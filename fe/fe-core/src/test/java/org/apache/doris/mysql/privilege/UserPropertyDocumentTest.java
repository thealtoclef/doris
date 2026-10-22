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

import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class UserPropertyDocumentTest {

    private static UserPropertyDocument parse(String json) throws UserPropertyDocument.MalformedDocumentException {
        return UserPropertyDocument.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void servesEachUsersPropertiesParsedTheWaySetPropertyDoes() throws Exception {
        UserPropertyDocument document = parse("{\"version\":7,\"users\":{"
                + "\"blue@cake.vn\":{\"max_user_connections\":\"20\",\"query_timeout\":\"60\"}}}");

        Assert.assertEquals(7, document.getVersion());
        Assert.assertEquals(20, document.get("blue@cake.vn").getMaxConn());
        Assert.assertEquals(60, document.get("blue@cake.vn").getQueryTimeout());
        Assert.assertNull(document.get("someone@cake.vn"));
    }

    @Test
    public void matchesUsernamesExactly() throws Exception {
        UserPropertyDocument document = parse("{\"version\":1,\"users\":{"
                + "\"system:serviceaccount:analytics:airflow\":{\"max_user_connections\":\"3\"}}}");

        Assert.assertEquals(3, document.get("system:serviceaccount:analytics:airflow").getMaxConn());
        Assert.assertNull(document.get("System:ServiceAccount:analytics:airflow"));
        Assert.assertNull(document.get("airflow"));
    }

    @Test
    public void leavesOutAUserWhosePropertiesDorisRefusesAndKeepsTheOthers() throws Exception {
        UserPropertyDocument document = parse("{\"version\":2,\"users\":{"
                + "\"good@cake.vn\":{\"max_user_connections\":\"5\"},"
                + "\"bad@cake.vn\":{\"max_user_connections\":\"not-a-number\"},"
                + "\"unknown@cake.vn\":{\"no_such_property\":\"1\"}}}");

        Assert.assertEquals(5, document.get("good@cake.vn").getMaxConn());
        Assert.assertNull(document.get("bad@cake.vn"));
        Assert.assertNull(document.get("unknown@cake.vn"));
        Assert.assertEquals("max_user_connections is not number",
                document.getRejectedUsers().get("bad@cake.vn"));
        Assert.assertEquals("Unknown user property(no_such_property)",
                document.getRejectedUsers().get("unknown@cake.vn"));
    }

    @Test
    public void refusesADocumentThatIsNotShapedAsSpecified() {
        String[] malformed = {
                "not json",
                "[]",
                "{\"users\":{}}",
                "{\"version\":0,\"users\":{}}",
                "{\"version\":1.5,\"users\":{}}",
                "{\"version\":\"1\",\"users\":{}}",
                "{\"version\":1}",
                "{\"version\":1,\"users\":[]}",
                "{\"version\":1,\"users\":{\"a\":\"max_user_connections=1\"}}",
                "{\"version\":1,\"users\":{\"a\":{\"max_user_connections\":1}}}",
                // only integer literals are versions
                "{\"version\":1.0,\"users\":{}}",
                "{\"version\":1e3,\"users\":{}}",
                "{\"version\":-0,\"users\":{}}",
                "{\"version\":9223372036854775808,\"users\":{}}",
                // strict JSON only
                "{version:1,users:{}}",
                "{'version':1,'users':{}}",
                "{\"version\":1,/* comment */\"users\":{}}",
                ")]}'\n{\"version\":1,\"users\":{}}",
                "{\"version\":1,\"users\":{\"a\":{\"max_user_connections\":20x}}}",
                "{\"version\":1,\"users\":{}} {}",
                // a name given twice leaves which value applies unsaid
                "{\"version\":1,\"version\":2,\"users\":{}}",
                "{\"version\":1,\"users\":{\"a\":{\"query_timeout\":\"1\"},\"a\":{\"query_timeout\":\"2\"}}}",
                "{\"version\":1,\"users\":{\"a\":{\"query_timeout\":\"1\",\"query_timeout\":\"2\"}}}",
        };
        for (String json : malformed) {
            Assert.assertThrows(json, UserPropertyDocument.MalformedDocumentException.class, () -> parse(json));
        }
    }

    @Test
    public void refusesADocumentThatIsNotUtf8() {
        byte[] latin1 = "{\"version\":1,\"users\":{\"b?a\":{\"query_timeout\":\"1\"}}}"
                .getBytes(StandardCharsets.US_ASCII);
        // 'é' in Latin-1, a byte that cannot start a UTF-8 sequence here
        latin1[new String(latin1, StandardCharsets.US_ASCII).indexOf('?')] = (byte) 0xE9;

        Assert.assertThrows(UserPropertyDocument.MalformedDocumentException.class,
                () -> UserPropertyDocument.parse(latin1));
    }

    @Test
    public void refusesAnEntryThatCouldNeverApplyAndKeepsTheOthers() throws Exception {
        UserPropertyDocument document = parse("{\"version\":1,\"users\":{"
                + "\"good@cake.vn\":{\"max_user_connections\":\"5\"},"
                + "\"default_cluster:etl\":{\"max_user_connections\":\"5\"},"
                + "\"twice@cake.vn\":{\"max_user_connections\":\"5\",\"MAX_USER_CONNECTIONS\":\"6\"}}}");

        Assert.assertEquals(5, document.get("good@cake.vn").getMaxConn());
        Assert.assertEquals("a login never carries the default_cluster: prefix, so this name matches no user",
                document.getRejectedUsers().get("default_cluster:etl"));
        Assert.assertEquals("property 'MAX_USER_CONNECTIONS' is set more than once",
                document.getRejectedUsers().get("twice@cake.vn"));
        Assert.assertEquals(1, document.getUsers().size());
    }

    @Test
    public void checkThatFailsUnexpectedlyCostsOnlyItsEntry(@Mocked Env env,
            @Mocked WorkloadGroupMgr workloadGroupMgr) throws Exception {
        new Expectations() {
            {
                Env.getCurrentEnv();
                minTimes = 0;
                result = env;

                env.getWorkloadGroupMgr();
                minTimes = 0;
                result = workloadGroupMgr;

                workloadGroupMgr.isWorkloadGroupExists("etl_group");
                result = new IllegalStateException("workload groups are not loaded");
            }
        };

        UserPropertyDocument document = parse("{\"version\":1,\"users\":{"
                + "\"etl\":{\"default_workload_group\":\"etl_group\"},"
                + "\"good@cake.vn\":{\"max_user_connections\":\"5\"}}}");

        Assert.assertEquals(5, document.get("good@cake.vn").getMaxConn());
        Assert.assertEquals("java.lang.IllegalStateException: workload groups are not loaded",
                document.getRejectedUsers().get("etl"));
    }

    @Test
    public void ignoresFieldsItDoesNotKnow() throws Exception {
        UserPropertyDocument document = parse("{\"version\":3,\"generatedBy\":\"x\",\"users\":{}}");

        Assert.assertEquals(3, document.getVersion());
        Assert.assertTrue(document.getUsers().isEmpty());
    }

    /**
     * The documents a provider must produce for a given set of users are pinned, together with doris-manager,
     * which keeps a byte-identical copy. A change to either the format or a fixture fails here.
     */
    @Test
    public void goldenDocumentsOfTheProviderContract() throws Exception {
        Assert.assertEquals("7dddd82c8fc45496b8efe61c6a971a9b9877722b7ef1058334cb43d2778ce5e6",
                sha256("basic.json"));
        Assert.assertEquals("148e5099f3cecc50890c93730175a6ada074c6d81ed88546451fa9b494d3e40c",
                sha256("empty.json"));
        Assert.assertEquals("fa3b7a25fe3af8b96229c0f3b94577e1569700f31f1ce4aa9d871d3d5eedb0e0",
                sha256("invalid_entry.json"));

        UserPropertyDocument basic = UserPropertyDocument.parse(resource("basic.json"));
        Assert.assertEquals(4744942945230521452L, basic.getVersion());
        Assert.assertEquals(3, basic.getUsers().size());
        Assert.assertEquals(20, basic.get("blue@cake.vn").getMaxConn());
        Assert.assertEquals(1000, basic.get("etl").getMaxConn());
        Assert.assertEquals(3600, basic.get("etl").getQueryTimeout());
        Assert.assertEquals(8589934592L, basic.get("system:serviceaccount:analytics:airflow").getExecMemLimit());

        Assert.assertTrue(UserPropertyDocument.parse(resource("empty.json")).getUsers().isEmpty());

        UserPropertyDocument invalid = UserPropertyDocument.parse(resource("invalid_entry.json"));
        Assert.assertEquals(1, invalid.getUsers().size());
        Assert.assertEquals(5, invalid.get("good@cake.vn").getMaxConn());
        Assert.assertEquals(2, invalid.getRejectedUsers().size());
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = UserPropertyDocumentTest.class.getResourceAsStream("/user_property_provider/" + name)) {
            Assert.assertNotNull(name, in);
            return ByteStreams.toByteArray(in);
        }
    }

    private static String sha256(String name) throws Exception {
        return Hashing.sha256().hashBytes(resource(name)).toString();
    }
}
