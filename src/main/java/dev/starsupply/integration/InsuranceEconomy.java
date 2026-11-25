package dev.starsupply.integration;

import dev.starsupply.api.InsuranceApi.MoneyOperation;
import dev.starsupply.api.InsuranceApi.MoneyResult;

/** Public authority boundary. Nonreceipt calls must never be retried after an uncertain result. */
public interface InsuranceEconomy {
    MoneyResult execute(MoneyOperation operation);

    /** True only when this exact operation can be retried through an independent durable receipt. */
    default boolean canReplay(MoneyOperation operation) { return false; }

    /** Operator-confirmed evidence, never a guess based on present balances. */
    MoneyResult reconcile(MoneyOperation operation, boolean applied);
}
