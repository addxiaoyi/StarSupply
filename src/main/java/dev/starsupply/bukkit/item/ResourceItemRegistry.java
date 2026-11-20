package dev.starsupply.bukkit.item;

import dev.starsupply.api.ResourceType;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Item-to-resource registration. Read stacks on the scheduler owning their inventory. */
public final class ResourceItemRegistry {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final int MAX_UNITS_PER_ITEM = 1_000;
    private static final String COOKERY_NAMESPACE = "kaleidoscopecookery:";

    public record Match(String itemId, ResourceType resource, int unitsPerItem, String quality, double qualityRatio) {}

    private record Rule(ResourceType resource, int units, boolean cookeryQualityRequired, double minimumQuality) {
        private Rule {
            Objects.requireNonNull(resource, "resource");
            if (units < 1 || units > MAX_UNITS_PER_ITEM) throw new IllegalArgumentException("物资换算量须在 1–1000 内");
            if (!Double.isFinite(minimumQuality) || minimumQuality < 0 || minimumQuality > 2) {
                throw new IllegalArgumentException("最低品质倍率须在 0–2 内");
            }
        }
    }

    private record Quality(String name, double ratio) {
        private static final Quality BASE = new Quality("BASE", 1);
    }

    private final Map<Material, Rule> vanilla = new ConcurrentHashMap<>();
    private final Map<String, Rule> custom = new ConcurrentHashMap<>();
    private final EngineItems engine;
    private final CookeryFood cookery;

    public ResourceItemRegistry(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        engine = EngineItems.bind(plugin.getServer().getPluginManager().getPlugin("CraftEngine"), plugin.getLogger());
        cookery = CookeryFood.bind(plugin.getServer().getPluginManager().getPlugin("KaleidoscopeCookeryPlugin"),
                plugin.getLogger());
        registerVanilla(Material.BREAD, ResourceType.FOOD, 1);
        registerVanilla(Material.HONEY_BOTTLE, ResourceType.MEDICAL, 1);
        registerVanilla(Material.IRON_INGOT, ResourceType.EQUIPMENT, 1);
        registerVanilla(Material.COBBLESTONE, ResourceType.MATERIALS, 1);
        registerVanilla(Material.SCAFFOLDING, ResourceType.ENGINEERING, 1);
        registerVanilla(Material.FIREWORK_ROCKET, ResourceType.STRATEGIC, 1);
    }

    /** Registers or replaces the mapping of plain vanilla stacks of the given material. */
    public void registerVanilla(Material material, ResourceType resource, int units) {
        Objects.requireNonNull(material, "material");
        if (material.isLegacy() || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("无效的原版物品 " + material);
        }
        vanilla.put(material, new Rule(resource, units, false, 0));
    }

    public void registerCustom(String itemId, ResourceType resource, int units) {
        registerCustom(itemId, resource, units, false, 0);
    }

    /** Registers or replaces a CraftEngine item by its canonical namespaced ID. */
    public void registerCustom(String itemId, ResourceType resource, int units,
                               boolean requireCookeryQuality, double minQuality) {
        validateId(itemId);
        if (itemId.startsWith("minecraft:")) throw new IllegalArgumentException("原版物品请使用 registerVanilla");
        if ((requireCookeryQuality || minQuality > 0) && !itemId.startsWith(COOKERY_NAMESPACE)) {
            throw new IllegalArgumentException("Cookery 品质仅适用于 " + COOKERY_NAMESPACE + " 物品");
        }
        custom.put(itemId, new Rule(resource, units, requireCookeryQuality, minQuality));
    }

    /** Loads optional rules from the top-level resource-mappings list. */
    public void load(ConfigurationSection root) {
        Objects.requireNonNull(root, "root");
        Object configured = root.get("resource-mappings");
        if (configured == null) return;
        if (!(configured instanceof java.util.List<?> rules)) throw new IllegalArgumentException("resource-mappings 必须为列表");
        for (Object value : rules) {
            if (!(value instanceof Map<?, ?> rule)) throw new IllegalArgumentException("resource-mappings 包含非映射条目");
            String id = requireString(rule, "item");
            validateId(id);
            ResourceType resource = ResourceType.valueOf(requireString(rule, "resource").toUpperCase(Locale.ROOT));
            Object rate = rule.containsKey("units") ? rule.get("units") : 1;
            if (!(rate instanceof Number units) || units.doubleValue() != units.intValue()) {
                throw new IllegalArgumentException("物资换算量须是整数: " + id);
            }
            Object qualityFlag = rule.containsKey("cookery-quality-required") ? rule.get("cookery-quality-required") : false;
            Object minQuality = rule.containsKey("minimum-quality") ? rule.get("minimum-quality") : 0.0;
            if (!(qualityFlag instanceof Boolean required) || !(minQuality instanceof Number minimum)) {
                throw new IllegalArgumentException("无效的品质配置: " + id);
            }
            if (id.startsWith("minecraft:")) {
                Material material = Material.matchMaterial(id);
                if (material == null || required || minimum.doubleValue() != 0) {
                    throw new IllegalArgumentException("无效的原版物品配置: " + id);
                }
                registerVanilla(material, resource, units.intValue());
            } else {
                registerCustom(id, resource, units.intValue(), required, minimum.doubleValue());
            }
        }
    }

