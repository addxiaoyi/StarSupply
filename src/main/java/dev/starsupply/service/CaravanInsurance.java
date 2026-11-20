package dev.starsupply.service;

import dev.starsupply.api.InsuranceApi.*;
import dev.starsupply.api.SupplyModels.Shipment;
import dev.starsupply.api.SupplyModels.TransportState;
import dev.starsupply.integration.InsuranceEconomy;
import dev.starsupply.storage.InsuranceStore;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Serialized durable coordinator. All values are snapshots of an authorized cargo operation. */
public final class CaravanInsurance {
    private final InsuranceStore store;
    private final InsuranceEconomy economy;
    private final BigDecimal loading;
    private final BigDecimal minimumPremium;

    public CaravanInsurance(InsuranceStore store, InsuranceEconomy economy,
                            BigDecimal loadingRate, BigDecimal minimumPremium) {
        this.store = Objects.requireNonNull(store);
        this.economy = Objects.requireNonNull(economy);
        this.loading = Objects.requireNonNull(loadingRate);
        this.minimumPremium = Objects.requireNonNull(minimumPremium);
        require(loading.signum() >= 0 && loading.compareTo(new BigDecimal("0.50")) <= 0,
                "Insurance loading must be 0–50%");
        require(minimumPremium.signum() > 0 && minimumPremium.scale() <= 2,
                "Insurance minimum premium must be a positive currency amount");
    }

    public Quote quote(Shipment shipment, double currentRisk, long now) {
        require(shipment.state() == TransportState.OPEN, "只能在商队出发前投保");
        require(Double.isFinite(currentRisk) && currentRisk > 0 && currentRisk <= 1,
                "无风险线路无需投保");
        BigDecimal claim = shipment.reward().multiply(new BigDecimal("0.80")).setScale(2, RoundingMode.DOWN);
        require(claim.signum() > 0, "该运输的运费不足以提供保险");
        BigDecimal premium = claim.multiply(BigDecimal.valueOf(currentRisk).add(loading))
                .setScale(2, RoundingMode.CEILING).max(minimumPremium);
        return new Quote(shipment.id(), shipment.purchaseId(), shipment.nationId(), shipment.reward(),
                claim, premium, currentRisk, now);
    }

    public Policy buy(Shipment shipment, UUID carrier, boolean hasPreviousFailure, double currentRisk, long now)
            throws SQLException {
        Objects.requireNonNull(carrier);
        Optional<Policy> existing = store.forShipment(shipment.id());
        if (existing.isPresent()) {
            require(existing.get().carrierId().equals(carrier), "该运输保险属于另一名承运者");
            return existing.get(); // A duplicate command never creates another premium or reservation.
        }
        require(!hasPreviousFailure, "已经判定过失败的物资批次不能重新投保");
        require(store.forPurchase(shipment.purchaseId()).isEmpty(), "同一物资批次只能投保一次");
        return advance(store.create(quote(shipment, currentRisk, now), carrier, now), now);
    }

    public Optional<Policy> policy(UUID shipment) throws SQLException { return store.forShipment(shipment); }

    /** Root calls before accept/cancel/deliver. An active policy also binds the original carrier. */
    public void requireCarrier(UUID shipment, UUID actor) throws SQLException {
        Optional<Policy> current = policy(shipment);
        if (current.isEmpty() || current.get().terminal()) return;
        require(current.get().carrierId().equals(actor), "保险运输已绑定承运者");
        require(!current.get().blocksTransport(), "保险正在结算或需要人工核对，运输操作已冻结");
    }

    public void requireRisk(UUID shipment, double currentRisk) throws SQLException {
        Optional<Policy> current = policy(shipment);
        if (current.isEmpty() || current.get().terminal()) return;
        require(Double.isFinite(currentRisk) && currentRisk <= current.get().maximumRisk() + 0.000001,
                "新路线风险超过保险承保上限，请先退保或恢复原风险范围");
    }

    /** Call immediately after the authoritative cargo transaction, then again during maintenance. */
    public Policy complete(Shipment shipment, boolean actualRiskFailure, long now) throws SQLException {
        return complete(shipment, actualRiskFailure, now, now);
    }

