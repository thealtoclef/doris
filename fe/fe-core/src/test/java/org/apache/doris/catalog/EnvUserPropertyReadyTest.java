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

package org.apache.doris.catalog;

import org.apache.doris.common.Config;
import org.apache.doris.mysql.privilege.UserPropertyProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** A follower accepts connections as soon as it reports ready, so its cached document must be served first. */
public class EnvUserPropertyReadyTest {
    private static final String DOCUMENT =
            "{\"version\":41,\"users\":{\"etl\":{\"max_user_connections\":\"7\"}}}";

    @TempDir
    public Path tempDir;

    @AfterEach
    public void tearDown() {
        Config.user_property_provider_url = "";
        Config.meta_dir = metaDir;
        UserPropertyProvider.reset();
    }

    private final String metaDir = Config.meta_dir;

    @Test
    public void theCachedDocumentIsServedBeforeTheFrontendReportsReady() throws Exception {
        Path cache = tempDir.resolve("user_property_provider").resolve("document.json");
        Files.createDirectories(cache.getParent());
        // a pipe where the cache should be: the read cannot finish until the test writes the document, so
        // whether ready is reported before or after that read becomes observable
        Assertions.assertEquals(0, new ProcessBuilder("mkfifo", cache.toString()).inheritIO().start().waitFor(),
                "mkfifo");
        Config.user_property_provider_url = "http://127.0.0.1:1/v1/user-properties";
        Config.meta_dir = tempDir.toString();
        Env env = new Env(false);
        Assertions.assertEquals(0, UserPropertyProvider.served().getVersion());

        Thread ready = new Thread(env::becomeReadyToRead);
        ready.start();
        ready.join(1000);
        Assertions.assertTrue(ready.isAlive(), "ready was reported without reading the cache");
        Assertions.assertFalse(env.isReady(), "ready was reported before the cached document was served");

        try (OutputStream writer = Files.newOutputStream(cache)) {
            writer.write(DOCUMENT.getBytes(StandardCharsets.UTF_8));
        }
        ready.join(5000);

        Assertions.assertFalse(ready.isAlive(), "ready was never reported");
        Assertions.assertTrue(env.isReady());
        Assertions.assertEquals(41, UserPropertyProvider.served().getVersion());
        Assertions.assertEquals(7, UserPropertyProvider.served().get("etl").getMaxConn());
    }
}
