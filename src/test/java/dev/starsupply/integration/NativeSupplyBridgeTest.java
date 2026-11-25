package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeSupplyBridgeTest {
    private static final UUID REQUEST = UUID.randomUUID();
    private static final UUID ARMY = UUID.randomUUID();
    private static final UUID NATION = UUID.randomUUID();

    @Test
    void requiresReceiptCapableCoreAndValidatesTheAuthorityResponse() {
        assertThrows(IllegalStateException.class, () -> NativeSupplyBridge.bind(new Object()));
        Authority authority = new Authority();
        NativeSupplyBridge bridge = NativeSupplyBridge.bind(authority);
        assertTrue(bridge.replenish(REQUEST, ARMY, NATION, 10));
        authority.receipt = new Receipt(UUID.randomUUID(), ARMY, NATION, 10, 10);
        assertThrows(IllegalStateException.class, () -> bridge.replenish(REQUEST, ARMY, NATION, 10));
        authority.receipt = new Receipt(REQUEST, ARMY, NATION, 10, 11);
        assertThrows(IllegalStateException.class, () -> bridge.replenish(REQUEST, ARMY, NATION, 10));
        authority.receipt = new Receipt(REQUEST, ARMY, NATION, 10, 0);
        assertTrue(bridge.replenish(REQUEST, ARMY, NATION, 10), "full native supply is still a confirmed receipt");
    }

    @Test
    void preservesFailureAndRequiresFreshPersistenceConfirmationOnEveryRetry() {
        Authority authority = new Authority();
        NativeSupplyBridge bridge = NativeSupplyBridge.bind(authority);
        authority.failure = new IllegalStateException("lost persistence confirmation");
        assertSame(authority.failure, assertThrows(IllegalStateException.class,
                () -> bridge.replenish(REQUEST, ARMY, NATION, 10)));
        authority.failure = null;
        assertTrue(bridge.replenish(REQUEST, ARMY, NATION, 10));
        assertEquals(2, authority.calls);
    }

    @Test
    void readsAndValidatesTheDurableReceiptForOperatorReconciliation() {
        Authority authority = new Authority();
        NativeSupplyBridge bridge = NativeSupplyBridge.bind(authority);
        assertTrue(bridge.receipt(REQUEST).isEmpty());
        authority.receipt = new Receipt(REQUEST, ARMY, NATION, 10, 7);
        var receipt = bridge.receipt(REQUEST).orElseThrow();
        assertEquals(REQUEST, receipt.requestId());
        assertEquals(ARMY, receipt.armyId());
        assertEquals(NATION, receipt.nationId());
        assertEquals(10, receipt.requestedPoints());
        assertEquals(7, receipt.appliedPoints());
        authority.receipt = new Receipt(UUID.randomUUID(), ARMY, NATION, 10, 7);
        assertThrows(IllegalStateException.class, () -> bridge.receipt(REQUEST));
    }

    public record Receipt(UUID requestId, UUID armyId, UUID nationId, int requestedPoints, int appliedPoints) {}

    public static final class Authority {
        Receipt receipt;
        RuntimeException failure;
        int calls;

        public Receipt applyLogisticsSupply(UUID request, UUID army, UUID nation, int points) {
            calls++;
            if (failure != null) throw failure;
            return receipt == null ? new Receipt(request, army, nation, points, points) : receipt;
        }

        public Optional<Receipt> logisticsSupplyReceipt(UUID request) { return Optional.ofNullable(receipt); }
    }
}