    /** outcomeAt is the immutable cargo failure timestamp, including recovery after its deadline. */
    public Policy complete(Shipment shipment, boolean actualRiskFailure, long outcomeAt, long now) throws SQLException {
        Policy policy = policy(shipment.id()).orElseThrow(() -> new IllegalArgumentException("运输没有保险"));
        require(policy.purchaseId().equals(shipment.purchaseId()) && policy.nationId().equals(shipment.nationId()),
                "保险与运输不匹配");
        if (policy.state() != State.ACTIVE) return advance(policy, now);
        boolean terminalCargo = shipment.state() == TransportState.DELIVERED
                || shipment.state() == TransportState.CANCELLED || shipment.state() == TransportState.FAILED;
        require(terminalCargo, "运输尚未结束");
        boolean claim = actualRiskFailure && shipment.state() == TransportState.FAILED
                && policy.carrierId().equals(shipment.carrier())
                && shipment.routeRisk() <= policy.maximumRisk() + 0.000001
                && shipment.readyAt() > 0 && shipment.readyAt() <= outcomeAt && shipment.expiresAt() > outcomeAt;
        require(!actualRiskFailure || claim, "保险理赔不满足遭袭条件");
        store.state(policy.id(), claim ? State.CLAIM_PENDING : State.CLOSING, false, now);
        return advance(store.find(policy.id()).orElseThrow(), now);
    }

    /** The cargo caller remains responsible for its nation/warehouse authorization and cancellation. */
    public Policy cancel(Shipment shipment, UUID actor, boolean authorizedManager, long now) throws SQLException {
        requireCancellableCargo(shipment);
        return cancel(shipment.id(), actor, authorizedManager, now);
    }

    /** Check before releasing insurance money: cargo rejection must not alter an earned claim. */
    public static void requireCancellableCargo(Shipment shipment) {
        Objects.requireNonNull(shipment);
        require(shipment.state() == TransportState.OPEN || shipment.state() == TransportState.IN_TRANSIT,
                "运输已进入结算或已关闭，不能取消运输或退保");
    }

    /** Use the Shipment overload when the cargo ledger is available. */
    public Policy cancel(UUID shipment, UUID actor, boolean authorizedManager, long now) throws SQLException {
        Policy policy = policy(shipment).orElseThrow(() -> new IllegalArgumentException("运输没有保险"));
        require(actor.equals(policy.carrierId()) || authorizedManager, "无权退保");
        if (policy.terminal()) return policy;
        require(policy.state() == State.ACTIVE && !policy.review(), "保险正在结算或待核验，不能退保");
        store.state(policy.id(), State.CLOSING, false, now); // Premium was earned once coverage activated.
        return advance(store.find(policy.id()).orElseThrow(), now);
    }

    public void resume(long now) throws SQLException {
        for (Policy policy : store.pending()) advance(policy, now);
    }

    /** An insured posting cannot reserve national collateral forever without departure. */
    public Policy expireUnstarted(Shipment shipment, long now, long maximumWaitMillis) throws SQLException {
        require(maximumWaitMillis >= 10_000 && maximumWaitMillis <= 86_400_000,
                "Insurance departure timeout must be 10 seconds to 24 hours");
        Policy policy = policy(shipment.id()).orElseThrow(() -> new IllegalArgumentException("运输没有保险"));
        if (policy.state() != State.ACTIVE || shipment.state() != TransportState.OPEN
                || policy.activatedAt() == 0 || now < policy.activatedAt()
                || now - policy.activatedAt() < maximumWaitMillis) return policy;
        store.state(policy.id(), State.CLOSING, false, now);
        return advance(store.find(policy.id()).orElseThrow(), now);
    }

    public Policy reconcile(UUID id, MoneyLeg leg, boolean applied, long now) throws SQLException {
        Policy policy = store.find(id).orElseThrow(() -> new IllegalArgumentException("保险不存在"));
        MoneyState current = policy.money().get(leg);
        require(current == MoneyState.REVIEW || current == MoneyState.STARTED, "该保险经济阶段不需要人工核对");
        MoneyResult result = economy.reconcile(store.operation(policy, leg), applied);
        saveResult(policy.id(), leg, result, now);
        return advance(store.find(id).orElseThrow(), now);
    }

