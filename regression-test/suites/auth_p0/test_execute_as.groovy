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

suite("test_execute_as", "p0,auth") {
    def dbName = context.config.getDbNameByFile(context.file)
    def tableName = "t_execute_as"
    def gateway = "test_execute_as_gateway"
    def alice = "test_execute_as_alice"
    def bob = "test_execute_as_bob"
    def nobody = "test_execute_as_nobody"
    def pwd = "C123_567p"
    def tokens = context.config.jdbcUrl.split('/')
    def url = tokens[0] + "//" + tokens[2] + "/" + dbName + "?"

    sql """DROP TABLE IF EXISTS ${tableName}"""
    sql """CREATE TABLE ${tableName} (id INT) DISTRIBUTED BY HASH(id) PROPERTIES('replication_num'='1')"""
    sql """INSERT INTO ${tableName} VALUES (1)"""

    [gateway, alice, bob, nobody].each { u ->
        try_sql("DROP USER '${u}'@'%'")
        sql """CREATE USER '${u}'@'%' IDENTIFIED BY '${pwd}'"""
    }
    sql """GRANT IMPERSONATE_PRIV ON *.*.* TO '${gateway}'@'%'"""
    sql """GRANT SELECT_PRIV ON internal.${dbName}.${tableName} TO '${alice}'@'%'"""

    // The privilege is global-only: a Doris privilege pairs a permission with a resource, and an
    // account is not one, so the same grant on a table is refused rather than silently narrowed.
    test {
        sql """GRANT IMPERSONATE_PRIV ON internal.${dbName}.${tableName} TO '${bob}'@'%'"""
        exception "IMPERSONATE_PRIV"
    }

    // An account that never adopted anyone keeps exactly its own privileges.
    connect(gateway, pwd, url) {
        test {
            sql """SELECT * FROM ${tableName}"""
            exception "denied"
        }
    }

    // An account without the privilege cannot adopt one.
    connect(nobody, pwd, url) {
        test {
            sql """EXECUTE AS ${alice} WITH NO REVERT"""
            exception "Impersonate_priv"
        }
    }

    // An account that cannot log in is not adoptable, and neither is a built-in one.
    connect(gateway, pwd, url) {
        test {
            sql """EXECUTE AS test_execute_as_no_such_account WITH NO REVERT"""
            exception "no Doris account"
        }
        test {
            sql """EXECUTE AS root WITH NO REVERT"""
            exception "built-in account"
        }
    }

    // The switch: the session's authorization becomes the adopted account's, and stays there.
    connect(gateway, pwd, url) {
        sql """EXECUTE AS '${alice}' WITH NO REVERT"""
        def currentUser = sql """SELECT current_user()"""
        assertTrue(currentUser.toString().contains(alice), currentUser.toString())
        sql """SELECT * FROM ${tableName}"""
        sql """SELECT * FROM ${tableName} WHERE id = 1"""
    }

    // Adopting an account with no privileges of its own leaves the session with none: the switch
    // transfers the adopted account's authority, it does not inherit the impersonator's.
    connect(gateway, pwd, url) {
        sql """EXECUTE AS ${bob} WITH NO REVERT"""
        test {
            sql """SELECT * FROM ${tableName}"""
            exception "denied"
        }
    }

    // One account per session, and the account's own management stays out of a borrowed session.
    connect(gateway, pwd, url) {
        sql """EXECUTE AS ${alice} WITH NO REVERT"""
        sql """SELECT * FROM ${tableName}"""
        test {
            sql """EXECUTE AS ${bob} WITH NO REVERT"""
            exception "twice"
        }
        test {
            sql """SET PASSWORD FOR '${alice}'@'%' = 'new_password'"""
            exception "EXECUTE AS"
        }
        test {
            sql """DROP USER '${bob}'@'%'"""
            exception "EXECUTE AS"
        }
        test {
            sql """GRANT SELECT_PRIV ON internal.${dbName}.${tableName} TO '${gateway}'@'%'"""
            exception "EXECUTE AS"
        }
        test {
            sql """CREATE ROLE exec_as_role"""
            exception "EXECUTE AS"
        }
        test {
            sql """DROP ROLE MAPPING IF EXISTS exec_as_map"""
            exception "EXECUTE AS"
        }
        test {
            sql """DROP ROW POLICY IF EXISTS exec_as_policy ON ${dbName}.${tableName}"""
            exception "EXECUTE AS"
        }
        test {
            sql """CREATE ROW POLICY exec_as_added ON ${dbName}.${tableName} AS PERMISSIVE TO '${alice}'@'%' USING (id = 1)"""
            exception "EXECUTE AS"
        }
        test {
            sql """SET LDAP_ADMIN_PASSWORD = 'secret'"""
            exception "EXECUTE AS"
        }
    }

    // Revoking the privilege does not disturb a session that already adopted an account, and stops
    // the next one: that is what WITH NO REVERT means, and why the privilege is revocable at all.
    connect(gateway, pwd, url) {
        sql """EXECUTE AS '${alice}' WITH NO REVERT"""
        // The revoke is issued from a connection of its own, so the session that holds the adopted
        // account stays open across it.
        connect(context.config.jdbcUser, context.config.jdbcPassword, url) {
            sql """REVOKE IMPERSONATE_PRIV ON *.*.* FROM '${gateway}'@'%'"""
        }
        def currentUser = sql """SELECT current_user()"""
        assertTrue(currentUser.toString().contains(alice), currentUser.toString())
        test {
            sql """SET PASSWORD FOR '${alice}'@'%' = 'new_password'"""
            exception "EXECUTE AS"
        }
    }
    connect(gateway, pwd, url) {
        test {
            sql """EXECUTE AS ${alice} WITH NO REVERT"""
            exception "Impersonate_priv"
        }
    }
    // The rest of the suite, and any rerun of it, expects the grant to be there.
    sql """GRANT IMPERSONATE_PRIV ON *.*.* TO '${gateway}'@'%'"""

    [gateway, alice, bob, nobody].each { u ->
        try_sql("DROP USER '${u}'@'%'")
    }
    sql """DROP TABLE IF EXISTS ${tableName}"""
}
