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

import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.mysql.privilege.PrivBitSet;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.mysql.privilege.Privilege;
import org.apache.doris.nereids.parser.NereidsParser;
import org.apache.doris.nereids.trees.plans.logical.LogicalPlan;
import org.apache.doris.persist.gson.GsonUtils;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.PreparedStatementContext;
import org.apache.doris.qe.QueryState;
import org.apache.doris.qe.StmtExecutor;
import org.apache.doris.transaction.TransactionEntry;
import org.apache.doris.utframe.TestWithFeService;

import com.google.common.collect.Lists;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * The behaviour of EXECUTE AS, exercised through the statements a client writes: what authorizes the
 * switch, which account becomes effective, and which statements a borrowed identity may not run.
 */
public class ExecuteAsCommandTest extends TestWithFeService {
    private static final String CLIENT_IP = "127.0.0.1";
    // The one account allowed to impersonate; its own privileges are deliberately nil, so that every
    // positive assertion below can only come from the adopted account.
    private static final String GATEWAY = "exec_as_gateway";

    private final NereidsParser parser = new NereidsParser();

    @Override
    protected void runBeforeAll() throws Exception {
        createDatabase("test");
        addUser(GATEWAY, false);
        addUser("exec_as_unprivileged", false);
        grantPriv("GRANT IMPERSONATE_PRIV ON *.*.* TO '" + GATEWAY + "'@'%'");
        // Created once: addUser() refuses a name that already exists, and the refusal tests adopt it.
        addUser("exec_as_acted", false);
    }

    private ExecuteAsCommand parseExecuteAs(String user) {
        LogicalPlan plan = parser.parseSingle("EXECUTE AS " + user + " WITH NO REVERT");
        Assertions.assertInstanceOf(ExecuteAsCommand.class, plan);
        return (ExecuteAsCommand) plan;
    }

    /**
     * A session shaped the way the server builds one: the identity is the account a login matched, so its
     * host is that account's pattern, and the client address stays for the account lookup.
     */
    private ConnectContext loginAs(String user) {
        return loginAs(user, CLIENT_IP);
    }

    private ConnectContext loginAs(String user, String remoteIp) {
        UserIdentity identity = UserIdentity.createAnalyzedUserIdentWithIp(user, "%");
        ConnectContext ctx = new ConnectContext();
        ctx.setEnv(Env.getCurrentEnv());
        ctx.setCurrentUserIdentity(identity);
        ctx.setAuthenticatedUserIdentity(identity);
        ctx.setRemoteIP(remoteIp);
        ctx.setThreadLocalInfo();
        return ctx;
    }

    /** The harness's own session is thread-local state: a test that installed another one puts it back. */
    private void restoreHarnessSession() {
        connectContext.setThreadLocalInfo();
    }

    private boolean canSelectTestDb(ConnectContext ctx) {
        return Env.getCurrentEnv().getAccessManager()
                .checkDbPriv(ctx, InternalCatalog.INTERNAL_CATALOG_NAME, "test", PrivPredicate.SELECT);
    }

