package dev.starsupply.bukkit.item;

import dev.starsupply.api.ResourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Physical inventory boundary. All methods taking a player require that player's region thread. */
public final class InventoryEscrow {
    private static final int STORAGE_SLOTS = 36;
    private static final int MAX_STACK = 99;
    private static final int MAX_STACK_BYTES = 128 * 1024;
    private static final String RECEIPT_NAME = "inventory_receipt_v1";

    private final ResourceItemRegistry items;
    private final NamespacedKey receiptKey;
    private final Path playerDataDirectory;

    public record Fingerprints(String canonical, String legacy) {
        public boolean matches(String value) {
            return canonical.equals(value) || legacy.equals(value);
        }
    }

    /** Construct during plugin enable, after the primary world has loaded. */
    public InventoryEscrow(JavaPlugin plugin, ResourceItemRegistry items) {
        this(items, new NamespacedKey(plugin, RECEIPT_NAME), plugin.getServer().getWorlds().getFirst()
                .getWorldFolder().toPath().toAbsolutePath().resolve("playerdata"));
    }

    InventoryEscrow(ResourceItemRegistry items, NamespacedKey receiptKey, Path playerDataDirectory) {
        this.items = Objects.requireNonNull(items, "items");
        this.receiptKey = Objects.requireNonNull(receiptKey, "receiptKey");
        this.playerDataDirectory = Objects.requireNonNull(playerDataDirectory, "playerDataDirectory");
    }

    public record EscrowLot(String itemId, String quality, double qualityRatio, int itemCount, int units, byte[] itemBytes) {
        public EscrowLot {
            if (itemId == null || itemId.length() > 150 || quality == null || !quality.matches("[A-Z_]{1,32}")
                    || !Double.isFinite(qualityRatio) || qualityRatio <= 0 || qualityRatio > 2
                    || itemCount < 1 || itemCount > MAX_STACK || units < itemCount || units > itemCount * 1_000
                    || itemBytes == null || itemBytes.length < 1 || itemBytes.length > MAX_STACK_BYTES) {
                throw new IllegalArgumentException("无效的托管物品批次");
            }
            itemBytes = itemBytes.clone();
        }

        @Override
        public byte[] itemBytes() {
            return itemBytes.clone();
        }
    }

    public static final class Plan {
        private final UUID playerId;
        private final ResourceType resource;
        private final int itemCount;
        private final int units;
        private final String beforeHash;
        private final String afterHash;
        private final List<EscrowLot> lots;
        private final ItemStack[] after;

        private Plan(UUID playerId, ResourceType resource, String beforeHash, ItemStack[] after, List<EscrowLot> lots) {
            this.playerId = playerId;
            this.resource = resource;
            this.lots = List.copyOf(lots);
            this.itemCount = this.lots.stream().mapToInt(EscrowLot::itemCount).sum();
            this.units = this.lots.stream().mapToInt(EscrowLot::units).sum();
            this.beforeHash = beforeHash;
            this.afterHash = fingerprint(after);
            this.after = after;
        }

        public UUID playerId() { return playerId; }
        public ResourceType resource() { return resource; }
        public int itemCount() { return itemCount; }
        public int units() { return units; }
        public String beforeHash() { return beforeHash; }
        public String afterHash() { return afterHash; }
        public List<EscrowLot> lots() { return lots; }
    }

    /** Snapshot a physical debit. Callers must persist the plan's receipt and lots before applying. */
    public Plan plan(Player player, ResourceType resource, int itemCount) {
        requireActivePlayer(player);
        Objects.requireNonNull(resource, "resource");
        if (itemCount < 1 || itemCount > STORAGE_SLOTS * MAX_STACK) throw new IllegalArgumentException("交货数量无效");

        ItemStack[] after = storageCopy(player);
        String beforeHash = fingerprint(after);
        List<EscrowLot> lots = new ArrayList<>();
        int remaining = itemCount;
        for (int slot = 0; slot < after.length && remaining > 0; slot++) {
            ItemStack source = after[slot];
            var match = items.identify(source).orElse(null);
            if (match == null || match.resource() != resource) continue;
            int count = Math.min(remaining, source.getAmount());
            byte[] bytes = withAmount(source, count).serializeAsBytes();
            if (bytes.length > MAX_STACK_BYTES) throw new IllegalArgumentException("物品数据超过托管大小限制");
            lots.add(new EscrowLot(match.itemId(), match.quality(), match.qualityRatio(), count,
                    Math.multiplyExact(count, match.unitsPerItem()), bytes));
            after[slot] = count == source.getAmount() ? null : withAmount(source, source.getAmount() - count);
            remaining -= count;
        }
        if (remaining != 0) throw new IllegalArgumentException("背包中可用的对应物资数量不足");
        return new Plan(player.getUniqueId(), resource, beforeHash, after, lots);
    }

    /** Plan the return of exact stored stacks. When space is insufficient the entire lot stays in escrow. */
    public Plan planRestore(Player player, ResourceType resource, List<EscrowLot> lots) {
        requireActivePlayer(player);
        Objects.requireNonNull(resource, "resource");
        List<EscrowLot> exactLots = List.copyOf(lots);
        if (exactLots.isEmpty() || exactLots.size() > STORAGE_SLOTS) {
            throw new IllegalArgumentException("托管批次数量无效");
        }

        ItemStack[] after = storageCopy(player);
        String beforeHash = fingerprint(after);
        int inventoryMax = Math.min(MAX_STACK, player.getInventory().getMaxStackSize());
        for (EscrowLot lot : exactLots) {
            ItemStack item = ItemStack.deserializeBytes(lot.itemBytes());
            if (empty(item) || item.getAmount() != lot.itemCount() || item.getAmount() > item.getMaxStackSize()) {
                throw new IllegalArgumentException("托管物品与已登记数量不符");
            }
            insert(after, item, Math.min(inventoryMax, item.getMaxStackSize()));
        }
        return new Plan(player.getUniqueId(), resource, beforeHash, after, exactLots);
    }

