package org.maxgamer.quickshop.economy;

import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.maxgamer.quickshop.QuickShop;
import org.maxgamer.quickshop.util.CalculateUtil;
import org.maxgamer.quickshop.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
public class EconomyTransaction {
    @Nullable
    private final UUID from;
    @Nullable
    private final UUID to;
    private final double amount;
    @NotNull
    private final EconomyCore core;
    private final double actualAmount; //
    private final double tax;
    private final UUID taxAccount;
    private final boolean allowLoan;
    @Getter
    private TransactionSteps steps; //For rollback
    @Nullable
    @Getter
    private String lastError = null;

    /**
     * Create a transaction
     *
     * @param from        The account that money from, but null will be ignored.
     * @param to          The account that money to, but null will be ignored.
     * @param core        economy core
     * @param allowLoan   allow loan?
     * @param amount      the amount of money
     * @param taxAccount  tax account
     * @param taxModifier tax modifier
     */

    @Builder
    public EconomyTransaction(@Nullable UUID from, @Nullable UUID to, double amount, double taxModifier, @Nullable UUID taxAccount, EconomyCore core, boolean allowLoan) {
        this.from = from;
        this.to = to;
        this.core = core == null ? QuickShop.getInstance().getEconomy() : core;
        this.amount = amount;
        this.steps = TransactionSteps.WAIT;
        this.taxAccount = taxAccount;
        this.allowLoan = allowLoan;
        if (taxModifier != 0.0d) { //Calc total money and apply tax
            this.actualAmount = CalculateUtil.multiply(CalculateUtil.subtract(1, taxModifier), amount);
        } else {
            this.actualAmount = amount;
        }
        this.tax = CalculateUtil.subtract(amount, actualAmount); //Calc total tax
        if (from == null && to == null) {
            lastError = "From and To cannot be null in same time.";
            throw new IllegalArgumentException("From and To cannot be null in same time.");
        }
        //Fetch some stupid plugin caching
        if (from != null) {
            this.core.getBalance(from);
        }
        if (to != null) {
            this.core.getBalance(to);
        }
    }

    /**
     * Commit the transaction by the Fail-Safe way
     * Automatic rollback when commit failed
     *
     * @return The transaction success.
     */
    public boolean failSafeCommit() {
        Util.debugLog("Transaction begin: FailSafe Commit --> " + from + " => " + to + "; Amount: " + amount + ", EconomyCore: " + core.getName());
        boolean result = commit();
        if (!result) {
            rollback(true);
        }
        return result;
    }

    /**
     * Commit the transaction
     *
     * @return The transaction success.
     */
    public boolean commit() {
        return this.commit(new TransactionCallback() {
            @Override
            public void onSuccess(@NotNull EconomyTransaction economyTransaction) {
                //Fetch some stupid plugin caching
                if (from != null) {
                    core.getBalance(from);
                }
                if (to != null) {
                    core.getBalance(to);
                }
            }
        });
    }

    /**
     * Commit the transaction with callback
     *
     * @param callback The result callback
     * @return The transaction success.
     */
    public boolean commit(@NotNull TransactionCallback callback) {
        Util.debugLog("Transaction begin: Regular Commit --> " + from + " => " + to + "; Amount: " + amount + " Total(include tax): " + actualAmount + " Tax: " + tax + ", EconomyCore: " + core.getName());
        steps = TransactionSteps.CHECK;
        if (from != null && core.getBalance(from) < amount && !allowLoan) {
            this.lastError = "From hadn't enough money";
            callback.onFailed(this);
            return false;
        }
        steps = TransactionSteps.WITHDRAW;
        if (from != null && !core.withdraw(from, amount)) {
            this.lastError = "Failed to withdraw " + amount + " from player " + from.toString() + " account";
            callback.onFailed(this);
            return false;
        }
        steps = TransactionSteps.DEPOSIT;
        if (to != null && !core.deposit(to, actualAmount)) {
            this.lastError = "Failed to deposit " + actualAmount + " to player " + to.toString() + " account";
            callback.onFailed(this);
            return false;
        }
        steps = TransactionSteps.TAX;
        if (tax > 0 && taxAccount != null && !core.deposit(taxAccount, tax)) {
            this.lastError = "Failed to deposit tax account: " + tax;
            callback.onTaxFailed(this);
            //Tax never should failed.
        }
        steps = TransactionSteps.DONE;
        callback.onSuccess(this);
        return true;
    }

    /**
     * Rolling back the transaction
     *
     * @param continueWhenFailed Continue when some parts of the rollback fails.
     * @return A list contains all steps executed. If "continueWhenFailed" is false, it only contains all success steps before hit the error. Else all.
     */
    @SuppressWarnings("UnusedReturnValue")
    @NotNull
    public List<RollbackSteps> rollback(boolean continueWhenFailed) {
        List<RollbackSteps> rollbackSteps = new ArrayList<>(3);
        if (steps == TransactionSteps.CHECK) {
            return rollbackSteps; //We did nothing, just checks balance
        }
        if (steps == TransactionSteps.WITHDRAW) {
            return rollbackSteps; //We did nothing, because the trade failed so no anybody money changes.
        }
        if (steps == TransactionSteps.DEPOSIT || steps == TransactionSteps.TAX) {
            if (from != null && !core.deposit(from, amount)) { //Rollback withdraw
                if (!continueWhenFailed) {
                    rollbackSteps.add(RollbackSteps.ROLLBACK_WITHDRAW);
                    return rollbackSteps;
                }
            }
        }
        if (steps == TransactionSteps.TAX) {
            if (to != null && !core.withdraw(to, actualAmount)) { //Rollback deposit
                if (!continueWhenFailed) {
                    rollbackSteps.add(RollbackSteps.ROLLBACK_DEPOSIT);
                    return rollbackSteps;
                }
            }
        }

        rollbackSteps.add(RollbackSteps.ROLLBACK_DONE);
        return rollbackSteps;
    }

    private enum RollbackSteps {
        ROLLBACK_WITHDRAW,
        ROLLBACK_DEPOSIT,
        ROLLBACK_TAX,
        ROLLBACK_DONE
    }

    public enum TransactionSteps {
        WAIT,
        CHECK,
        WITHDRAW,
        DEPOSIT,
        TAX,
        DONE
    }

    interface TransactionCallback {
        default void onSuccess(@NotNull EconomyTransaction economyTransaction) {
            Util.debugLog("Transaction succeed.");
        }

        default void onFailed(@NotNull EconomyTransaction economyTransaction) {
            Util.debugLog("Transaction failed: " + economyTransaction.getLastError() + ".");
        }

        default void onTaxFailed(@NotNull EconomyTransaction economyTransaction) {
            Util.debugLog("Tax Transaction failed: " + economyTransaction.getLastError() + ".");
        }

    }

}
