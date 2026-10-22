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
import org.apache.doris.cloud.system.CloudSystemInfoService;
import org.apache.doris.common.Config;
import org.apache.doris.common.Pair;
import org.apache.doris.resource.workloadgroup.WorkloadGroupMgr;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * How user properties resolve while a user property provider is configured: the served document is the
 * only source, for internal and external-auth users alike.
 */
public class ServedUserPropertyLookupTest {
    private static final String PROVIDER_URL = "http://127.0.0.1:1/v1/user-properties";

    private UserPropertyMgr propertyMgr;

    @Before
    public void setUp() throws Exception {
        propertyMgr = new UserPropertyMgr();
        // an internal account whose properties were set before the provider was configured
        propertyMgr.addUserResource("etl");
        propertyMgr.updateUserProperty("etl", Lists.newArrayList(Pair.of("max_user_connections", "500")), false);
    }

    @After
    public void tearDown() {
        Config.user_property_provider_url = "";
        UserPropertyProvider.reset();
    }

    private static void serve(String json) throws Exception {
        Config.user_property_provider_url = PROVIDER_URL;
        UserPropertyProvider.serve(UserPropertyDocument.parse(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void servedPropertiesApplyToInternalAndExternalUsersAlike() throws Exception {
        serve("{\"version\":1,\"users\":{"
                + "\"etl\":{\"max_user_connections\":\"1000\",\"query_timeout\":\"3600\"},"
                + "\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        Assert.assertEquals(1000, propertyMgr.getMaxConn("etl"));
        Assert.assertEquals(3600, propertyMgr.getQueryTimeout("etl"));
        // no session is needed: the name alone selects the entry
        Assert.assertEquals(20, propertyMgr.getMaxConn("blue@cake.vn"));
    }

    @Test
    public void anUnlistedUserGetsTheDefaultsEvenWithStoredProperties() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        Assert.assertEquals(100, propertyMgr.getMaxConn("etl"));
        Assert.assertEquals(100, propertyMgr.getMaxConn("someone@cake.vn"));
        Assert.assertEquals(-1, propertyMgr.getQueryTimeout("someone@cake.vn"));
        Assert.assertEquals(-1, propertyMgr.getExecMemLimit("someone@cake.vn"));
        Assert.assertEquals("internal", propertyMgr.getInitCatalog("someone@cake.vn"));
        Assert.assertEquals(WorkloadGroupMgr.DEFAULT_GROUP_NAME, propertyMgr.getWorkloadGroup("someone@cake.vn"));
        Assert.assertEquals(0, propertyMgr.getSqlBlockRules("someone@cake.vn").length);
    }

    @Test
    public void beforeAnyDocumentEveryUserGetsTheDefaults() {
        Config.user_property_provider_url = PROVIDER_URL;

        Assert.assertEquals(100, propertyMgr.getMaxConn("etl"));
        Assert.assertEquals(100, propertyMgr.getMaxConn("blue@cake.vn"));
    }

    @Test
    public void withoutAProviderStoredPropertiesApplyAsBefore() throws Exception {
        UserPropertyProvider.serve(UserPropertyDocument.parse(
                "{\"version\":1,\"users\":{\"etl\":{\"max_user_connections\":\"1000\"}}}"
                        .getBytes(StandardCharsets.UTF_8)));

        Assert.assertEquals(500, propertyMgr.getMaxConn("etl"));
    }

    @Test
    public void internalAccountNamesKeepTheirCase() throws Exception {
        // a second internal account, distinct from 'etl' only by case
        propertyMgr.addUserResource("ETL");
        serve("{\"version\":1,\"users\":{\"ETL\":{\"max_user_connections\":\"30\"}}}");

        Assert.assertEquals(30, propertyMgr.getMaxConn("ETL"));
        // 'etl' is not listed, so its stored 500 is ignored and it gets the default
        Assert.assertEquals(100, propertyMgr.getMaxConn("etl"));
        Assert.assertEquals(100, propertyMgr.getMaxConn("Etl"));
    }

    @Test
    public void showPropertyListsServedValuesForAnyUser() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        Assert.assertEquals("20", valueOf(propertyMgr.fetchUserProperty("blue@cake.vn"), "max_user_connections"));
        Assert.assertEquals("100", valueOf(propertyMgr.fetchUserProperty("etl"), "max_user_connections"));
    }

    @Test
    public void maxConnectionMetricCoversTheAccountsAndTheServedUsers() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        Assert.assertEquals(ImmutableMap.of("blue@cake.vn", 20L, "etl", 100L), propertyMgr.getMaxConnForAllUsers());
    }

    @Test
    public void defaultComputeGroupComesFromTheDocumentWithoutAStoredUser() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"default_compute_group\":\"analytics\"}}}");

        Assert.assertEquals("analytics", propertyMgr.getDefaultCloudCluster("blue@cake.vn"));
        Assert.assertNull(propertyMgr.getDefaultCloudCluster("someone@cake.vn"));
    }

    @Test
    public void onlyTheDocumentKeepsAWorkloadGroupInUseWhileServed(@Mocked Env env,
            @Mocked WorkloadGroupMgr workloadGroupMgr) throws Exception {
        new Expectations() {
            {
                Env.getCurrentEnv();
                minTimes = 0;
                result = env;

                env.getWorkloadGroupMgr();
                minTimes = 0;
                result = workloadGroupMgr;

                workloadGroupMgr.isWorkloadGroupExists(anyString);
                minTimes = 0;
                result = true;
            }
        };
        propertyMgr.updateUserProperty("etl",
                Lists.newArrayList(Pair.of("default_workload_group", "stored_group")), false);
        Assert.assertEquals(Pair.of(true, "etl"), propertyMgr.isWorkloadGroupInUse("stored_group"));

        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"default_workload_group\":\"etl_group\"}}}");

        Assert.assertEquals(Pair.of(true, "blue@cake.vn"), propertyMgr.isWorkloadGroupInUse("etl_group"));
        Assert.assertEquals(Pair.of(false, ""), propertyMgr.isWorkloadGroupInUse("stored_group"));
    }

    @Test
    public void servedComputeGroupMustExist(@Mocked Env env, @Mocked CloudSystemInfoService systemInfo)
            throws Exception {
        new Expectations() {
            {
                Env.getCurrentSystemInfo();
                minTimes = 0;
                result = systemInfo;

                systemInfo.getCloudClusterNames();
                minTimes = 0;
                result = Lists.newArrayList("analytics");
            }
        };
        String deployMode = Config.deploy_mode;
        Config.deploy_mode = "cloud";
        try {
            serve("{\"version\":1,\"users\":{"
                    + "\"blue@cake.vn\":{\"default_compute_group\":\"analytics\"},"
                    + "\"etl\":{\"default_compute_group\":\"missing\"}}}");
        } finally {
            Config.deploy_mode = deployMode;
        }

        Assert.assertEquals("analytics", propertyMgr.getDefaultCloudCluster("blue@cake.vn"));
        Assert.assertTrue(UserPropertyProvider.served().getRejectedUsers().get("etl").contains("missing not exist"));
    }

    private static String valueOf(List<List<String>> rows, String key) {
        for (List<String> row : rows) {
            if (row.get(0).equals(key)) {
                return row.get(1);
            }
        }
        return null;
    }
}