    private static void insert(ItemStack[] target, ItemStack item, int maxStackSize) {
        if (maxStackSize < 1) throw new IllegalArgumentException("背包堆叠上限无效");
        int remaining = item.getAmount();
        for (int slot = 0; slot < target.length && remaining > 0; slot++) {
            ItemStack current = target[slot];
            if (empty(current) || !current.isSimilar(item) || current.getAmount() >= maxStackSize) continue;
            int count = Math.min(remaining, maxStackSize - current.getAmount());
            target[slot] = withAmount(current, current.getAmount() + count);
            remaining -= count;
        }
        for (int slot = 0; slot < target.length && remaining > 0; slot++) {
            if (!empty(target[slot])) continue;
            int count = Math.min(remaining, maxStackSize);
            target[slot] = withAmount(item, count);
            remaining -= count;
        }
        if (remaining > 0) throw new IllegalArgumentException("背包空间不足，物资仍在托管中");
    }

    /**
     * Apply a prepared debit/return, save the inventory and receipt together, and verify the receipt on disk.
     * False means the snapshot was stale and inventory was untouched. An exception may leave it in doubt.
     */
    public boolean apply(Player player, UUID operation, Plan plan) {
        requireActivePlayer(player);
        Objects.requireNonNull(operation, "operation");
        if (!player.getUniqueId().equals(plan.playerId())) throw new IllegalArgumentException("托管计划的玩家不匹配");
        UUID current = receipt(player).orElse(null);
        if (current != null) {
            if (!operation.equals(current)) throw new IllegalStateException("该玩家仍有等待恢复的库存变更");
            persistReceipt(player, operation);
            return true;
        }
        if (!plan.beforeHash().equals(hash(player))) return false;
        player.closeInventory();
        // Closing an inventory may return grid contents or trigger another plugin's inventory listener.
        if (!plan.beforeHash().equals(hash(player))) return false;
        player.getInventory().setStorageContents(copy(plan.after));
        player.getPersistentDataContainer().set(receiptKey, PersistentDataType.STRING, operation.toString());
        persistReceipt(player, operation);
        return true;
    }

    /** Retry the player save for an existing receipt without applying any inventory change. */
    public void persistReceipt(Player player, UUID operation) {
        if (receipt(player).filter(operation::equals).isEmpty()) {
            throw new IllegalStateException("玩家没有这笔库存变更的托管回执");
        }
        player.saveData();
        if (!verifyReceipt(player, operation)) {
            throw new IllegalStateException("无法验证玩家存档中的托管回执，保留待恢复记录");
        }
    }

    public Optional<UUID> receipt(Player player) {
        requireOwningThread(player);
        String value = player.getPersistentDataContainer().get(receiptKey, PersistentDataType.STRING);
        return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
    }

    /** Confirm the exact operation still owns the receipt and is present in the current on-disk save. */
    public boolean verifyReceipt(Player player, UUID operation) {
        if (receipt(player).filter(operation::equals).isEmpty()) return false;
        Path save = playerDataDirectory.resolve(player.getUniqueId() + ".dat");
        try {
            return PlayerDataReceipt.verify(save, receiptKey.toString(), operation.toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取或同步玩家托管存档 " + player.getUniqueId(), failure);
        }
    }

    /** Clear an operation's marker only after StarSupply SQL has durably advanced past the inventory step. */
    public void clearReceipt(Player player, UUID operation) {
        if (receipt(player).filter(operation::equals).isPresent()) {
            player.getPersistentDataContainer().remove(receiptKey);
            player.saveData();
        }
    }

    public String hash(Player player) {
        requireOwningThread(player);
        return fingerprint(storageCopy(player));
    }

    public Fingerprints fingerprints(Player player) {
        requireOwningThread(player);
        ItemStack[] slots = storageCopy(player);
        return new Fingerprints(fingerprint(slots), legacyFingerprint(slots));
    }

    private static ItemStack[] storageCopy(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        if (storage.length != STORAGE_SLOTS) throw new IllegalStateException("不受支持的玩家背包布局");
        return copy(storage);
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int index = 0; index < items.length; index++) {
            if (!empty(items[index])) copy[index] = items[index].clone();
        }
        return copy;
    }

    private static ItemStack withAmount(ItemStack source, int amount) {
        ItemStack result = source.clone();
        result.setAmount(amount);
        return result;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() < 1;
    }

    private static String fingerprint(ItemStack[] slots) {
        Object[] serialized = new Object[slots.length];
        for (int index = 0; index < slots.length; index++) {
            if (!empty(slots[index])) serialized[index] = slots[index].serialize();
        }
        return InventoryFingerprint.hash(serialized);
    }

    private static String legacyFingerprint(ItemStack[] slots) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        ByteBuffer length = ByteBuffer.allocate(Integer.BYTES);
        for (ItemStack item : slots) {
            byte[] bytes = empty(item) ? null : item.serializeAsBytes();
            length.putInt(0, bytes == null ? 0 : bytes.length);
            digest.update(length.array());
            if (bytes != null) digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireOwningThread(Player player) {
        if (!Bukkit.isOwnedByCurrentRegion(player)) throw new IllegalStateException("库存操作必须在玩家所属线程执行");
    }

    private static void requireActivePlayer(Player player) {
        requireOwningThread(player);
        if (!player.isOnline() || player.isDead()) throw new IllegalStateException("玩家必须在线且存活");
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) {
            throw new IllegalArgumentException("只有生存或冒险模式可执行托管库存操作");
        }
    }
}
