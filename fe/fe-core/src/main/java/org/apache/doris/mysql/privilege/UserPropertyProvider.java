// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.mysql.privilege;

import org.apache.doris.common.Config;
import org.apache.doris.common.util.Daemon;
import org.apache.doris.metric.MetricRepo;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Keeps the user property document served at {@link Config#user_property_provider_url}.
 *
 * <p>While the URL is set, the served document is the only source of user properties, for every user
 * (see {@link UserPropertyMgr}). Property reads happen on every connection and query under the Auth lock,
 * so they only ever read the document held in memory; this daemon replaces it in the background.
 *
 * <p>The protocol follows the Ranger policy client's: each poll sends the version held as lastKnownVersion
 * (-1 when none) and this frontend's pluginId, and the provider answers 200 with a document or 304 when that
 * version is current. A failed poll or a malformed document keeps the document held. Every document served is
 * written to a cache file under meta_dir and read back at startup, before the frontend accepts connections,
 * so a restart while the provider is unreachable keeps the last document; only a frontend that has never
 * received one serves no users, so every user gets the defaults.
 *
 * <p>The provider is trusted the way the Ranger policy service is: the channel is plain HTTP inside one
 * network, and whoever can answer a poll decides every user's limits.
 */
public class UserPropertyProvider extends Daemon {
    private static final Logger LOG = LogManager.getLogger(UserPropertyProvider.class);

    // A document this large cannot be a list of account properties; refusing it bounds what one bad
    // response can allocate.
    private static final int MAX_DOCUMENT_BYTES = 16 << 20;

    private static final long DEFAULT_POLL_INTERVAL_MS = 30000;
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 30000;

    private static volatile UserPropertyDocument served = UserPropertyDocument.EMPTY;
    private static volatile long lastSuccessMs = 0;

    private final String url;
    private final String pluginId;
    private final Path cacheFile;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private boolean cacheLoaded = false;

    public UserPropertyProvider() {
        this(Config.user_property_provider_url, localPluginId(),
                Paths.get(Config.meta_dir, "user_property_provider", "document.json"),
                Config.user_property_provider_poll_interval_ms, Config.user_property_provider_connect_timeout_ms,
                Config.user_property_provider_read_timeout_ms);
    }

    UserPropertyProvider(String url, String pluginId, Path cacheFile, long pollIntervalMs, long connectTimeoutMs,
            long readTimeoutMs) {
        super("user-property-provider", positiveOr("user_property_provider_poll_interval_ms", pollIntervalMs,
                DEFAULT_POLL_INTERVAL_MS));
        this.url = url;
        this.pluginId = pluginId;
        this.cacheFile = cacheFile;
        this.connectTimeoutMs = timeoutOf("user_property_provider_connect_timeout_ms", connectTimeoutMs,
                DEFAULT_CONNECT_TIMEOUT_MS);
        this.readTimeoutMs = timeoutOf("user_property_provider_read_timeout_ms", readTimeoutMs,
                DEFAULT_READ_TIMEOUT_MS);
    }

    /** A poll interval of zero would poll in a loop, and a negative one stops the daemon at its first sleep. */
    private static long positiveOr(String key, long value, long defaultValue) {
        if (value > 0) {
            return value;
        }
        LOG.warn("user property provider: {} = {} is not positive, using {}", key, value, defaultValue);
        return defaultValue;
    }

    /** A timeout of zero waits forever, and one past an int cannot be set on a connection at all. */
    static int timeoutOf(String key, long value, int defaultValue) {
        return (int) Math.min(positiveOr(key, value, defaultValue), Integer.MAX_VALUE);
    }

    public static boolean isEnabled() {
        return !Strings.isNullOrEmpty(Config.user_property_provider_url);
    }

    /** The document held: the only source of user properties while the provider is enabled. */
    public static UserPropertyDocument served() {
        return served;
    }

    public static long lastSuccessMs() {
        return lastSuccessMs;
    }

    /** Drops the held document and the last-success time: static state, so tests clear it. */
    @VisibleForTesting
    public static synchronized void reset() {
        serve(UserPropertyDocument.EMPTY);
        lastSuccessMs = 0;
    }

    static synchronized void serve(UserPropertyDocument document) {
        UserPropertyDocument previous = served;
        served = document;
        // A user dropped from the document loses its gauge unless it still has an account, or there would be
        // one series per name ever listed, which for single-sign-on accounts is unbounded.
        MetricRepo.syncUserConnectionMaxMetrics(previous.getUsers().keySet());
    }

    /** The pluginId the Ranger plugin reports for this frontend, so both polls are recorded under one id. */
    private static String localPluginId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-doris";
        } catch (UnknownHostException e) {
            LOG.warn("user property provider: cannot resolve the local host name for the pluginId", e);
            return "unknown-doris";
        }
    }

    /** Reads the cache before the first poll, so connections accepted at startup see the last document. */
    @Override
    public synchronized void start() {
        loadCacheOnce();
        super.start();
    }

    /**
     * Reads the cache unless it has been read. A frontend becomes master and non-master more than once in its
     * life, and the cache read again then could be older than the document the daemon has served since.
     */
    public synchronized void loadCacheOnce() {
        if (!cacheLoaded) {
            cacheLoaded = true;
            loadCache();
        }
    }

    @Override
    protected void runOneCycle() {
        pollOnce();
    }

    void loadCache() {
        if (!Files.exists(cacheFile)) {
            LOG.warn("user property provider: no cached document at {}; every user gets the default properties"
                    + " until the provider at {} answers", cacheFile, url);
            return;
        }
        try {
            UserPropertyDocument document = UserPropertyDocument.parse(Files.readAllBytes(cacheFile));
            serve(document);
            LOG.info("user property provider: serving cached version {} with {} users", document.getVersion(),
                    document.getUsers().size());
        } catch (IOException | UserPropertyDocument.MalformedDocumentException | RuntimeException e) {
            // a bad cache must never stop a frontend from starting
            LOG.warn("user property provider: cannot read the cached document at {}; every user gets the default"
                    + " properties until the provider at {} answers", cacheFile, url, e);
        }
    }

    void pollOnce() {
        UserPropertyDocument held = served;
        long lastKnownVersion = held.getVersion() > 0 ? held.getVersion() : -1;
        int status;
        byte[] body;
        HttpURLConnection connection = null;
        try {
            URL pollUrl = new URL(url + (url.contains("?") ? "&" : "?") + "lastKnownVersion=" + lastKnownVersion
                    + "&pluginId=" + URLEncoder.encode(pluginId, "UTF-8"));
            connection = (HttpURLConnection) pollUrl.openConnection();
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("GET");
            status = connection.getResponseCode();
            body = status == 200 ? readFully(connection.getInputStream(), connection.getContentLength()) : new byte[0];
        } catch (IOException e) {
            LOG.warn("user property provider: cannot read the document from {}, keeping version {}: {}", url,
                    held.getVersion(),
                    e.getMessage());
            return;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        if (status == 304) {
            lastSuccessMs = System.currentTimeMillis();
            recheck(held);
            return;
        }
        if (status != 200) {
            LOG.warn("user property provider: {} answered {}, keeping version {}", url, status,
                    held.getVersion());
            return;
        }
        UserPropertyDocument document;
        try {
            document = UserPropertyDocument.parse(body);
        } catch (UserPropertyDocument.MalformedDocumentException e) {
            LOG.warn("user property provider: {} served a malformed document, keeping version {}: {}", url,
                    held.getVersion(), e.getMessage());
            return;
        }
        writeCache(document);
        serve(document);
        lastSuccessMs = System.currentTimeMillis();
        LOG.info("user property provider: serving version {} with {} users ({} refused)", document.getVersion(),
                document.getUsers().size(), document.getRejectedUsers().size());
    }

    /**
     * Whether Doris accepts a user's properties can change while the document does not: a workload group
     * created after the document named it, or dropped while another frontend's document still named it.
     * The held document is checked again on every unchanged poll, and served again when that changes which
     * users are refused.
     */
    private void recheck(UserPropertyDocument held) {
        if (held.getVersion() <= 0) {
            return;
        }
        try {
            UserPropertyDocument reparsed = UserPropertyDocument.parse(held.getBytes());
            if (!reparsed.getRejectedUsers().keySet().equals(held.getRejectedUsers().keySet())) {
                LOG.info("user property provider: version {} checked again, now {} refused users (was {})",
                        held.getVersion(), reparsed.getRejectedUsers().size(), held.getRejectedUsers().size());
                serve(reparsed);
            }
        } catch (UserPropertyDocument.MalformedDocumentException e) {
            // the held document parsed before, and parsing it again cannot change its shape
            LOG.warn("user property provider: cannot parse the held document again", e);
        }
    }

    private static byte[] readFully(InputStream in, int contentLength) throws IOException {
        if (contentLength > MAX_DOCUMENT_BYTES) {
            throw new IOException("the document is larger than " + MAX_DOCUMENT_BYTES + " bytes");
        }
        try (InputStream stream = in) {
            byte[] body = ByteStreams.toByteArray(ByteStreams.limit(stream, MAX_DOCUMENT_BYTES + 1L));
            if (body.length > MAX_DOCUMENT_BYTES) {
                throw new IOException("the document is larger than " + MAX_DOCUMENT_BYTES + " bytes");
            }
            return body;
        }
    }

    /** Written durably before it replaces the previous cache: the cache exists for the restart after a crash. */
    private void writeCache(UserPropertyDocument document) {
        try {
            Path directory = cacheFile.getParent();
            Files.createDirectories(directory);
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(document.getBytes());
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException e) {
            LOG.warn("user property provider: cannot write the cache {}; a restart before the next successful"
                    + " write starts from the previous cache", cacheFile, e);
        }
    }
}
