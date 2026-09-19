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

import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.authorizer.ranger.doris.DorisAccessType;
import org.apache.doris.catalog.authorizer.ranger.doris.RangerDorisAccessController;
import org.apache.doris.catalog.authorizer.ranger.doris.RangerDorisResource;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.ranger.plugin.model.RangerServiceDef;
import org.apache.ranger.plugin.policyengine.RangerAccessRequest;
import org.apache.ranger.plugin.policyengine.RangerAccessResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * Impersonation through a policy layer: the account being adopted is the resource.
 */
public class RangerImpersonationTest {
    private static RangerServiceDef serviceDefWith(String... resourceNames) {
        RangerServiceDef serviceDef = new RangerServiceDef();
        List<RangerServiceDef.RangerResourceDef> resources = Lists.newArrayList();
        for (String name : resourceNames) {
            RangerServiceDef.RangerResourceDef resource = new RangerServiceDef.RangerResourceDef();
            resource.setName(name);
            resources.add(resource);
        }
        serviceDef.setResources(resources);
        return serviceDef;
    }

    @Test
    public void testTheAccountBeingAdoptedIsTheResource() {
        List<RangerAccessRequest> asked = Lists.newArrayList();
        RangerTest.DorisTestPlugin plugin = new RangerTest.DorisTestPlugin("test") {
            @Override
            public RangerServiceDef getServiceDef() {
                return serviceDefWith("global", RangerDorisResource.KEY_USER);
            }

            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                asked.add(request);
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                result.setIsAllowed(true);
                return result;
            }
        };
        RangerDorisAccessController ac = new RangerDorisAccessController(plugin);

        Assertions.assertTrue(ac.checkImpersonatePriv(
                UserIdentity.createAnalyzedUserIdentWithIp("gateway", "%"), "alice"));

        Assertions.assertEquals(1, asked.size(), asked.toString());
        Assertions.assertEquals(DorisAccessType.IMPERSONATE.name(), asked.get(0).getAccessType());
        Assertions.assertEquals("alice", asked.get(0).getResource().getValue(RangerDorisResource.KEY_USER));
        Assertions.assertEquals("gateway", asked.get(0).getUser());
    }

    @Test
    public void testTheAccountDecidesWhereTheServiceDefinitionModelsAccounts() {
        RangerTest.DorisTestPlugin plugin = new RangerTest.DorisTestPlugin("test") {
            @Override
            public RangerServiceDef getServiceDef() {
                return serviceDefWith("global", RangerDorisResource.KEY_USER);
            }

            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                // every global request is allowed; no account is
                result.setIsAllowed(request.getResource().getValue(RangerDorisResource.KEY_USER) == null);
                return result;
            }
        };
        RangerDorisAccessController ac = new RangerDorisAccessController(plugin);
        UserIdentity gateway = UserIdentity.createAnalyzedUserIdentWithIp("gateway", "%");

        Assertions.assertFalse(ac.checkImpersonatePriv(gateway, "sre-oncall"));
        Assertions.assertTrue(ac.checkGlobalPriv(gateway, PrivPredicate.ADMIN));
    }

    @Test
    public void testAServiceDefinitionWithoutTheResourceAsksTheGlobalPrivilege() {
        List<RangerAccessRequest> asked = Lists.newArrayList();
        RangerTest.DorisTestPlugin plugin = new RangerTest.DorisTestPlugin("test") {
            @Override
            public RangerServiceDef getServiceDef() {
                return serviceDefWith("global");
            }

            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                asked.add(request);
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                result.setIsAllowed("gateway".equals(request.getUser())
                        && DorisAccessType.ADMIN.name().equals(request.getAccessType()));
                return result;
            }
        };
        RangerDorisAccessController ac = new RangerDorisAccessController(plugin);

        // The global privilege decides, in both directions: an engine that allowed every fallback would
        // adopt an account for a caller whose global privilege is denied.
        Assertions.assertTrue(ac.checkImpersonatePriv(
                UserIdentity.createAnalyzedUserIdentWithIp("gateway", "%"), "alice"));
        Assertions.assertFalse(ac.checkImpersonatePriv(
                UserIdentity.createAnalyzedUserIdentWithIp("nobody", "%"), "alice"));
        Assertions.assertTrue(asked.stream()
                        .noneMatch(request -> request.getResource().getValue(RangerDorisResource.KEY_USER) != null),
                asked.toString());
    }

    /**
     * The service definition is read to decide which of the two rules applies, and a Ranger policy refresh
     * can land between that read and the check. A definition that declares accounts while the global check
     * runs must decide by account, so that the fallback cannot let a global administrator through once the
     * deployment has narrowed who may be adopted.
     */
    @Test
    public void testADefinitionThatGainsAccountsWhileTheGlobalCheckRunsStillDecidesByAccount() {
        RangerTest.DorisTestPlugin plugin = new RangerTest.DorisTestPlugin("test") {
            private boolean reloaded = false;

            @Override
            public RangerServiceDef getServiceDef() {
                return reloaded
                        ? serviceDefWith("global", RangerDorisResource.KEY_USER)
                        : serviceDefWith("global");
            }

            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                // The reload lands here, while the global check is being evaluated.
                reloaded = true;
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                result.setIsAllowed(request.getResource().getValue(RangerDorisResource.KEY_USER) == null);
                return result;
            }
        };
        RangerDorisAccessController ac = new RangerDorisAccessController(plugin);

        Assertions.assertFalse(ac.checkImpersonatePriv(
                UserIdentity.createAnalyzedUserIdentWithIp("gateway", "%"), "sre-oncall"));
    }

    @Test
    public void testImpersonateIsAskedOfRangerUnderItsOwnAccessType() {
        Assertions.assertEquals(DorisAccessType.IMPERSONATE,
                DorisAccessType.toAccessType(Privilege.IMPERSONATE_PRIV));

        // Ranger reads an access type its service definition does not declare as no match, so the
        // check has to ask for IMPERSONATE by that name: an engine that asked for ADMIN instead would
        // silently grant impersonation to every administrator, and one that asked for anything else
        // would deny it to everyone.
        Set<String> askedActions = Sets.newHashSet();
        RangerTest.DorisTestPlugin plugin = new RangerTest.DorisTestPlugin("test") {
            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                askedActions.add(request.getAccessType());
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                result.setIsAllowed("IMPERSONATE".equals(request.getAccessType()));
                return result;
            }
        };
        RangerDorisAccessController ac = new RangerDorisAccessController(plugin);
        UserIdentity ui = UserIdentity.createAnalyzedUserIdentWithIp("user1", "%");

        Assertions.assertTrue(ac.checkGlobalPriv(ui, PrivPredicate.IMPERSONATE));
        Assertions.assertTrue(askedActions.contains(DorisAccessType.IMPERSONATE.name()), askedActions.toString());

        RangerDorisAccessController denying = new RangerDorisAccessController(new RangerTest.DorisTestPlugin("test") {
            @Override
            public RangerAccessResult isAccessAllowed(RangerAccessRequest request) {
                RangerAccessResult result = new RangerAccessResult(1, "test", null, request);
                result.setIsAllowed(false);
                return result;
            }
        });
        Assertions.assertFalse(denying.checkGlobalPriv(ui, PrivPredicate.IMPERSONATE));
    }
}
