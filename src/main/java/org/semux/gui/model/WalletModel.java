/**
 * Copyright (c) 2017-2018 The Semux Developers
 *
 * Distributed under the MIT software license, see the accompanying file
 * LICENSE or https://opensource.org/licenses/mit-license.php
 */
package org.semux.gui.model;

import static org.semux.core.Amount.ZERO;

import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.semux.config.Config;
import org.semux.consensus.ValidatorActivatedFork;
import org.semux.core.Amount;
import org.semux.core.Block;
import org.semux.core.SyncManager;
import org.semux.core.state.Account;
import org.semux.crypto.Key;
import org.semux.gui.Action;
import org.semux.net.Peer;
import org.semux.util.ByteArray;

/**
 * A Model stores all the data that GUI needs. The thread-safety of this class
 * is achieved by swapping pointers instead of synchronization.
 */
public class WalletModel {

    private final List<ActionListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ActionListener> lockableComponents = new CopyOnWriteArrayList<>();

    private final Config config;

    private SyncManager.Progress syncProgress;

    private Block latestBlock;

    private Key coinbase;
    private Status status;

    private volatile Map<ByteArray, Integer> accountsIndex = new HashMap<>();
    private volatile List<WalletAccount> accounts = new ArrayList<>();
    private volatile List<WalletDelegate> delegates = new ArrayList<>();
    private volatile List<String> validators = new ArrayList<>();
    private volatile Map<ValidatorActivatedFork, ValidatorActivatedFork.Activation> activatedForks = new HashMap<>();

    private Map<String, Peer> activePeers = new HashMap<>();

    public WalletModel(Config config) {
        this.config = config;
    }

    /**
     * Fires an model update event.
     */
    public void fireUpdateEvent() {
        updateView();
    }

    /**
     * Fires an lock event.
     */
    public void fireLockEvent() {
        lockView();
    }

    /**
     * Add a listener.
     * 
     * @param listener
     */
    public void addListener(ActionListener listener) {
        listeners.add(listener);
    }

    /**
     * Add a component for locking.<br />
     * This component has to provide Action.LOCK as ActionListener Event
     * 
     * @param listener
     */
    public void addLockable(ActionListener listener) {
        lockableComponents.add(listener);
    }

    /**
     * Getter for property ${@link #syncProgress}.
     *
     * @return Value to set for property ${@link #syncProgress}.
     */
    public Optional<SyncManager.Progress> getSyncProgress() {
        return Optional.ofNullable(syncProgress);
    }

    /**
     * Setter for property ${@link #syncProgress}.
     *
     * @param syncProgress
     *            Value to set for property ${@link #syncProgress}.
     */
    public void setSyncProgress(SyncManager.Progress syncProgress) {
        this.syncProgress = syncProgress;
    }

    /**
     * Get the latest block.
     * 
     * @return
     */
    public Block getLatestBlock() {
        return latestBlock;
    }

    /**
     * Set the latest block.
     * 
     * @param latestBlock
     */
    public void setLatestBlock(Block latestBlock) {
        this.latestBlock = latestBlock;
    }

    /**
     * Get the coinbase.
     * 
     * @return
     */
    public Key getCoinbase() {
        return coinbase;
    }

    /**
     * Set the coinbase.
     * 
     * @param coinbase
     */
    public void setCoinbase(Key coinbase) {
        this.coinbase = coinbase;
    }

    /**
     * Returns the account status.
     * 
     * @return
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Sets the account status.
     * 
     * @param status
     */
    public void setStatus(Status status) {
        this.status = status;
    }

    /**
     * Get the total available.
     * 
     * @return
     */
    public Amount getTotalAvailable() {
        return accounts.stream().map(Account::getAvailable).reduce(ZERO, Amount::sum);
    }

    /**
     * Get the total locked.
     * 
     * @return
     */
    public Amount getTotalLocked() {
        return accounts.stream().map(Account::getLocked).reduce(ZERO, Amount::sum);
    }

    public List<WalletAccount> getAccounts() {
        return accounts;
    }

    public int getAccountNumber(byte[] address) {
        Integer n = accountsIndex.get(ByteArray.of(address));
        return n == null ? -1 : n;
    }

    public WalletAccount getAccount(byte[] address) {
        int accountNum = getAccountNumber(address);
        return accountNum >= 0 ? accounts.get(accountNum) : null;
    }

