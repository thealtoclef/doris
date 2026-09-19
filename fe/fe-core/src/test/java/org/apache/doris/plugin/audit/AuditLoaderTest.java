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

package org.apache.doris.plugin.audit;

import org.apache.doris.catalog.InternalSchema;
import org.apache.doris.common.jmockit.Deencapsulation;
import org.apache.doris.plugin.AuditEvent;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AuditLoaderTest {

    @Test
    public void testAssembleAuditIsSerializedWithLoadLock() throws Exception {
        AuditLoader auditLoader = new AuditLoader();
        AuditEvent auditEvent = new AuditEvent.AuditEventBuilder()
                .setQueryId("query-in-shared-monitor-test")
                .setTimestamp(1L)
                .setStmt("select 1")
                .build();

        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread assembleThread = new Thread(() -> {
            started.countDown();
            try {
                Deencapsulation.invoke(auditLoader, "assembleAudit", auditEvent);
            } catch (Throwable t) {
                error.set(t);
            }
        });

        synchronized (auditLoader) {
            assembleThread.start();
            Assert.assertTrue(started.await(5, TimeUnit.SECONDS));
            Assert.assertTrue(waitForBlocked(assembleThread));
            Assert.assertFalse(getAuditLogBuffer(auditLoader).contains(auditEvent.queryId));
        }

        assembleThread.join(5000);
        Assert.assertFalse(assembleThread.isAlive());
        if (error.get() != null) {
            throw new AssertionError("failed to assemble audit event", error.get());
        }
        Assert.assertTrue(getAuditLogBuffer(auditLoader).contains(auditEvent.queryId));
    }

    @Test
    public void testTheImpersonatedColumnIsWhereTheAuditTableExpectsIt() throws Exception {
        AuditLoader auditLoader = new AuditLoader();
        AuditEvent auditEvent = new AuditEvent.AuditEventBuilder()
                .setQueryId("query-of-an-impersonated-session")
                .setClientIp("127.0.0.1")
                .setUser("alice")
                .setImpersonatedBy("superset_gateway")
                .setStmt("select 1")
                .build();

        Deencapsulation.invoke(auditLoader, "assembleAudit", auditEvent);

        // The internal audit table is created from InternalSchema.AUDIT_SCHEMA and the loader writes
        // the same fields positionally, so a column known to one and not the other loses every audit
        // row rather than one column of it.
        String buffer = getAuditLogBuffer(auditLoader);
        String row = buffer.substring(0, buffer.length() - 1);
        String[] columns = row.split(String.valueOf(AuditLoader.AUDIT_TABLE_COL_SEPARATOR), -1);
        Assert.assertEquals(InternalSchema.AUDIT_SCHEMA.size(), columns.length);

        int userIndex = -1;
        for (int i = 0; i < InternalSchema.AUDIT_SCHEMA.size(); i++) {
            if ("user".equals(InternalSchema.AUDIT_SCHEMA.get(i).getName())) {
                userIndex = i;
            }
        }
        Assert.assertTrue("the audit schema declares no user column", userIndex >= 0);
        Assert.assertEquals("impersonated_by", InternalSchema.AUDIT_SCHEMA.get(userIndex + 1).getName());
        Assert.assertEquals("alice", columns[userIndex]);
        Assert.assertEquals("superset_gateway", columns[userIndex + 1]);
    }

    private boolean waitForBlocked(Thread thread) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private String getAuditLogBuffer(AuditLoader auditLoader) {
        StringBuilder buffer = Deencapsulation.getField(auditLoader, "auditLogBuffer");
        return buffer.toString();
    }
}
