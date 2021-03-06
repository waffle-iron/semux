/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.semux.core.Amount.Unit.NANO_SEM;
import static org.semux.core.Amount.Unit.SEM;
import static org.semux.core.Amount.ZERO;
import static org.semux.core.Amount.sub;
import static org.semux.core.Amount.sum;
import static org.semux.core.TransactionResult.Error.INSUFFICIENT_AVAILABLE;
import static org.semux.core.TransactionResult.Error.INSUFFICIENT_LOCKED;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.semux.Network;
import org.semux.config.Config;
import org.semux.config.Constants;
import org.semux.config.DevnetConfig;
import org.semux.core.state.AccountState;
import org.semux.core.state.DelegateState;
import org.semux.crypto.Key;
import org.semux.rules.TemporaryDatabaseRule;
import org.semux.util.Bytes;

public class TransactionExecutorTest {

    @Rule
    public TemporaryDatabaseRule temporaryDBFactory = new TemporaryDatabaseRule();

    private Config config;
    private Blockchain chain;
    private AccountState as;
    private DelegateState ds;
    private TransactionExecutor exec;
    private Network network;

    @Before
    public void prepare() {
        config = new DevnetConfig(Constants.DEFAULT_DATA_DIR);
        chain = new BlockchainImpl(config, temporaryDBFactory);
        as = chain.getAccountState();
        ds = chain.getDelegateState();
        exec = new TransactionExecutor(config);
        network = config.network();
    }

    private TransactionResult executeAndCommit(TransactionExecutor exec, Transaction tx, AccountState as,
            DelegateState ds) {
        TransactionResult res = exec.execute(tx, as, ds);
        as.commit();
        ds.commit();

        return res;
    }

    @Test
    public void testTransfer() {
        Key key = new Key();

        TransactionType type = TransactionType.TRANSFER;
        byte[] from = key.toAddress();
        byte[] to = Bytes.random(20);
        Amount value = NANO_SEM.of(5);
        Amount fee = config.minTransactionFee();
        long nonce = as.getAccount(from).getNonce();
        long timestamp = System.currentTimeMillis();
        byte[] data = Bytes.random(16);

        Transaction tx = new Transaction(network, type, to, value, fee, nonce, timestamp, data);
        tx.sign(key);
        assertTrue(tx.validate(network));

        // insufficient available
        TransactionResult result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());

        Amount available = SEM.of(1000);
        as.adjustAvailable(key.toAddress(), available);

        // execute but not commit
        result = exec.execute(tx, as.track(), ds.track());
        assertTrue(result.isSuccess());
        assertEquals(available, as.getAccount(key.toAddress()).getAvailable());
        assertEquals(ZERO, as.getAccount(to).getAvailable());