    @Test
    public void testTheAdoptedAccountBringsItsOwnPrivileges() throws Exception {
        addUser("exec_as_alice", false);
        grantPriv("GRANT SELECT_PRIV ON internal.test.* TO 'exec_as_alice'@'%'");
        addUser("exec_as_bob", false);

        ConnectContext ctx = loginAs(GATEWAY);
        try {
            Assertions.assertFalse(canSelectTestDb(ctx));

            parseExecuteAs("exec_as_alice").run(ctx, null);
            Assertions.assertTrue(ctx.isImpersonated());
            Assertions.assertEquals("exec_as_alice", ctx.getQualifiedUser());
            Assertions.assertTrue(canSelectTestDb(ctx));

            // Attributed to the adopted account, not to the one that authenticated.
            Assertions.assertEquals(GATEWAY, ctx.getAuthenticatedUserIdentity().getQualifiedUser());
        } finally {
            restoreHarnessSession();
        }

        // Adopting an account transfers that account's authority; it does not inherit the impersonator's.
        ConnectContext bobSession = loginAs(GATEWAY);
        try {
            parseExecuteAs("exec_as_bob").run(bobSession, null);
            Assertions.assertEquals("exec_as_bob", bobSession.getQualifiedUser());
            Assertions.assertFalse(canSelectTestDb(bobSession));
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testASessionWithoutThePrivilegeCannotAdoptAnotherAccount() throws Exception {
        addUser("exec_as_nobody", false);
        addUser("exec_as_target", false);

        ConnectContext ctx = loginAs("exec_as_nobody");
        try {
            AnalysisException e = Assertions.assertThrows(AnalysisException.class,
                    () -> parseExecuteAs("exec_as_target").run(ctx, null));
            Assertions.assertTrue(e.getMessage().contains("Impersonate_priv"), e.getMessage());
            Assertions.assertFalse(ctx.isImpersonated());
            Assertions.assertEquals("exec_as_nobody", ctx.getQualifiedUser());
        } finally {
            restoreHarnessSession();
        }
    }

    /** A caller without the right is refused the same way whether or not the account exists. */
    @Test
    public void testARefusalDoesNotSayWhetherTheAccountExists() throws Exception {
        ConnectContext ctx = loginAs("exec_as_unprivileged");
        try {
            AnalysisException e = Assertions.assertThrows(AnalysisException.class,
                    () -> parseExecuteAs("exec_as_no_such_account").run(ctx, null));
            Assertions.assertTrue(e.getMessage().contains("Impersonate_priv"), e.getMessage());
            Assertions.assertFalse(e.getMessage().contains("no_such_account"), e.getMessage());
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testAnAccountThatCannotLogInIsNotAdoptable() throws Exception {
        addUser("exec_as_ghostless", false);
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            AnalysisException e = Assertions.assertThrows(AnalysisException.class,
                    () -> parseExecuteAs("exec_as_no_such_account").run(ctx, null));
            Assertions.assertTrue(e.getMessage().contains("no_such_account"), e.getMessage());
            Assertions.assertFalse(ctx.isImpersonated());
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testABuiltInAccountIsNotAdoptable() throws Exception {
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("root").run(ctx, null));
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("'admin'").run(ctx, null));
            Assertions.assertFalse(ctx.isImpersonated());
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testASessionAdoptsOneAccountOnly() throws Exception {
        addUser("exec_as_first", false);
        addUser("exec_as_second", false);

        ConnectContext ctx = loginAs(GATEWAY);
        try {
            parseExecuteAs("exec_as_first").run(ctx, null);
            AnalysisException e = Assertions.assertThrows(AnalysisException.class,
                    () -> parseExecuteAs("exec_as_second").run(ctx, null));
            Assertions.assertTrue(e.getMessage().contains("twice"), e.getMessage());
            Assertions.assertEquals("exec_as_first", ctx.getQualifiedUser());
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testASessionThatDidNotAuthenticateCannotAdoptAnAccount() throws Exception {
        ConnectContext internal = new ConnectContext();
        internal.setEnv(Env.getCurrentEnv());
        internal.setCurrentUserIdentity(UserIdentity.createAnalyzedUserIdentWithIp("exec_as_alice", CLIENT_IP));
        try {
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("exec_as_alice").run(
                    internal, null));
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testAnOpenTransactionRefusesTheSwitch() throws Exception {
        addUser("exec_as_in_txn", false);
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            ctx.setTxnEntry(new TransactionEntry());
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("exec_as_in_txn").run(ctx, null));
            Assertions.assertFalse(ctx.isImpersonated());
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testAPreparedPlanRefusesTheSwitch() throws Exception {
        addUser("exec_as_prepared", false);
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            ctx.addPreparedStatementContext("stmt1",
                    new PreparedStatementContext(null, ctx, null, "select 1"));
            Assertions.assertTrue(ctx.hasPreparedStatements());
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("exec_as_prepared").run(ctx, null));
            Assertions.assertFalse(ctx.isImpersonated());
        } finally {
            restoreHarnessSession();
        }
    }

    /** A procedure's statements run on a context of its own, which ends with the procedure. */
    @Test
    public void testAProcedureRefusesTheSwitch() throws Exception {
        addUser("exec_as_in_proc", false);
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            ctx.setRunProcedure(true);
            Assertions.assertThrows(AnalysisException.class, () -> parseExecuteAs("exec_as_in_proc").run(ctx, null));
            Assertions.assertFalse(ctx.isImpersonated());
        } finally {
            restoreHarnessSession();
        }
    }

    /**
     * A domain-backed account is adopted as the domain account a login through it continues as, not as
     * the per-address entry the domain resolver created for that address.
     */
    @Test
    public void testADomainAccountIsAdoptedAsItsDomainAccount() throws Exception {
        String address = "10.9.9.9";
        UserIdentity domainIdentity = UserIdentity.createAnalyzedUserIdentWithDomain(
                "exec_as_domain", "['corp.example.com']");
        Env.getCurrentEnv().getAuth().getUserManager().createUser(
                UserIdentity.createAnalyzedUserIdentWithIp("exec_as_domain", address), new byte[0],
                domainIdentity, true, "");

        ConnectContext ctx = loginAs(GATEWAY, address);
        try {
            parseExecuteAs("exec_as_domain").run(ctx, null);
            Assertions.assertTrue(ctx.isImpersonated());
            Assertions.assertTrue(ctx.getCurrentUserIdentity().isDomain());
            Assertions.assertEquals(domainIdentity, ctx.getCurrentUserIdentity());
        } finally {
            restoreHarnessSession();
        }
    }

    /**
     * Statements a borrowed identity must not run: they change the account it borrowed, hand it
     * privileges, reshape the roles, mappings and integrations that carry them, or remove a row filter.
     */
    private List<String> authorizationManagementStatements() {
        return Lists.newArrayList(
                "SET PASSWORD FOR 'exec_as_acted'@'%' = 'secret'",
                "ALTER USER 'exec_as_acted'@'%' IDENTIFIED BY 'secret'",
                "DROP USER 'exec_as_acted'@'%'",
                "CREATE USER exec_as_created@'%' IDENTIFIED BY 'secret'",
                "GRANT SELECT_PRIV ON internal.test.* TO '" + GATEWAY + "'@'%'",
                "REVOKE SELECT_PRIV ON internal.test.* FROM 'exec_as_acted'@'%'",
                "SET PROPERTY FOR 'exec_as_acted' 'max_user_connections' = '9999'",
                "CREATE ROLE exec_as_role",
                "ALTER ROLE exec_as_role COMMENT 'note'",
                "DROP ROLE exec_as_role",
                "CREATE ROLE MAPPING exec_as_map ON AUTHENTICATION INTEGRATION exec_as_idp"
                        + " RULE (USING CEL 'true' GRANT ROLE exec_as_acted)",
                "DROP ROLE MAPPING exec_as_map",
                "CREATE AUTHENTICATION INTEGRATION exec_as_idp PROPERTIES ('type' = 'ldap')",
                "DROP AUTHENTICATION INTEGRATION exec_as_idp",
                "CREATE ROW POLICY exec_as_added ON test.exec_as_t AS PERMISSIVE"
                        + " TO 'exec_as_acted'@'%' USING (id = 1)",
                "DROP ROW POLICY IF EXISTS exec_as_policy ON test.exec_as_t",
                "SET LDAP_ADMIN_PASSWORD = 'secret'");
    }

    @Test
    public void testAccountAndGrantManagementIsClosedToAnImpersonatedSession() throws Exception {
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            parseExecuteAs("exec_as_acted").run(ctx, null);

            for (String sql : authorizationManagementStatements()) {
                assertRefused(ctx, sql);
            }
        } finally {
            restoreHarnessSession();
        }
    }

    /**
     * The refusal comes before the statement can be handed to the master, which is given the adopted
     * account as the only identity in the request: this frontend is told it is not the master, the state
     * in which it forwards instead of running a statement itself.
     */
    @Test
    public void testARefusalComesBeforeAStatementCouldBeForwarded() throws Exception {
        ConnectContext ctx = loginAs(GATEWAY);
        new MockUp<Env>() {
            @Mock
            public boolean isMaster() {
                return false;
            }
        };
        Assertions.assertFalse(Env.getCurrentEnv().isMaster());
        try {
            parseExecuteAs("exec_as_acted").run(ctx, null);
            assertRefused(ctx, "CREATE USER exec_as_forwarded@'%' IDENTIFIED BY 'secret'");
        } finally {
            restoreHarnessSession();
        }
    }

    /**
     * Run a statement the way a client does and assert the impersonation refusal is what comes back, in
     * whichever shape the executor reports it.
     */
    private void assertRefused(ConnectContext ctx, String sql) throws Exception {
        ctx.getState().reset();
        StmtExecutor executor = new StmtExecutor(ctx, sql);
        ctx.setExecutor(executor);
        String message;
        try {
            executor.execute();
            Assertions.assertEquals(QueryState.MysqlStateType.ERR, ctx.getState().getStateType(), sql);
            message = ctx.getState().getErrorMessage();
        } catch (AnalysisException e) {
            message = e.getMessage();
        }
        Assertions.assertTrue(message.contains("EXECUTE AS"), sql + " -> " + message);
        Assertions.assertTrue(message.contains(GATEWAY), sql + " -> " + message);
    }

    /** The audit actor: the account behind the session, empty until it adopts one. */
    @Test
    public void testTheAuditActorNamesTheAccountBehindTheOneThatActed() throws Exception {
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            Assertions.assertEquals("", Impersonation.auditActor(ctx));

            parseExecuteAs("exec_as_acted").run(ctx, null);

            Assertions.assertEquals("exec_as_acted", ctx.getQualifiedUser());
            Assertions.assertEquals(GATEWAY, Impersonation.auditActor(ctx));
        } finally {
            restoreHarnessSession();
        }
    }

    /** Adopting an account closes the account management, not the work the adopted account does. */
    @Test
    public void testDataWorkAndSessionVariablesStayAvailable() throws Exception {
        ConnectContext ctx = loginAs(GATEWAY);
        try {
            parseExecuteAs("exec_as_acted").run(ctx, null);

            for (String sql : Lists.newArrayList("SET @v = 1", "SHOW GRANTS")) {
                ctx.getState().reset();
                StmtExecutor executor = new StmtExecutor(ctx, sql);
                ctx.setExecutor(executor);
                executor.execute();
                Assertions.assertNotEquals(QueryState.MysqlStateType.ERR, ctx.getState().getStateType(),
                        sql + " -> " + ctx.getState().getErrorMessage());
            }
        } finally {
            restoreHarnessSession();
        }
    }

    @Test
    public void testTheImpersonatePrivilegeIsGrantableOnlyGlobally() throws Exception {
        addUser("exec_as_privs", false);
        UserIdentity identity = UserIdentity.createAnalyzedUserIdentWithIp("exec_as_privs", "%");

        // A privilege is a (privilege, resource) pair and an account is not a resource.
        AnalysisException e = Assertions.assertThrows(AnalysisException.class,
                () -> grantPriv("GRANT IMPERSONATE_PRIV ON internal.test.some_table TO 'exec_as_privs'@'%'"));
        Assertions.assertTrue(e.getMessage().contains("IMPERSONATE_PRIV"), e.getMessage());

        // Nor on a resource or a workload group, which the resource-grant statement enforces itself.
        LogicalPlan onResource = parser.parseSingle(
                "GRANT IMPERSONATE_PRIV ON RESOURCE 'some_resource' TO 'exec_as_privs'@'%'");
        Assertions.assertInstanceOf(GrantResourcePrivilegeCommand.class, onResource);
        AnalysisException resourceDenied = Assertions.assertThrows(AnalysisException.class,
                () -> ((GrantResourcePrivilegeCommand) onResource).run(connectContext, null));
        Assertions.assertTrue(resourceDenied.getMessage().contains("Impersonate_priv"), resourceDenied.getMessage());

        grantPriv("GRANT IMPERSONATE_PRIV ON *.*.* TO 'exec_as_privs'@'%'");
        Assertions.assertTrue(Env.getCurrentEnv().getAccessManager()
                .checkGlobalPriv(identity, PrivPredicate.IMPERSONATE));

        // ...and an administrator holds it without a separate grant.
        Assertions.assertTrue(Env.getCurrentEnv().getAccessManager()
                .checkGlobalPriv(UserIdentity.createAnalyzedUserIdentWithIp("root", "%"), PrivPredicate.IMPERSONATE));

        // Visible where an operator looks for it.
        List<List<String>> authInfo = Env.getCurrentEnv().getAuth().getAuthInfo(identity);
        Assertions.assertTrue(authInfo.stream().flatMap(List::stream)
                .anyMatch(value -> value.contains("Impersonate_priv")), authInfo.toString());
    }

    @Test
    public void testTheNewPrivilegeSurvivesTheImageFormat() {
        PrivBitSet privs = PrivBitSet.of(Privilege.IMPERSONATE_PRIV);
        PrivBitSet restored = GsonUtils.GSON.fromJson(GsonUtils.GSON.toJson(privs), PrivBitSet.class);
        Assertions.assertTrue(restored.get(Privilege.IMPERSONATE_PRIV.getIdx()));
        Assertions.assertTrue(restored.satisfy(PrivPredicate.IMPERSONATE));
        Assertions.assertFalse(PrivBitSet.of().satisfy(PrivPredicate.IMPERSONATE));
    }
}
