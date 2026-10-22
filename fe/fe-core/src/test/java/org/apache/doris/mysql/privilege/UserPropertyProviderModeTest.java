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

import org.apache.doris.analysis.RedirectStatus;
import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.Config;
import org.apache.doris.common.proc.UserPropertyProcNode;
import org.apache.doris.metric.GaugeMetricImpl;
import org.apache.doris.metric.MetricRepo;
import org.apache.doris.nereids.trees.plans.commands.SetUserPropertiesCommand;
import org.apache.doris.nereids.trees.plans.commands.ShowUserPropertyCommand;
import org.apache.doris.utframe.TestWithFeService;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** The SQL statements on user properties while a user property provider is enabled. */
public class UserPropertyProviderModeTest extends TestWithFeService {

    @AfterEach
    public void disableProvider() {
        Config.user_property_provider_url = "";
        UserPropertyProvider.reset();
    }

    private static void serve(String json) throws Exception {
        Config.user_property_provider_url = "http://127.0.0.1:1/v1/user-properties";
        UserPropertyProvider.serve(UserPropertyDocument.parse(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void setPropertyIsRefused() throws Exception {
        executeSql("CREATE USER provider_mode_etl IDENTIFIED BY '12345'");
        serve("{\"version\":1,\"users\":{}}");

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () ->
                executeSql("SET PROPERTY FOR 'provider_mode_etl' 'max_user_connections' = '5'"));

        Assertions.assertTrue(e.getMessage().contains(
                "Set property is prohibited when the user property provider is enabled"), e.getMessage());
        disableProvider();
        Assertions.assertEquals(100, Env.getCurrentEnv().getAuth().getMaxConn("provider_mode_etl"));
    }

    @Test
    public void setPropertyForOneselfIsRefused() throws Exception {
        serve("{\"version\":1,\"users\":{}}");

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () ->
                executeSql("SET PROPERTY 'max_user_connections' = '5'"));

        Assertions.assertTrue(e.getMessage().contains(
                "Set property is prohibited when the user property provider is enabled"), e.getMessage());
    }

    @Test
    public void setPropertyIsRefusedWhereItIsIssuedRatherThanForwarded() throws Exception {
        SetUserPropertiesCommand command = new SetUserPropertiesCommand("etl", Collections.emptyList());
        Assertions.assertEquals(RedirectStatus.FORWARD_WITH_SYNC, command.toRedirectStatus());

        serve("{\"version\":1,\"users\":{}}");

        Assertions.assertEquals(RedirectStatus.NO_FORWARD, command.toRedirectStatus());
    }

    @Test
    public void showPropertyShowsTheDefaultsOfAUserWhoseEntryIsRefused() throws Exception {
        serve("{\"version\":1,\"users\":{\"bad@cake.vn\":{\"max_user_connections\":\"not-a-number\"}}}");

        List<List<String>> rows = new ShowUserPropertyCommand("bad@cake.vn", "max_user_connections", false)
                .doRun(connectContext, null).getResultRows();

        Assertions.assertEquals(ImmutableList.of(ImmutableList.of("max_user_connections", "100")), rows);
    }

    @Test
    public void theAuthProcShowsTheServedValues() throws Exception {
        executeSql("CREATE USER provider_mode_proc IDENTIFIED BY '12345'");
        serve("{\"version\":1,\"users\":{\"provider_mode_proc\":{\"max_user_connections\":\"7\"}}}");

        List<List<String>> rows = new UserPropertyProcNode(Env.getCurrentEnv().getAuth(),
                UserIdentity.createAnalyzedUserIdentWithIp("provider_mode_proc", "%")).fetchResult().getRows();

        Assertions.assertTrue(rows.contains(ImmutableList.of("max_user_connections", "7")), rows.toString());
    }

    @Test
    public void workloadGroupOnlyStoredPropertiesNameCanBeDropped() throws Exception {
        executeSql("CREATE WORKLOAD GROUP IF NOT EXISTS provider_mode_stored "
                + " PROPERTIES ('min_memory_percent'='1', 'max_memory_percent'='30%')");
        executeSql("CREATE USER provider_mode_wg IDENTIFIED BY '12345'");
        executeSql("SET PROPERTY FOR 'provider_mode_wg' 'default_workload_group' = 'provider_mode_stored'");
        serve("{\"version\":1,\"users\":{}}");

        executeSql("DROP WORKLOAD GROUP provider_mode_stored");

        Assertions.assertFalse(Env.getCurrentEnv().getWorkloadGroupMgr().isWorkloadGroupExists("provider_mode_stored"));
    }

    @Test
    public void workloadGroupTheDocumentNamesCannotBeDropped() throws Exception {
        executeSql("CREATE WORKLOAD GROUP IF NOT EXISTS provider_mode_served "
                + " PROPERTIES ('min_memory_percent'='1', 'max_memory_percent'='30%')");
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"default_workload_group\":\"provider_mode_served\"}}}");

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () ->
                executeSql("DROP WORKLOAD GROUP provider_mode_served"));