    private Policy advance(Policy initial, long now) throws SQLException {
        Policy policy = initial;
        if (policy.review() || policy.terminal()) return policy;
        if (policy.state() == State.INSURING) {
            MoneyResult reserved = leg(policy, MoneyLeg.RESERVE, now);
            if (reserved == MoneyResult.REJECTED) {
                store.state(policy.id(), State.CLOSED, false, now);
                return store.find(policy.id()).orElseThrow();
            }
            if (reserved != MoneyResult.APPLIED) return store.find(policy.id()).orElseThrow();
            MoneyResult debit = leg(policy, MoneyLeg.PREMIUM_DEBIT, now);
            if (debit == MoneyResult.REJECTED) {
                store.state(policy.id(), State.CLOSING, false, now);
            } else if (debit != MoneyResult.APPLIED) return store.find(policy.id()).orElseThrow();
            else {
                MoneyResult credit = leg(policy, MoneyLeg.PREMIUM_CREDIT, now);
                if (credit == MoneyResult.REJECTED) store.state(policy.id(), State.CLOSING, true, now);
                else if (credit != MoneyResult.APPLIED) return store.find(policy.id()).orElseThrow();
                else store.state(policy.id(), State.ACTIVE, false, now);
            }
            policy = store.find(policy.id()).orElseThrow();
        }
        if (policy.state() == State.CLAIM_PENDING) {
            MoneyResult result = leg(policy, MoneyLeg.CLAIM, now);
            if (result == MoneyResult.APPLIED) store.state(policy.id(), State.CLAIMED, false, now);
            else if (result == MoneyResult.REJECTED) {
                // A reserved valid claim must not silently vanish because someone refunded its reserve.
                store.money(policy.id(), MoneyLeg.CLAIM, MoneyState.REVIEW, now);
            }
        } else if (policy.state() == State.CLOSING) {
            if (policy.refundPremium()) {
                MoneyResult refund = leg(policy, MoneyLeg.PREMIUM_REFUND, now);
                if (refund == MoneyResult.REJECTED) {
                    store.money(policy.id(), MoneyLeg.PREMIUM_REFUND, MoneyState.REVIEW, now);
                }
                if (refund != MoneyResult.APPLIED) return store.find(policy.id()).orElseThrow();
            }
            MoneyResult released = leg(policy, MoneyLeg.RELEASE, now);
            if (released == MoneyResult.APPLIED) {
                store.state(policy.id(), State.CLOSED, policy.refundPremium(), now);
            } else if (released == MoneyResult.REJECTED) {
                store.money(policy.id(), MoneyLeg.RELEASE, MoneyState.REVIEW, now);
            }
        }
        return store.find(policy.id()).orElseThrow();
    }

    private MoneyResult leg(Policy policy, MoneyLeg leg, long now) throws SQLException {
        MoneyState current = store.find(policy.id()).orElseThrow().money().get(leg);
        if (current == MoneyState.APPLIED) return MoneyResult.APPLIED;
        if (current == MoneyState.REJECTED) return MoneyResult.REJECTED;
        if (current == MoneyState.REVIEW) return MoneyResult.REVIEW;
        store.money(policy.id(), leg, MoneyState.STARTED, now);
        MoneyResult result;
        try { result = economy.execute(store.operation(policy, leg)); }
        catch (RuntimeException ambiguous) { result = MoneyResult.REVIEW; }
        saveResult(policy.id(), leg, result, now);
        return result;
    }

    private void saveResult(UUID id, MoneyLeg leg, MoneyResult result, long now) throws SQLException {
        store.money(id, leg, switch (result) {
            case APPLIED -> MoneyState.APPLIED;
            case REJECTED -> MoneyState.REJECTED;
            case RETRY -> MoneyState.PENDING;
            case REVIEW -> MoneyState.REVIEW;
        }, now);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
