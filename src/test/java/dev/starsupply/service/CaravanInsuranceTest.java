package dev.starsupply.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsupply.api.InsuranceApi.*;
import dev.starsupply.api.SupplyModels.Shipment;
import dev.starsupply.api.SupplyModels.TransportState;
import dev.starsupply.integration.InsuranceEconomy;
import dev.starsupply.storage.InsuranceStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaravanInsuranceTest {
    private static final UUID NATION = UUID.randomUUID(), CARRIER = UUID.randomUUID();
    private static final long NOW = 100_000;
    @TempDir Path folder;

    @Test void upfrontPremiumAndReservedClaimRemainIdempotentAcrossCommandsAndRestart() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.25);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("insurance.db"))) {
            CaravanInsurance service = service(store, money);
            Quote quote = service.quote(shipment, 0.25, NOW);
            assertEquals(new BigDecimal("8.00"), quote.claimAmount());
            assertEquals(new BigDecimal("2.80"), quote.premium());
            Policy policy = service.buy(shipment, CARRIER, false, 0.25, NOW);
            assertEquals(State.ACTIVE, policy.state());
            assertEquals(policy.id(), service.buy(shipment, CARRIER, false, 0.25, NOW + 1).id());
            assertEquals(1, money.calls.get(MoneyLeg.RESERVE));
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_CREDIT));
            assertThrows(IllegalArgumentException.class, () -> service.requireCarrier(shipment.id(), UUID.randomUUID()));
            assertThrows(IllegalArgumentException.class, () -> service.requireRisk(shipment.id(), 0.26));
        }
        try (InsuranceStore store = new InsuranceStore(folder.resolve("insurance.db"))) {
            CaravanInsurance service = service(store, money);
            service.resume(NOW + 2);
            Policy closed = service.complete(withState(shipment, TransportState.DELIVERED), false, NOW + 3);
            assertEquals(State.CLOSED, closed.state());
            assertEquals(1, money.calls.get(MoneyLeg.RELEASE));
            assertFalse(money.calls.containsKey(MoneyLeg.CLAIM));
            assertFalse(money.calls.containsKey(MoneyLeg.PREMIUM_REFUND), "activated coverage earns its premium");
        }
    }

    @Test void onlyActualRouteRiskFailureClaimsAndSameCargoCannotInsureTwice() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.50);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("claims.db"))) {
            CaravanInsurance service = service(store, money);
            service.buy(shipment, CARRIER, false, 0.50, NOW);
            Policy claimed = service.complete(withState(shipment, TransportState.FAILED), true, NOW + 5);
            assertEquals(State.CLAIMED, claimed.state());
            assertEquals(State.CLAIMED, service.complete(withState(shipment, TransportState.FAILED), true, NOW + 6).state());
            assertEquals(1, money.calls.get(MoneyLeg.CLAIM));
            assertFalse(money.calls.containsKey(MoneyLeg.RELEASE));
            Shipment rerun = shipment(UUID.randomUUID(), shipment.purchaseId(), TransportState.OPEN, 0.50);
            assertThrows(IllegalArgumentException.class, () -> service.buy(rerun, CARRIER, false, 0.50, NOW + 7));
            Shipment failedBefore = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.50);
            assertThrows(IllegalArgumentException.class, () -> service.buy(failedBefore, CARRIER, true, 0.50, NOW + 7));
        }
    }

    @Test void uncertainPremiumDebitFreezesShipmentUntilIndependentEvidenceAndNeverRecharges() throws Exception {
        FakeEconomy money = new FakeEconomy();
        money.results.put(MoneyLeg.PREMIUM_DEBIT, MoneyResult.REVIEW);
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.30);
        UUID id;
        try (InsuranceStore store = new InsuranceStore(folder.resolve("debit-review.db"))) {
            CaravanInsurance service = service(store, money);
            Policy policy = service.buy(shipment, CARRIER, false, 0.30, NOW);
            id = policy.id();
            assertTrue(policy.review());
            assertThrows(IllegalArgumentException.class, () -> service.requireCarrier(shipment.id(), CARRIER));
        }
        try (InsuranceStore store = new InsuranceStore(folder.resolve("debit-review.db"))) {
            CaravanInsurance service = service(store, money);
            service.resume(NOW + 1);
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_DEBIT));
            Policy closed = service.reconcile(id, MoneyLeg.PREMIUM_DEBIT, false, NOW + 2);
            assertEquals(State.CLOSED, closed.state());
            assertEquals(1, money.calls.get(MoneyLeg.RELEASE));
            assertFalse(money.calls.containsKey(MoneyLeg.PREMIUM_CREDIT));
            assertFalse(money.calls.containsKey(MoneyLeg.PREMIUM_REFUND));
        }
    }

    @Test void confirmedUnfundedPremiumCreditRefundsOnlyItsConfirmedDebitBeforeReleasingReserve() throws Exception {
        FakeEconomy money = new FakeEconomy();
        money.results.put(MoneyLeg.PREMIUM_CREDIT, MoneyResult.REVIEW);
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.30);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("credit-review.db"))) {
            CaravanInsurance service = service(store, money);
            Policy policy = service.buy(shipment, CARRIER, false, 0.30, NOW);
            assertTrue(policy.review());
            Policy closed = service.reconcile(policy.id(), MoneyLeg.PREMIUM_CREDIT, false, NOW + 2);
            assertEquals(State.CLOSED, closed.state());
            assertTrue(closed.refundPremium());
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_REFUND));
            assertEquals(1, money.calls.get(MoneyLeg.RELEASE));
            service.resume(NOW + 3);
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_REFUND));
        }
    }

    @Test void crashWithNonreceiptCallStartedDoesNotReplayItAfterDatabaseReopen() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.20);
        UUID id;
        try (InsuranceStore store = new InsuranceStore(folder.resolve("crash.db"))) {
            Policy policy = store.create(service(store, money).quote(shipment, 0.20, NOW), CARRIER, NOW);
            id = policy.id();
            store.money(id, MoneyLeg.RESERVE, MoneyState.APPLIED, NOW);
            store.money(id, MoneyLeg.PREMIUM_DEBIT, MoneyState.STARTED, NOW);
        }
        try (InsuranceStore store = new InsuranceStore(folder.resolve("crash.db"))) {
            CaravanInsurance service = service(store, money);
            service.resume(NOW + 1);
            assertTrue(service.policy(shipment.id()).orElseThrow().review());
            assertFalse(money.calls.containsKey(MoneyLeg.PREMIUM_DEBIT));
            Policy active = service.reconcile(id, MoneyLeg.PREMIUM_DEBIT, true, NOW + 2);
            assertEquals(State.ACTIVE, active.state());
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_CREDIT));
        }
    }

    @Test void failedClaimCreditRemainsFrozenAndResumesWithoutAnotherPremiumOrReservation() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.20);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("claim-review.db"))) {
            CaravanInsurance service = service(store, money);
            Policy active = service.buy(shipment, CARRIER, false, 0.20, NOW);
            money.results.put(MoneyLeg.CLAIM, MoneyResult.REVIEW);
            Policy review = service.complete(withState(shipment, TransportState.FAILED), true, NOW + 2);
            assertEquals(State.CLAIM_PENDING, review.state());
            assertTrue(review.review());
            service.resume(NOW + 3);
            assertEquals(1, money.calls.get(MoneyLeg.CLAIM));
            assertEquals(State.CLAIMED, service.reconcile(active.id(), MoneyLeg.CLAIM, true, NOW + 4).state());
            assertEquals(1, money.calls.get(MoneyLeg.RESERVE));
            assertEquals(1, money.calls.get(MoneyLeg.PREMIUM_DEBIT));
        }
    }

    @Test void timeoutsCancellationsAndUnaffordableReservesNeverProduceInsuranceClaims() throws Exception {
        FakeEconomy money = new FakeEconomy();
        try (InsuranceStore store = new InsuranceStore(folder.resolve("nonclaims.db"))) {
            CaravanInsurance service = service(store, money);
            Shipment timeout = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
            service.buy(timeout, CARRIER, false, 0.40, NOW);
            assertEquals(State.CLOSED, service.complete(withState(timeout, TransportState.FAILED), false, NOW + 100_000).state());
            Shipment cancelled = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
            service.buy(cancelled, CARRIER, false, 0.40, NOW);
            assertThrows(IllegalArgumentException.class, () -> service.cancel(cancelled.id(), UUID.randomUUID(), false, NOW));
            assertEquals(State.CLOSED, service.cancel(cancelled.id(), CARRIER, false, NOW).state());
            money.results.put(MoneyLeg.RESERVE, MoneyResult.REJECTED);
            Shipment unaffordable = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
            assertEquals(State.CLOSED, service.buy(unaffordable, CARRIER, false, 0.40, NOW).state());
            assertEquals(2, money.calls.get(MoneyLeg.PREMIUM_DEBIT));
            assertFalse(money.calls.containsKey(MoneyLeg.CLAIM));
        }
    }

    @Test void persistedRiskOutcomeBeforeDeadlineCanBeClaimedAfterLongRestart() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("late-claim.db"))) {
            service(store, money).buy(shipment, CARRIER, false, 0.40, NOW);
        }
        try (InsuranceStore store = new InsuranceStore(folder.resolve("late-claim.db"))) {
            CaravanInsurance service = service(store, money);
            Policy claimed = service.complete(withState(shipment, TransportState.FAILED), true,
                    NOW + 1, NOW + 1_000_000);
            assertEquals(State.CLAIMED, claimed.state());
            assertEquals(1, money.calls.get(MoneyLeg.CLAIM));
        }
    }

    @Test void insuredPostingReleasesCollateralIfCarrierNeverDeparts() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("departure-timeout.db"))) {
            CaravanInsurance service = service(store, money);
            service.buy(shipment, CARRIER, false, 0.40, NOW);
            assertEquals(State.ACTIVE, service.expireUnstarted(shipment, NOW + 9_999, 10_000).state());
            assertEquals(State.CLOSED, service.expireUnstarted(shipment, NOW + 10_000, 10_000).state());
            assertEquals(1, money.calls.get(MoneyLeg.RELEASE));
            assertFalse(money.calls.containsKey(MoneyLeg.PREMIUM_REFUND));
        }
    }

    @Test void delayedPremiumReviewReceivesFullDepartureWindowFromActualActivationAcrossRestart() throws Exception {
        FakeEconomy money = new FakeEconomy();
        money.results.put(MoneyLeg.PREMIUM_DEBIT, MoneyResult.REVIEW);
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        long activated = NOW + 1_000_000;
        UUID id;
        try (InsuranceStore store = new InsuranceStore(folder.resolve("activation.db"))) {
            CaravanInsurance service = service(store, money);
            Policy waiting = service.buy(shipment, CARRIER, false, 0.40, NOW);
            id = waiting.id();
            assertEquals(0, waiting.activatedAt());
            Policy active = service.reconcile(id, MoneyLeg.PREMIUM_DEBIT, true, activated);
            assertEquals(activated, active.activatedAt());
            assertEquals(State.ACTIVE, service.expireUnstarted(shipment, activated + 9_999, 10_000).state());
            assertFalse(money.calls.containsKey(MoneyLeg.RELEASE));
            // A later money/state refresh must never extend the departure deadline.
            store.money(id, MoneyLeg.PREMIUM_CREDIT, MoneyState.APPLIED, activated + 5_000);
            store.state(id, State.ACTIVE, false, activated + 5_000);
            assertEquals(activated, store.find(id).orElseThrow().activatedAt());
        }
        try (InsuranceStore store = new InsuranceStore(folder.resolve("activation.db"))) {
            CaravanInsurance service = service(store, money);
            assertEquals(activated, service.policy(shipment.id()).orElseThrow().activatedAt());
            assertEquals(State.CLOSED, service.expireUnstarted(shipment, activated + 10_000, 10_000).state());
            assertEquals(1, money.calls.get(MoneyLeg.RELEASE));
        }
    }

    @Test void legacyInsuranceSchemaMigratesActiveBoundaryWithoutActivatingUnfinishedPolicy() throws Exception {
        Path file = folder.resolve("legacy-insurance.db");
        FakeEconomy money = new FakeEconomy();
        Shipment active = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        Shipment waiting = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        UUID waitingId;
        long legacyUpdated = NOW + 4_000;
        try (InsuranceStore store = new InsuranceStore(file)) {
            CaravanInsurance service = service(store, money);
            Policy policy = service.buy(active, CARRIER, false, 0.40, NOW);
            store.money(policy.id(), MoneyLeg.PREMIUM_CREDIT, MoneyState.APPLIED, legacyUpdated);
            waitingId = store.create(service.quote(waiting, 0.40, NOW), CARRIER, NOW).id();
        }
        // Reconstruct the shipped v1 shape; this exercises a real on-disk ALTER migration.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE insurance_policy DROP COLUMN activated");
            statement.execute("PRAGMA user_version=1");
        }
        try (InsuranceStore store = new InsuranceStore(file)) {
            assertEquals(legacyUpdated, store.forShipment(active.id()).orElseThrow().activatedAt());
            assertEquals(0, store.find(waitingId).orElseThrow().activatedAt());
            service(store, money).resume(legacyUpdated + 10_000);
            assertEquals(legacyUpdated + 10_000, store.find(waitingId).orElseThrow().activatedAt());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
    }

    @Test void rejectedCargoCancellationCannotReleaseInsuranceMoneyOrDestroyRiskClaim() throws Exception {
        FakeEconomy money = new FakeEconomy();
        Shipment shipment = shipment(UUID.randomUUID(), UUID.randomUUID(), TransportState.OPEN, 0.40);
        try (InsuranceStore store = new InsuranceStore(folder.resolve("cancellation-order.db"))) {
            CaravanInsurance service = service(store, money);
            service.buy(shipment, CARRIER, false, 0.40, NOW);
            for (TransportState state : new TransportState[]{TransportState.SETTLING, TransportState.REVIEW,
                    TransportState.FAILED, TransportState.DELIVERED, TransportState.CANCELLED}) {
                assertThrows(IllegalArgumentException.class,
                        () -> service.cancel(withState(shipment, state), CARRIER, false, NOW + 1));
                assertEquals(State.ACTIVE, service.policy(shipment.id()).orElseThrow().state());
                assertFalse(money.calls.containsKey(MoneyLeg.RELEASE));
            }
            assertEquals(State.CLAIMED,
                    service.complete(withState(shipment, TransportState.FAILED), true, NOW + 2).state());
            assertEquals(1, money.calls.get(MoneyLeg.CLAIM));
        }
    }

    private static CaravanInsurance service(InsuranceStore store, InsuranceEconomy economy) {
        return new CaravanInsurance(store, economy, new BigDecimal("0.10"), new BigDecimal("1.00"));
    }

    private static Shipment shipment(UUID id, UUID purchase, TransportState state, double risk) {
        return new Shipment(id, NATION, purchase, "depot", "front", new BigDecimal("10.00"), state,
                state == TransportState.OPEN ? null : CARRIER, NOW, NOW + 50_000, "depot>front", NOW, risk);
    }

    private static Shipment withState(Shipment shipment, TransportState state) {
        return shipment(shipment.id(), shipment.purchaseId(), state, shipment.routeRisk());
    }

    private static final class FakeEconomy implements InsuranceEconomy {
        final Map<MoneyLeg,MoneyResult> results = new EnumMap<>(MoneyLeg.class);
        final Map<MoneyLeg,Integer> calls = new EnumMap<>(MoneyLeg.class);
        @Override public MoneyResult execute(MoneyOperation operation) {
            calls.merge(operation.leg(), 1, Integer::sum);
            return results.getOrDefault(operation.leg(), MoneyResult.APPLIED);
        }
        @Override public MoneyResult reconcile(MoneyOperation operation, boolean applied) {
            return applied ? MoneyResult.APPLIED : MoneyResult.REJECTED;
        }
    }
}