    public void setAccounts(List<WalletAccount> accounts) {
        Map<ByteArray, Integer> map = new HashMap<>();
        for (int i = 0; i < accounts.size(); i++) {
            map.put(ByteArray.of(accounts.get(i).getKey().toAddress()), i);
        }
        this.accounts = accounts;
        this.accountsIndex = map;
    }

    public List<WalletDelegate> getDelegates() {
        return delegates;
    }

    public void setDelegates(List<WalletDelegate> delegates) {
        this.delegates = delegates;
    }

    public Map<String, Peer> getActivePeers() {
        return activePeers;
    }

    /**
     * Getter for property 'validators'.
     *
     * @return Value for property 'validators'.
     */
    public List<String> getValidators() {
        return validators;
    }

    /**
     * Setter for property 'validators'.
     *
     * @param validators
     *            Value to set for property 'validators'.
     */
    public void setValidators(List<String> validators) {
        this.validators = validators;
    }

    public void setActivePeers(Map<String, Peer> activePeers) {
        this.activePeers = activePeers;
    }

    /**
     * Returns the address of the current primary validator based off
     * {@link this#latestBlock}.
     *
     * @return The address of the current primary validator.
     */
    public Optional<String> getPrimaryValidator() {
        if (validators == null || latestBlock == null || activatedForks == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(config.getPrimaryValidator(validators,
                latestBlock.getNumber() + 1,
                0,
                activatedForks.containsKey(ValidatorActivatedFork.UNIFORM_DISTRIBUTION)));
    }

    /**
     * Returns the {@link WalletDelegate} of the current primary validator based off
     * {@link this#latestBlock}.
     *
     * @return The {@link WalletDelegate} of the current primary validator.
     */
    public Optional<WalletDelegate> getPrimaryValidatorDelegate() {
        if (delegates == null || !getPrimaryValidator().isPresent()) {
            return Optional.empty();
        }

        return delegates.stream()
                .filter(wd -> wd.getAddressString().equals(getPrimaryValidator().get()))
                .findFirst();
    }

    /**
     * Returns the {@link WalletDelegate} of the next primary validator based off
     * {@link this#latestBlock}.
     *
     * @return The {@link WalletDelegate} of the next primary validator.
     */
    public Optional<WalletDelegate> getNextPrimaryValidatorDelegate() {
        if (latestBlock == null || delegates == null || validators == null) {
            return Optional.empty();
        }

        // the next validator can't be predicted if the validator set is going to be
        // updated in the next round
        if ((latestBlock.getNumber() + 2) % config.getValidatorUpdateInterval() == 0) {
            return Optional.empty();
        }

        return delegates.stream()
                .filter(wd -> wd.getAddressString().equals(
                        validators.get((int) ((latestBlock.getNumber() + 2) % validators.size()))))
                .findFirst();
    }

    /**
     * Calculates and returns the block number of next validator set update based
     * off {@link this#latestBlock}.
     *
     * @return the block number of next validator set update.
     */
    public Optional<Long> getNextValidatorSetUpdate() {
        if (latestBlock == null) {
            return Optional.empty();
        }

        return Optional.of(
                ((latestBlock.getNumber() + 1) / config.getValidatorUpdateInterval() + 1)
                        * config.getValidatorUpdateInterval());
    }

    /**
     * Getter for property 'activatedForks'.
     *
     * @return Value for property 'activatedForks'.
     */
    public Map<ValidatorActivatedFork, ValidatorActivatedFork.Activation> getActivatedForks() {
        return activatedForks;
    }

    /**
     * Setter for property 'activatedForks'.
     *
     * @param activatedForks
     *            Value to set for property 'activatedForks'.
     */
    public void setActivatedForks(Map<ValidatorActivatedFork, ValidatorActivatedFork.Activation> activatedForks) {
        this.activatedForks = activatedForks;
    }

    /**
     * Updates MVC view.
     */
    protected void updateView() {
        for (ActionListener listener : listeners) {
            EventQueue.invokeLater(() -> listener.actionPerformed(new ActionEvent(this, 0, Action.REFRESH.name())));
        }
    }

    /**
     * Locks components.
     */
    protected void lockView() {
        for (ActionListener listener : lockableComponents) {
            EventQueue.invokeLater(() -> listener.actionPerformed(new ActionEvent(this, 0, Action.LOCK.name())));
        }
    }

    public enum Status {
        NORMAL, DELEGATE, VALIDATOR
    }
}
