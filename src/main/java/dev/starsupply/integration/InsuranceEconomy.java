package dev.starsupply.integration;

import dev.starsupply.api.InsuranceApi.MoneyOperation;
import dev.starsupply.api.InsuranceApi.MoneyResult;

/** Public authority boundary. Nonreceipt calls must never be retried after an uncertain result. */
public interface InsuranceEconomy {
    MoneyResult execute(MoneyOperation operation);

    /** Operator-confirmed evidence, never a guess based on present balances. */
    MoneyResult reconcile(MoneyOperation operation, boolean applied);
}
