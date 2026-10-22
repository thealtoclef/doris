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

import org.apache.doris.catalog.Env;
import org.apache.doris.resource.workloadgroup.WorkloadGroupMgr;

import com.sun.net.httpserver.HttpServer;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public class UserPropertyProviderTest {
    private static final String DOCUMENT = "{\"version\":41,\"users\":{"
            + "\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}";

    @Rule
    public TemporaryFolder metaDir = new TemporaryFolder();

    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> queries = new ArrayList<>();
    private HttpServer server;
    private Path cacheFile;
    private UserPropertyProvider provider;

    private static final class Response {
        final int status;
        final byte[] body;
        final boolean chunked;

        Response(int status, String body) {
            this(status, body.getBytes(StandardCharsets.UTF_8), false);
        }

        Response(int status, byte[] body, boolean chunked) {
            this.status = status;
            this.body = body;
            this.chunked = chunked;
        }
    }

    @Before
    public void setUp() throws Exception {
        UserPropertyProvider.reset();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/user-properties", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            Response response = responses.isEmpty() ? new Response(500, "no response queued") : responses.poll();
            long length = response.chunked ? 0 : response.body.length == 0 ? -1 : response.body.length;
            exchange.sendResponseHeaders(response.status, length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response.body);
            }
        });
        server.start();
        cacheFile = metaDir.getRoot().toPath().resolve("user_property_provider").resolve("document.json");
        provider = providerAt(url());
    }

    @After
    public void tearDown() {
        server.stop(0);
        UserPropertyProvider.reset();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/user-properties";
    }

    private UserPropertyProvider providerAt(String url) {
        return new UserPropertyProvider(url, "fe1-doris", cacheFile, 30000, 1000, 1000);
    }

    /** A valid document, so only its size can refuse it. */
    private static byte[] oversized() {
        byte[] document = "{\"version\":42,\"users\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[(16 << 20) + 1];
        Arrays.fill(body, (byte) ' ');
        System.arraycopy(document, 0, body, 0, document.length);
        return body;
    }

    @Test
    public void theFirstPollServesTheDocumentAndCachesItAsServed() throws Exception {
        responses.add(new Response(200, DOCUMENT));

        provider.pollOnce();

        Assert.assertEquals("lastKnownVersion=-1&pluginId=fe1-doris", queries.get(0));
        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
        Assert.assertEquals(20, UserPropertyProvider.served().get("blue@cake.vn").getMaxConn());
        Assert.assertEquals(DOCUMENT, new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8));
    }

    @Test
    public void laterPollsSendTheHeldVersionAndKeepItWhenNotModified() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        responses.add(new Response(304, ""));

        provider.pollOnce();
        provider.pollOnce();

        Assert.assertEquals("lastKnownVersion=41&pluginId=fe1-doris", queries.get(1));
        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void failedOrMalformedResponseKeepsTheLastGoodDocument() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        responses.add(new Response(503, "no document compiled yet"));
        responses.add(new Response(200, "{\"version\":42}"));

        provider.pollOnce();
        provider.pollOnce();
        provider.pollOnce();

        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
        Assert.assertEquals(DOCUMENT, new String(Files.readAllBytes(cacheFile), StandardCharsets.UTF_8));
    }

    @Test
    public void anUnreachableProviderKeepsTheLastGoodDocument() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        provider.pollOnce();
        server.stop(0);

        provider.pollOnce();

        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void startingServesTheCachedDocumentBeforeTheProviderAnswers() throws Exception {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, DOCUMENT.getBytes(StandardCharsets.UTF_8));
        // nothing queued: every poll is answered 500
        try {
            provider.start();

            Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
            Assert.assertEquals(20, UserPropertyProvider.served().get("blue@cake.vn").getMaxConn());
        } finally {
            provider.exit();
        }
    }

    @Test
    public void restartWithoutACacheServesNoUsers() {
        provider.loadCacheOnce();

        Assert.assertTrue(UserPropertyProvider.served().getUsers().isEmpty());
    }

    @Test
    public void corruptCacheServesNoUsersRatherThanStoppingTheStart() throws Exception {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, "{\"version\":41,\"us".getBytes(StandardCharsets.UTF_8));

        provider.loadCacheOnce();

        Assert.assertEquals(0, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void theCacheIsReadOnceSoALaterRoleChangeKeepsTheNewerDocument() throws Exception {
        Files.createDirectories(cacheFile.getParent());
        Files.write(cacheFile, DOCUMENT.getBytes(StandardCharsets.UTF_8));
        provider.loadCacheOnce();
        // a newer document served, whose cache write failed
        UserPropertyProvider.serve(UserPropertyDocument.parse(
                "{\"version\":42,\"users\":{}}".getBytes(StandardCharsets.UTF_8)));

        provider.loadCacheOnce();

        Assert.assertEquals(42, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void cacheThatCannotBeWrittenStillServesTheDocument() throws Exception {
        // the cache directory's place is taken by a file
        Files.write(cacheFile.getParent(), new byte[0]);
        responses.add(new Response(200, DOCUMENT));

        provider.pollOnce();

        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void refusedUserIsCheckedAgainWhileTheDocumentIsUnchanged(@Mocked Env env,
            @Mocked WorkloadGroupMgr workloadGroupMgr) throws Exception {
        new Expectations() {
            {
                Env.getCurrentEnv();
                minTimes = 0;
                result = env;

                env.getWorkloadGroupMgr();
                minTimes = 0;
                result = workloadGroupMgr;

                // the workload group is created after the document is first served
                workloadGroupMgr.isWorkloadGroupExists("etl_group");
                returns(false, true);
            }
        };
        responses.add(new Response(200,
                "{\"version\":5,\"users\":{\"etl\":{\"default_workload_group\":\"etl_group\"}}}"));
        responses.add(new Response(304, ""));

        provider.pollOnce();
        Assert.assertNull(UserPropertyProvider.served().get("etl"));
        provider.pollOnce();

        Assert.assertEquals("etl_group", UserPropertyProvider.served().get("etl").getWorkloadGroup());
    }

    @Test
    public void anAcceptedUserIsRefusedOnceItsWorkloadGroupIsDropped(@Mocked Env env,
            @Mocked WorkloadGroupMgr workloadGroupMgr) throws Exception {
        new Expectations() {
            {
                Env.getCurrentEnv();
                minTimes = 0;
                result = env;

                env.getWorkloadGroupMgr();
                minTimes = 0;
                result = workloadGroupMgr;

                // the master dropped the group before this frontend's document stopped naming it
                workloadGroupMgr.isWorkloadGroupExists("etl_group");
                returns(true, false);
            }
        };
        responses.add(new Response(200,
                "{\"version\":5,\"users\":{\"etl\":{\"default_workload_group\":\"etl_group\"}}}"));
        responses.add(new Response(304, ""));

        provider.pollOnce();
        Assert.assertEquals("etl_group", UserPropertyProvider.served().get("etl").getWorkloadGroup());
        provider.pollOnce();

        Assert.assertNull(UserPropertyProvider.served().get("etl"));
    }

    @Test
    public void anUnchangedPollThatChangesNothingServesNothingNew() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        responses.add(new Response(304, ""));
        provider.pollOnce();
        UserPropertyDocument held = UserPropertyProvider.served();

        provider.pollOnce();

        Assert.assertSame(held, UserPropertyProvider.served());
    }

    @Test
    public void anOversizedResponseKeepsTheLastGoodDocument() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        provider.pollOnce();
        responses.add(new Response(200, oversized(), false));

        provider.pollOnce();

        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void anOversizedResponseWithoutALengthKeepsTheLastGoodDocument() throws Exception {
        responses.add(new Response(200, DOCUMENT));
        provider.pollOnce();
        responses.add(new Response(200, oversized(), true));

        provider.pollOnce();

        Assert.assertEquals(41, UserPropertyProvider.served().getVersion());
    }

    @Test
    public void theQueryIsAddedToAUrlThatAlreadyHasOne() throws Exception {
        responses.add(new Response(200, DOCUMENT));

        new UserPropertyProvider(url() + "?cluster=a", "fe 1+x", cacheFile, 30000, 1000, 1000).pollOnce();

        Assert.assertEquals("cluster=a&lastKnownVersion=-1&pluginId=fe+1%2Bx", queries.get(0));
    }

    @Test
    public void lastSuccessIsTheLastPollTheProviderAnswered() throws Exception {
        Assert.assertEquals(0, UserPropertyProvider.lastSuccessMs());
        responses.add(new Response(200, DOCUMENT));
        provider.pollOnce();
        long afterDocument = UserPropertyProvider.lastSuccessMs();
        Assert.assertTrue(afterDocument > 0);

        Thread.sleep(5);
        responses.add(new Response(304, ""));
        provider.pollOnce();
        long afterNotModified = UserPropertyProvider.lastSuccessMs();
        Assert.assertTrue(afterNotModified > afterDocument);

        responses.add(new Response(503, "no document compiled yet"));
        provider.pollOnce();
        Assert.assertEquals(afterNotModified, UserPropertyProvider.lastSuccessMs());
    }

    @Test
    public void settingThatIsNotPositiveUsesItsDefault() {
        Assert.assertEquals(30000,
                new UserPropertyProvider(url(), "fe1-doris", cacheFile, 0, 1000, 1000).getInterval());
        Assert.assertEquals(30000,
                new UserPropertyProvider(url(), "fe1-doris", cacheFile, -1, 1000, 1000).getInterval());
        Assert.assertEquals(10000, UserPropertyProvider.timeoutOf("connect", 0, 10000));
        Assert.assertEquals(10000, UserPropertyProvider.timeoutOf("connect", -5, 10000));
        Assert.assertEquals(Integer.MAX_VALUE, UserPropertyProvider.timeoutOf("connect", 1L << 40, 10000));
        Assert.assertEquals(250, UserPropertyProvider.timeoutOf("connect", 250, 10000));
    }
}