        // execute and commit
        result = executeAndCommit(exec, tx, as.track(), ds.track());
        assertTrue(result.isSuccess());
        assertEquals(sub(available, sum(value, fee)), as.getAccount(key.toAddress()).getAvailable());
        assertEquals(value, as.getAccount(to).getAvailable());
    }

    @Test
    public void testDelegate() {
        Key delegate = new Key();

        Amount available = SEM.of(2000);
        as.adjustAvailable(delegate.toAddress(), available);

        TransactionType type = TransactionType.DELEGATE;
        byte[] from = delegate.toAddress();
        byte[] to = Bytes.random(20);
        Amount value = config.minDelegateBurnAmount();
        Amount fee = config.minTransactionFee();
        long nonce = as.getAccount(from).getNonce();
        long timestamp = System.currentTimeMillis();
        byte[] data = Bytes.random(16);

        // register delegate (to != EMPTY_ADDRESS, random name)
        Transaction tx = new Transaction(network, type, to, value, fee, nonce, timestamp, data).sign(delegate);
        TransactionResult result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());

        // register delegate (to == EMPTY_ADDRESS, random name)
        tx = new Transaction(network, type, Bytes.EMPTY_ADDRESS, value, fee, nonce, timestamp, data).sign(delegate);
        result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());

        // register delegate (to == EMPTY_ADDRESS, normal name) and commit
        data = Bytes.of("test");
        tx = new Transaction(network, type, Bytes.EMPTY_ADDRESS, value, fee, nonce, timestamp, data).sign(delegate);
        result = executeAndCommit(exec, tx, as.track(), ds.track());
        assertTrue(result.isSuccess());
        assertEquals(sub(available, sum(config.minDelegateBurnAmount(), fee)),
                as.getAccount(delegate.toAddress()).getAvailable());
        assertArrayEquals(delegate.toAddress(), ds.getDelegateByName(data).getAddress());
        assertArrayEquals(data, ds.getDelegateByAddress(delegate.toAddress()).getName());
    }

    @Test
    public void testVote() {
        Key voter = new Key();
        Key delegate = new Key();

        Amount available = SEM.of(100);
        as.adjustAvailable(voter.toAddress(), available);

        TransactionType type = TransactionType.VOTE;
        byte[] from = voter.toAddress();
        byte[] to = delegate.toAddress();
        Amount value = SEM.of(33);
        Amount fee = config.minTransactionFee();
        long nonce = as.getAccount(from).getNonce();
        long timestamp = System.currentTimeMillis();
        byte[] data = {};

        // vote for non-existing delegate
        Transaction tx = new Transaction(network, type, to, value, fee, nonce, timestamp, data).sign(voter);
        TransactionResult result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());

        ds.register(delegate.toAddress(), Bytes.of("delegate"));

        // vote for delegate
        result = executeAndCommit(exec, tx, as.track(), ds.track());
        assertTrue(result.isSuccess());
        assertEquals(sub(available, sum(value, fee)), as.getAccount(voter.toAddress()).getAvailable());
        assertEquals(value, as.getAccount(voter.toAddress()).getLocked());
        assertEquals(value, ds.getDelegateByAddress(delegate.toAddress()).getVotes());
    }

    @Test
    public void testUnvote() {
        Key voter = new Key();
        Key delegate = new Key();

        Amount available = SEM.of(100);
        as.adjustAvailable(voter.toAddress(), available);

        ds.register(delegate.toAddress(), Bytes.of("delegate"));

        TransactionType type = TransactionType.UNVOTE;
        byte[] from = voter.toAddress();
        byte[] to = delegate.toAddress();
        Amount value = SEM.of(33);
        Amount fee = config.minTransactionFee();
        long nonce = as.getAccount(from).getNonce();
        long timestamp = System.currentTimeMillis();
        byte[] data = {};

        // unvote (never voted before)
        Transaction tx = new Transaction(network, type, to, value, fee, nonce, timestamp, data).sign(voter);
        TransactionResult result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());
        assertEquals(INSUFFICIENT_LOCKED, result.error);
        ds.vote(voter.toAddress(), delegate.toAddress(), value);

        // unvote (locked = 0)
        result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());
        assertEquals(INSUFFICIENT_LOCKED, result.error);

        as.adjustLocked(voter.toAddress(), value);

        // normal unvote
        result = executeAndCommit(exec, tx, as.track(), ds.track());
        assertTrue(result.isSuccess());
        assertEquals(sum(available, sub(value, fee)), as.getAccount(voter.toAddress()).getAvailable());
        assertEquals(ZERO, as.getAccount(voter.toAddress()).getLocked());
        assertEquals(ZERO, ds.getDelegateByAddress(delegate.toAddress()).getVotes());
    }

    @Test
    public void testUnvoteInsufficientFee() {
        Key voter = new Key();
        Key delegate = new Key();

        as.adjustAvailable(voter.toAddress(), sub(config.minTransactionFee(), NANO_SEM.of(1)));
        ds.register(delegate.toAddress(), Bytes.of("delegate"));

        TransactionType type = TransactionType.UNVOTE;
        byte[] from = voter.toAddress();
        byte[] to = delegate.toAddress();
        Amount value = SEM.of(100);
        Amount fee = config.minTransactionFee();
        long nonce = as.getAccount(from).getNonce();
        long timestamp = System.currentTimeMillis();
        byte[] data = {};

        // unvote (never voted before)
        Transaction tx = new Transaction(network, type, to, value, fee, nonce, timestamp, data).sign(voter);

        TransactionResult result = exec.execute(tx, as.track(), ds.track());
        assertFalse(result.isSuccess());
        assertEquals(INSUFFICIENT_AVAILABLE, result.error);
    }

    @Test
    public void testValidateDelegateName() {
        assertFalse(TransactionExecutor.validateDelegateName(Bytes.random(2)));
        assertFalse(TransactionExecutor.validateDelegateName(Bytes.random(17)));
        assertFalse(TransactionExecutor.validateDelegateName(new byte[] { 0x11, 0x22, 0x33 }));

        int[][] ranges = { { 'a', 'z' }, { '0', '9' }, { '_', '_' } };
        for (int[] range : ranges) {
            for (int i = range[0]; i <= range[1]; i++) {
                byte[] data = new byte[3];
                data[0] = (byte) i;
                data[1] = (byte) i;
                data[2] = (byte) i;
                assertTrue(TransactionExecutor.validateDelegateName(data));
            }
        }
    }
}
