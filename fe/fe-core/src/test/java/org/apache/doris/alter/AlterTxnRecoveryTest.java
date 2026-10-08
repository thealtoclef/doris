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

package org.apache.doris.alter;

import org.apache.doris.catalog.Env;
import org.apache.doris.common.Config;
import org.apache.doris.common.UserException;
import org.apache.doris.system.SystemInfoService;
import org.apache.doris.transaction.GlobalTransactionMgr;
import org.apache.doris.transaction.GlobalTransactionMgrIface;
import org.apache.doris.transaction.TransactionState;
import org.apache.doris.transaction.TransactionState.LoadJobSourceType;
import org.apache.doris.transaction.TransactionState.TxnCoordinator;
import org.apache.doris.transaction.TransactionState.TxnSourceType;
import org.apache.doris.transaction.TransactionStatus;

import com.google.common.collect.Lists;
import mockit.Mock;
import mockit.MockUp;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

public class AlterTxnRecoveryTest {
    private GlobalTransactionMgrIface transactionMgr;
    private boolean previousAbortConfig;

    @Before
    public void setUp() {
        previousAbortConfig = Config.enable_abort_txn_by_checking_conflict_txn;
        Config.enable_abort_txn_by_checking_conflict_txn = true;
        transactionMgr = Mockito.mock(GlobalTransactionMgrIface.class);
        SystemInfoService systemInfo = Mockito.mock(SystemInfoService.class);
        new MockUp<Env>() {
            @Mock
            public GlobalTransactionMgrIface getCurrentGlobalTransactionMgr() {
                return transactionMgr;
            }

            @Mock
            public SystemInfoService getCurrentSystemInfo() {
                return systemInfo;
            }
        };
    }

    @After
    public void tearDown() {
        Config.enable_abort_txn_by_checking_conflict_txn = previousAbortConfig;
    }

    @Test
    public void testCommittedAndFinalTransactionsAreNotAborted() {
        TransactionState prepared = transaction(1, TransactionStatus.PREPARE);
        TransactionState precommitted = transaction(2, TransactionStatus.PRECOMMITTED);
        List<TransactionState> conflicts = Lists.newArrayList(prepared, precommitted,
                transaction(3, TransactionStatus.COMMITTED), transaction(4, TransactionStatus.VISIBLE),
                transaction(5, TransactionStatus.ABORTED));

        // Every coordinator is missing. Only transactions that can still be aborted are selected.
        Assert.assertEquals(Lists.newArrayList(prepared, precommitted),
                GlobalTransactionMgr.checkFailedTxns(conflicts));
    }

    @Test
    public void testAlterJobsWaitForCommittedConflictWithoutAborting() throws Exception {
        Mockito.when(transactionMgr.getUnFinishedPreviousLoad(20L, 1L, Lists.newArrayList(2L)))
                .thenReturn(Lists.newArrayList(transaction(10L, TransactionStatus.COMMITTED)));
        SchemaChangeJobV2 schemaChange = new SchemaChangeJobV2();
        prepareJob(schemaChange);
        RollupJobV2 rollup = new RollupJobV2();
        prepareJob(rollup);

        schemaChange.runWaitingTxnJob();
        rollup.runWaitingTxnJob();

        Assert.assertEquals(AlterJobV2.JobState.WAITING_TXN, schemaChange.getJobState());
        Assert.assertEquals(AlterJobV2.JobState.WAITING_TXN, rollup.getJobState());
        Mockito.verify(transactionMgr, Mockito.never())
                .abortTransaction(1L, 10L, "Cancel by schema change");
    }

    @Test
    public void testSchemaChangeRetriesAfterConflictAbortFailure() throws Exception {
        SchemaChangeJobV2 job = new SchemaChangeJobV2();
        prepareJob(job);
        mockAbortFailure();

        // An already-aborted conflict must not cancel the job or dispatch alter tasks.
        job.runWaitingTxnJob();
        Assert.assertEquals(AlterJobV2.JobState.WAITING_TXN, job.getJobState());
        Assert.assertEquals(0, job.schemaChangeBatchTask.getTaskNum());
        Mockito.verify(transactionMgr).abortTransaction(1L, 10L, "Cancel by schema change");

        clearConflicts();
        Assert.assertTrue(job.checkFailedPreviousLoadAndAbort());
        Mockito.verify(transactionMgr, Mockito.times(2))
                .getUnFinishedPreviousLoad(20L, 1L, Lists.newArrayList(2L));
    }

    @Test
    public void testRollupRetriesAfterConflictAbortFailure() throws Exception {
        RollupJobV2 job = new RollupJobV2();
        prepareJob(job);
        mockAbortFailure();

        job.runWaitingTxnJob();
        Assert.assertEquals(AlterJobV2.JobState.WAITING_TXN, job.getJobState());
        Mockito.verify(transactionMgr).abortTransaction(1L, 10L, "Cancel by schema change");

        clearConflicts();
        Assert.assertTrue(job.checkFailedPreviousLoadAndAbort());
        Mockito.verify(transactionMgr, Mockito.times(2))
                .getUnFinishedPreviousLoad(20L, 1L, Lists.newArrayList(2L));
    }

    private void prepareJob(AlterJobV2 job) {
        job.dbId = 1L;
        job.tableId = 2L;
        job.watershedTxnId = 20L;
        job.setJobState(AlterJobV2.JobState.WAITING_TXN);
    }

    private void mockAbortFailure() throws UserException {
        Mockito.when(transactionMgr.getUnFinishedPreviousLoad(20L, 1L, Lists.newArrayList(2L)))
                .thenReturn(Lists.newArrayList(transaction(10L, TransactionStatus.PREPARE)));
        Mockito.doThrow(new UserException("transaction is already aborted"))
                .when(transactionMgr).abortTransaction(1L, 10L, "Cancel by schema change");
    }

    private void clearConflicts() throws UserException {
        Mockito.when(transactionMgr.getUnFinishedPreviousLoad(20L, 1L, Lists.newArrayList(2L)))
                .thenReturn(Collections.emptyList());
    }

    private TransactionState transaction(long id, TransactionStatus status) {
        TransactionState txn = new TransactionState(1L, Lists.newArrayList(2L), id,
                "conflict_" + id, null, LoadJobSourceType.BACKEND_STREAMING,
                new TxnCoordinator(TxnSourceType.BE, 999L, "old-pod-ip", 1L), -1L, 1000L);
        txn.setTransactionStatus(status);
        return txn;
    }
}