        Assertions.assertTrue(e.getMessage().contains("Workload group provider_mode_served is set for user"
                + " blue@cake.vn by the user property document"), e.getMessage());
    }

    @Test
    public void userDroppedFromTheDocumentKeepsItsGaugeOnlyWithAnAccount() throws Exception {
        MetricRepo.init();
        executeSql("CREATE USER provider_mode_gauge IDENTIFIED BY '12345'");
        serve("{\"version\":1,\"users\":{\"provider_mode_gauge\":{\"max_user_connections\":\"7\"},"
                + "\"gone@cake.vn\":{\"max_user_connections\":\"8\"}}}");
        Assertions.assertEquals(7L, gauge("provider_mode_gauge").getValue());
        Assertions.assertEquals(8L, gauge("gone@cake.vn").getValue());

        serve("{\"version\":2,\"users\":{}}");

        Assertions.assertEquals(100L, gauge("provider_mode_gauge").getValue());
        Assertions.assertNull(gauge("gone@cake.vn"));
    }

    @Test
    public void droppingTheAccountOfAListedUserKeepsItsServedGauge() throws Exception {
        MetricRepo.init();
        executeSql("CREATE USER provider_mode_listed IDENTIFIED BY '12345'");
        serve("{\"version\":1,\"users\":{\"provider_mode_listed\":{\"max_user_connections\":\"7\"}}}");

        executeSql("DROP USER provider_mode_listed");

        Assertions.assertEquals(7L, gauge("provider_mode_listed").getValue());
    }

    private static GaugeMetricImpl<Long> gauge(String user) {
        return MetricRepo.USER_GAUGE_CONNECTION_MAX.getMetrics().get(user);
    }

    @Test
    public void showPropertyShowsTheServedValuesOfAUserWithNoAccount() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        List<List<String>> rows = new ShowUserPropertyCommand("blue@cake.vn", "max_user_connections", false)
                .doRun(connectContext, null).getResultRows();

        Assertions.assertEquals(ImmutableList.of(ImmutableList.of("max_user_connections", "20")), rows);
    }

    @Test
    public void showAllPropertiesListsTheServedUsers() throws Exception {
        serve("{\"version\":1,\"users\":{\"blue@cake.vn\":{\"max_user_connections\":\"20\"}}}");

        List<List<String>> rows = new ShowUserPropertyCommand(null, "max_user_connections", true)
                .doRun(connectContext, null).getResultRows();

        Assertions.assertTrue(rows.contains(ImmutableList.of("blue@cake.vn", "{\"max_user_connections\":\"20\"}")),
                rows.toString());
        Assertions.assertTrue(rows.contains(ImmutableList.of("root", "{\"max_user_connections\":\"100\"}")),
                rows.toString());
    }
}
