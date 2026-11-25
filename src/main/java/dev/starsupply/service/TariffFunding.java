package dev.starsupply.service;

import dev.starsupply.api.SupplyModels.TaxAssessment;
import dev.starsupply.integration.CoreGateway;
import dev.starsupply.integration.TaxFundingRetryException;
import dev.starsupply.storage.SupplyStore;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import static dev.starsupply.storage.SupplyStore.TaxState.*;

/** Coordinates the local SQL phase with the original Core tax protocol. */
public final class TariffFunding {
    private final SupplyStore store;
    private final CoreGateway core;
    private final LongSupplier clock;
    private final Logger logger;

    public TariffFunding(SupplyStore store, CoreGateway core, LongSupplier clock, Logger logger) {
        this.store=store; this.core=core; this.clock=clock; this.logger=logger;
    }

    public boolean fund(TaxAssessment tax) {
        SupplyStore.TaxState state;
        boolean marked;
        try {
            state = store.taxState(tax.settlementId()).orElseThrow(() -> new IllegalArgumentException("Missing tax phase"));
            if (state == REVIEW) return false;
            marked = core.tariffUsesReceipts(tax);
            // Persist protocol before FUNDING. An older FUNDING without this marker stays legacy.
            if (!marked && state == QUOTED) marked = core.prepareTariffReceipts(tax);
        } catch (IllegalArgumentException conflict) {
            logger.log(Level.WARNING, "Tariff identity needs review " + tax.settlementId(), conflict);
            return false;
        } catch (Exception unavailable) {
            throw new TaxFundingRetryException("Tariff intent could not be read or saved", unavailable);
        }
        if (!marked) return legacy(tax, state);
        try {
            if (state == QUOTED && !store.beginTaxFunding(tax.settlementId(), clock.getAsLong())) {
                throw new IllegalStateException("Tax phase changed before funding");
            }
            if (!core.fundTariffReceipt(tax)) throw new IllegalStateException("Core has not confirmed the tax receipt");
            if (state != FUNDED) store.finishTaxFunding(tax.settlementId(), true, clock.getAsLong());
            return true;
        } catch (IllegalArgumentException conflict) {
            logger.log(Level.WARNING, "Tariff receipt needs review " + tax.settlementId(), conflict);
            return false;
        } catch (Exception uncertain) {
            throw new TaxFundingRetryException("Retry original receipted tariff " + tax.settlementId(), uncertain);
        }
    }

    private boolean legacy(TaxAssessment tax, SupplyStore.TaxState state) {
        try {
            if (state == FUNDED) return true;
            if (state == FUNDING) {
                store.finishTaxFunding(tax.settlementId(), false, clock.getAsLong());
                return false;
            }
            if (!store.beginTaxFunding(tax.settlementId(), clock.getAsLong())) return false;
            boolean funded;
            try { funded = core.fundTariff(tax); }
            catch (RuntimeException uncertain) {
                store.finishTaxFunding(tax.settlementId(), false, clock.getAsLong());
                logger.log(Level.SEVERE, "Legacy tariff needs reconciliation " + tax.settlementId(), uncertain);
                return false;
            }
            store.finishTaxFunding(tax.settlementId(), funded, clock.getAsLong());
            return funded;
        } catch (Exception uncertain) {
            logger.log(Level.SEVERE, "Legacy tariff needs reconciliation " + tax.settlementId(), uncertain);
            return false;
        }
    }
}
