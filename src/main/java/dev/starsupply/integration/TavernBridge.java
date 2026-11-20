package dev.starsupply.integration;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional, read-only adapter for SenLuo Tavern's public catalog and bottle quality API. */
public final class TavernBridge {
    private final JavaPlugin owner;
    private final Plugin tavern;
    private final Plugin engine;
    private final Method itemId;
    private final Method asString;
    private final Method brewLevel;
    private final Set<String> beverages;
    private final AtomicBoolean warned = new AtomicBoolean();

    private TavernBridge(JavaPlugin owner, Plugin tavern, Plugin engine, Method itemId, Method asString,
                         Method brewLevel, Set<String> beverages) {
        this.owner = owner;
        this.tavern = tavern;
        this.engine = engine;
        this.itemId = itemId;
        this.asString = asString;
        this.brewLevel = brewLevel;
        this.beverages = Set.copyOf(beverages);
    }

    public static TavernBridge bind(JavaPlugin owner) {
        Plugin tavern = owner.getServer().getPluginManager().getPlugin("KaleidoscopeTavernPlugin");
        Plugin engine = owner.getServer().getPluginManager().getPlugin("CraftEngine");
        if (tavern == null || engine == null || !tavern.isEnabled() || !engine.isEnabled()) return null;
        try {
            Class<?> api = engine.getClass().getClassLoader()
                    .loadClass("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
            Method id = api.getMethod("getCustomItemId", ItemStack.class);
            Method level = tavern.getClass().getMethod("brewLevel", ItemStack.class);
            Object catalog = tavern.getClass().getMethod("catalog").invoke(tavern);
            Object recipes = catalog.getClass().getMethod("barrel").invoke(catalog);
            Set<String> beverages = new HashSet<>();
            for (Object recipe : (Iterable<?>) recipes) {
                beverages.add((String) recipe.getClass().getMethod("output").invoke(recipe));
            }
            return new TavernBridge(owner, tavern, engine, id, id.getReturnType().getMethod("asString"),
                    level, beverages);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            owner.getLogger().warning("森罗酒馆 API 不可用，真实酒品验证停用: " + failure);
            return null;
        }
    }

    /** Call on the scheduler owning this stack. No item, quality or amount is modified. */
    public boolean authenticBeverage(ItemStack stack, int minimumLevel) {
        if (!tavern.isEnabled() || !engine.isEnabled() || stack == null || stack.getType().isAir()) return false;
        try {
            Object key = itemId.invoke(null, stack);
            if (key == null || !beverages.contains((String) asString.invoke(key))) return false;
            return ((Number) brewLevel.invoke(tavern, stack)).intValue() >= minimumLevel;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (warned.compareAndSet(false, true)) owner.getLogger().warning("拒绝无法验证的 Tavern 酒品: " + failure);
            return false;
        }
    }
}
