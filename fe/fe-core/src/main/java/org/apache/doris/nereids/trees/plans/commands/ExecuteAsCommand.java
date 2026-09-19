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

import org.apache.doris.analysis.StmtType;
import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.mysql.privilege.Privilege;
import org.apache.doris.nereids.trees.plans.PlanType;
import org.apache.doris.nereids.trees.plans.visitor.PlanVisitor;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.StmtExecutor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Objects;

/**
 * EXECUTE AS &lt;user&gt; WITH NO REVERT.
 *
 * <p>Adopts an existing Doris account for the rest of the session: privilege checks, masks, row filters
 * and the audit log follow the adopted account. What authorizes the switch is IMPERSONATE_PRIV (or
 * ADMIN_PRIV) of the connecting account, never the adopted account's password — one service account
 * holds the connection while each end user's own authorization applies.
 *
 * <p>The target is the account a login of that name from this client host would reach, and there is no
 * way back before the session ends, so the statement is refused rather than half-applied: on a session
 * that already adopted an account (or changed user), one running a procedure's statements, one with an
 * open transaction, and one holding a prepared plan.
 */
public class ExecuteAsCommand extends Command implements NoForward {
    private static final Logger LOG = LogManager.getLogger(ExecuteAsCommand.class);

    private final String targetUser;

    public ExecuteAsCommand(String targetUser) {
        super(PlanType.EXECUTE_AS_COMMAND);
        this.targetUser = Objects.requireNonNull(targetUser, "targetUser is null");
    }

    @Override
    public void run(ConnectContext ctx, StmtExecutor executor) throws Exception {
        UserIdentity authenticatedUser = ctx.getAuthenticatedUserIdentity();
        if (authenticatedUser == null) {
            throw new AnalysisException("EXECUTE AS is not allowed on a connection that did not authenticate");
        }
        if (ctx.isImpersonated()) {
            throw new AnalysisException(String.format(
                    "EXECUTE AS is not allowed twice: this session authenticated as '%s' and already acts as '%s'",
                    authenticatedUser.getQualifiedUser(), ctx.getQualifiedUser()));
        }
        if (ctx.getTxnEntry() != null) {
            throw new AnalysisException("EXECUTE AS is not allowed in an open transaction");
        }
        if (ctx.hasPreparedStatements()) {
            throw new AnalysisException("EXECUTE AS is not allowed once this session has prepared statements,"
                    + " because a prepared plan is authorization-decided for the account that prepared it");
        }
        if (ctx.isRunProcedure()) {
            throw new AnalysisException("EXECUTE AS is not allowed in a procedure, whose statements run on a"
                    + " context of their own that ends with the procedure");
        }
        // The check comes first and is asked about the account the client named, so a caller without the right
        // is told the same thing whether or not that account exists, and is told it without the account being
        // looked up at all.
        if (!Env.getCurrentEnv().getAccessManager().checkImpersonatePriv(authenticatedUser, targetUser)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_SPECIFIC_ACCESS_DENIED_ERROR,
                    Privilege.IMPERSONATE_PRIV);
        }
        List<UserIdentity> matched = Env.getCurrentEnv().getAuth()
                .getUserIdentityForImpersonation(targetUser, ctx.getRemoteIP());
        UserIdentity target = matched.isEmpty() ? null : matched.get(0);
        if (target == null) {
            throw new AnalysisException(String.format("There is no Doris account '%s' this client can log in as",
                    targetUser));
        }
        if (target.isRootUser() || target.isAdminUser()) {
            throw new AnalysisException(String.format("EXECUTE AS '%s' is not allowed: '%s' is a built-in account",
                    targetUser, targetUser));
        }

        LOG.info("session of user '{}' adopts account '{}' with EXECUTE AS",
                authenticatedUser.getQualifiedUser(), target);
        ctx.switchToImpersonatedUserIdentity(target);
    }

    @Override
    public <R, C> R accept(PlanVisitor<R, C> visitor, C context) {
        return visitor.visitExecuteAsCommand(this, context);
    }

    @Override
    public StmtType stmtType() {
        return StmtType.EXECUTE;
    }

    public String getTargetUser() {
        return targetUser;
    }
}