    public Optional<Match> identify(ItemStack item) {
        if (item == null || item.getType().isAir() || item.getAmount() < 1
                || item.getAmount() > item.getMaxStackSize() || item.getAmount() > 99) return Optional.empty();

        // Compare the complete component set so a vanilla-looking custom stack cannot enter via a vanilla rule.
        Rule vanillaRule = vanilla.get(item.getType());
        if (vanillaRule != null && !item.hasItemMeta() && item.isSimilar(new ItemStack(item.getType()))) {
            return Optional.of(match(item.getType().getKey().toString(), vanillaRule, Quality.BASE));
        }
        if (engine == null || custom.isEmpty()) return Optional.empty();
        try {
            String id = engine.id(item);
            if (id == null) return Optional.empty();
            Rule rule = custom.get(id);
            if (rule == null) return Optional.empty();
            Quality quality = id.startsWith(COOKERY_NAMESPACE) && cookery != null ? cookery.quality(item, id) : null;
            if (quality == null && (rule.cookeryQualityRequired() || rule.minimumQuality() > 0)) return Optional.empty();
            if (quality == null) quality = Quality.BASE;
            if (quality.ratio() < rule.minimumQuality()) return Optional.empty();
            return Optional.of(match(id, rule, quality));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            engine.reject(failure);
            return Optional.empty();
        }
    }

    private static Match match(String id, Rule rule, Quality quality) {
        return new Match(id, rule.resource(), rule.units(), quality.name(), quality.ratio());
    }

    private static void validateId(String id) {
        if (id == null || id.length() > 150 || !IDENTIFIER.matcher(id).matches()) {
            throw new IllegalArgumentException("物品 ID 须使用 namespace:path 格式");
        }
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) throw new IllegalArgumentException("缺少配置字段 " + key);
        return string;
    }

    private record EngineItems(Plugin plugin, Method getId, Method asString, Logger logger, AtomicBoolean warned) {
        static EngineItems bind(Plugin plugin, Logger logger) {
            if (plugin == null || !plugin.isEnabled()) return null;
            try {
                Class<?> api = plugin.getClass().getClassLoader().loadClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
                Method getId = api.getMethod("getCustomItemId", ItemStack.class);
                return new EngineItems(plugin, getId, getId.getReturnType().getMethod("asString"), logger,
                        new AtomicBoolean());
            } catch (ReflectiveOperationException | LinkageError failure) {
                logger.warning("CraftEngine 物品 API 不可用，自定义物品收购停用: " + failure);
                return null;
            }
        }

        String id(ItemStack stack) throws ReflectiveOperationException {
            if (!plugin.isEnabled()) return null;
            Object key = getId.invoke(null, stack);
            return key == null ? null : (String) asString.invoke(key);
        }

        void reject(Throwable failure) {
            if (warned.compareAndSet(false, true)) logger.warning("拒绝无法验证的自定义物品: " + failure);
        }
    }

    private record CookeryFood(Plugin plugin, Method read, Method itemId, Method quality, Method name, Method ratio) {
        static CookeryFood bind(Plugin plugin, Logger logger) {
            if (plugin == null || !plugin.isEnabled()) return null;
            try {
                Class<?> api = plugin.getClass().getClassLoader().loadClass("net.kaleidoscope.cookery.item.CookeryFoodItems");
                Method read = api.getMethod("read", ItemStack.class);
                Method quality = read.getReturnType().getMethod("quality");
                return new CookeryFood(plugin, read, read.getReturnType().getMethod("itemId"), quality,
                        quality.getReturnType().getMethod("name"), quality.getReturnType().getMethod("ratio"));
            } catch (ReflectiveOperationException | LinkageError failure) {
                logger.warning("Cookery 品质 API 不可用，需要品质验证的收购停用: " + failure);
                return null;
            }
        }

        Quality quality(ItemStack stack, String expectedId) throws ReflectiveOperationException {
            if (!plugin.isEnabled()) return null;
            Object food = read.invoke(null, stack);
            if (food == null) return null;
            if (!expectedId.equals(itemId.invoke(food))) throw new IllegalArgumentException("Cookery 物品 ID 与品质数据不一致");
            Object profile = quality.invoke(food);
            String label = (String) name.invoke(profile);
            double multiplier = (double) ratio.invoke(profile);
            if (label == null || !label.matches("[A-Z_]{1,32}") || !Double.isFinite(multiplier)
                    || multiplier <= 0 || multiplier > 2) throw new IllegalArgumentException("无效的 Cookery 品质数据");
            return new Quality(label, multiplier);
        }
    }
}
