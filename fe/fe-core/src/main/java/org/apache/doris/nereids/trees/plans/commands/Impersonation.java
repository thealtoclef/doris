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

package org.apache.doris.nereids.trees.plans.commands;

import org.apache.doris.cluster.ClusterNamespace;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

/**
 * EXECUTE AS: which statements a session that adopted another account may not run, what a refusal says,
 * and what an audit row records about it.
 *
 * <p>Impersonation lends an identity for data access; it does not hand over the account. Listed below are
 * the statements that could change or extend what the borrowed identity — or the borrower, once the
 * session ends — may do: accounts and credentials, grants and revokes, role lifecycle, role mappings,
 * authentication integrations, and row filters. Statements that only restrict, and data work under the
 * adopted identity, stay available. The list lives here rather than on each statement so that a rebase
 * touches one file instead of twenty.
 */
public final class Impersonation {
    private static final Set<Class<? extends Command>> IDENTITY_MANAGEMENT = ImmutableSet.of(
            AlterAuthenticationIntegrationCommand.class,
            AlterRoleCommand.class,
            AlterUserCommand.class,
            CreateAuthenticationIntegrationCommand.class,
            CreateRoleCommand.class,
            CreateRoleMappingCommand.class,
            CreateUserCommand.class,
            DropAuthenticationIntegrationCommand.class,
            DropRoleCommand.class,
            DropRoleMappingCommand.class,
            DropRowPolicyCommand.class,
            DropUserCommand.class,
            GrantResourcePrivilegeCommand.class,
            GrantRoleCommand.class,
            GrantTablePrivilegeCommand.class,
            RevokeResourcePrivilegeCommand.class,
            RevokeRoleCommand.class,
            RevokeTablePrivilegeCommand.class,
            SetUserPropertiesCommand.class);

    private Impersonation() {
    }

    /**
     * True for a statement a session acting as an account it adopted with EXECUTE AS must not run.
     * {@code StmtExecutor} asks this before the statement can be prepared or forwarded, since a forwarded
     * one reaches the master carrying the adopted account as its only identity.
     */
    public static boolean isIdentityManagement(LogicalPlan plan) {
        // SET and CREATE POLICY carry several cases in one statement, so they answer for themselves.
        if (plan instanceof SetOptionsCommand) {
            return ((SetOptionsCommand) plan).isCredentialAssignment();
        }
        if (plan instanceof CreatePolicyCommand) {
            return ((CreatePolicyCommand) plan).isRowPolicy();
        }
        return IDENTITY_MANAGEMENT.contains(plan.getClass());
    }

    /** Refuse a statement while the session acts as an account it adopted. */
    public static void checkNotImpersonated(ConnectContext ctx) throws AnalysisException {
        if (ctx.isImpersonated()) {
            throw new AnalysisException(String.format(
                    "This statement is not allowed in a session that has run EXECUTE AS: this session"
                            + " authenticated as '%s' and now acts as '%s'",
                    ctx.getAuthenticatedUserIdentity().getQualifiedUser(), ctx.getQualifiedUser()));
        }
    }

    /**
     * The account the session authenticated as when it has since adopted another one with EXECUTE AS,
     * and empty for an ordinary session: what makes an impersonated act attributable.
     */
    public static String auditActor(ConnectContext ctx) {
        return ctx.isImpersonated()
                ? ClusterNamespace.getNameFromFullName(ctx.getAuthenticatedUserIdentity().getQualifiedUser())
                : "";
    }
}
