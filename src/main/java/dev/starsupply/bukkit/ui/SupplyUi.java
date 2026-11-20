package dev.starsupply.bukkit.ui;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Commands and inventory views. All gameplay mutations are delegated to the application service. */
public final class SupplyUi implements CommandExecutor, TabCompleter, Listener {
    private static final String USE = "starsupply.use";
    private static final String MANAGE = "starsupply.manage";
    private static final String ADMIN = "starsupply.admin";
    private static final int PAGE_SIZE = 45;

    private final Plugin plugin;
    private final Actions actions;

    public SupplyUi(Plugin plugin, Actions actions) {
        this.plugin = Objects.requireNonNull(plugin);
        this.actions = Objects.requireNonNull(actions);
    }

    public interface Actions {
        void dispatch(Player player, String command, String[] args);

        /** Capture any player state synchronously, then load the authorized view asynchronously. */
        CompletionStage<List<Entry>> entries(Player player, Section section);
    }

    public enum Section {
        WAREHOUSES("仓库与库存", Material.CHEST, "查看本国仓库、前线站和储备"),
        ORDERS("采购与运输", Material.WRITABLE_BOOK, "查看可用合同及自己的订单"),
        ARMY("军队补给", Material.SHIELD, "查看军队需求、库存和作战系数"),
        ROUTES("补给路线", Material.MAP, "查看道路、封锁状态和运输成本"),
        TAVERNS("酒馆与情报", Material.CAMPFIRE, "商人集合点、实地侦察和有时效的情报板");

        private final String title;
        private final Material icon;
        private final String description;

        Section(String title, Material icon, String description) {
            this.title = title;
            this.icon = icon;
            this.description = description;
        }
    }

    public record Entry(Material icon, String title, List<String> lore, String command, List<String> arguments) {
        public Entry {
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(title, "title");
            if (!icon.isItem() || icon.isAir()) {
                throw new IllegalArgumentException("Menu entry needs an item icon");
            }
            lore = List.copyOf(lore);
            arguments = List.copyOf(arguments);
        }

        public Entry(Material icon, String title, List<String> lore) {
            this(icon, title, lore, null, List.of());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("StarSupply 命令需要在游戏内执行。");
            return true;
        }
        if (!player.hasPermission(USE)) {
            denied(player);
        } else if (args.length == 0) {
            openMenu(player);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            showHelp(player);
        } else {
            dispatch(player, args[0], Arrays.copyOfRange(args, 1, args.length));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission(USE)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(visibleCommands(sender).stream().map(Subcommand::name).toList(), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            return matching(List.of("purchase", "shipment"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("buy")) {
            return matching(List.of("food", "medical", "equipment", "materials", "engineering", "strategic"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("market")) {
            return matching(List.of("food", "medical", "equipment", "materials", "engineering", "strategic"), args[2]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("buy")) {
            return matching(List.of("market"), args[4]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("block") && sender.hasPermission(MANAGE)) {
            return matching(List.of("true", "false"), args[2]);
        }
        if (args.length == 3 && sender.hasPermission(ADMIN)) {
            if (args[0].equalsIgnoreCase("reconcile-insurance")) {
                return matching(List.of("reserve", "premium_debit", "premium_credit", "claim", "release", "premium_refund"), args[2]);
            }
            if (args[0].equalsIgnoreCase("reconcile-tax")) {
                return matching(List.of("funded", "unfunded"), args[2]);
            }
            if (args[0].equalsIgnoreCase("reconcile")) {
                return matching(List.of("paid", "unpaid"), args[2]);
            }
            if (args[0].equalsIgnoreCase("reconcile-inventory")) {
                return matching(List.of("applied", "untouched"), args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("reconcile-insurance") && sender.hasPermission(ADMIN)) {
            return matching(List.of("applied", "untouched"), args[3]);
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void guardClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof MenuPage page)) {
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (!(event.getWhoClicked() instanceof Player player) || event.getClick() != ClickType.LEFT
                || rawSlot < 0 || rawSlot >= top.getSize()) {
            return;
        }
        Runnable action = page.clicks[rawSlot];
        if (action != null) {
            // InventoryClickEvent has not finished applying its inventory mutation yet.
            player.getScheduler().run(plugin, task -> {
                if (player.getOpenInventory().getTopInventory() == top) {
                    if (player.hasPermission(USE)) {
                        action.run();
                    } else {
                        player.closeInventory();
                        denied(player);
                    }
                }
            }, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void guardDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuPage) {
            event.setCancelled(true);
        }
    }

    private void openMenu(Player player) {
        MenuPage menu = new MenuPage(27, "StarSupply · 战略后勤");
        int[] slots = {10, 11, 13, 15, 16};
        for (Section section : Section.values()) {
            menu.add(slots[section.ordinal()], section.icon, section.title, List.of(section.description),
                    () -> openSection(player, section));
        }
        menu.add(22, Material.BOOK, "命令帮助", List.of("在聊天栏查看操作命令"), () -> {
            player.closeInventory();
            showHelp(player);
        });
        player.openInventory(menu.inventory);
    }

    private void openSection(Player player, Section section) {
        MenuPage loading = new MenuPage(54, section.title);
        loading.add(22, Material.CLOCK, "正在读取…", List.of(), null);
        loading.add(49, Material.OAK_DOOR, "返回", List.of(), () -> openMenu(player));
        player.openInventory(loading.inventory);

        try {
            actions.entries(player, section).whenComplete((entries, failure) -> {
                player.getScheduler().run(plugin, task -> {
                    if (player.getOpenInventory().getTopInventory() != loading.inventory) {
                        return;
                    }
                    if (!player.hasPermission(USE)) {
                        player.closeInventory();
                        denied(player);
                    } else if (failure != null) {
                        showLoadFailure(player, loading, section, failure);
                    } else {
                        showEntries(player, section, List.copyOf(entries), 0);
                    }
                }, null);
            });
        } catch (RuntimeException failure) {
            showLoadFailure(player, loading, section, failure);
        }
    }

    private void showLoadFailure(Player player, MenuPage loading, Section section, Throwable failure) {
        plugin.getLogger().log(Level.WARNING, "StarSupply menu failed to load " + section, failure);
        loading.add(22, Material.BARRIER, "读取失败", List.of("点击刷新后重试"), null);
        loading.add(50, Material.CLOCK, "刷新", List.of(), () -> openSection(player, section));
    }

    private void showEntries(Player player, Section section, List<Entry> entries, int page) {
        int pages = entries.isEmpty() ? 1 : (entries.size() - 1) / PAGE_SIZE + 1;
        MenuPage menu = new MenuPage(54, section.title + " · " + (page + 1) + " / " + pages);
        int start = page * PAGE_SIZE;
        int count = Math.min(PAGE_SIZE, entries.size() - start);
        for (int index = 0; index < count; index++) {
            Entry entry = entries.get(start + index);
            Runnable action = entry.command() == null ? null : () -> {
                player.closeInventory();
                dispatch(player, entry.command(), entry.arguments().toArray(String[]::new));
            };
            menu.add(index, entry.icon(), entry.title(), entry.lore(), action);
        }
        if (entries.isEmpty()) {
            menu.add(22, Material.PAPER, "暂无数据", List.of(), null);
        }
        if (page > 0) {
            menu.add(45, Material.ARROW, "上一页", List.of(), () -> showEntries(player, section, entries, page - 1));
        }
        menu.add(49, Material.OAK_DOOR, "返回", List.of(), () -> openMenu(player));
        menu.add(50, Material.CLOCK, "刷新", List.of(), () -> openSection(player, section));
        if (page + 1 < pages) {
            menu.add(53, Material.ARROW, "下一页", List.of(), () -> showEntries(player, section, entries, page + 1));
        }
        player.openInventory(menu.inventory);
    }

    /** Actor-scheduler presentation for a physically authorized detached board snapshot. */
    public void present(Player player, Section section, List<Entry> entries) {
        if (!player.hasPermission(USE)) { denied(player); return; }
        showEntries(player, section, List.copyOf(entries), 0);
    }

    private void dispatch(Player player, String name, String[] args) {
        Subcommand command = visibleCommands(player).stream().filter(c -> c.name().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (command == null) {
            player.sendMessage(label("命令不可用。使用 /supply help 查看权限内的操作。", NamedTextColor.RED));
            return;
        }
        if (args.length < command.minimum() || args.length > command.maximum()) {
            player.sendMessage(label("用法 /supply " + command.usage(), NamedTextColor.YELLOW));
            return;
        }
        switch (command.name()) {
            case "help" -> showHelp(player);
            case "orders" -> openSection(player, Section.ORDERS);
            default -> actions.dispatch(player, command.name(), args);
        }
    }

    private static void showHelp(Player player) {
        player.sendMessage(label("StarSupply · 后勤操作", NamedTextColor.GOLD));
        for (Subcommand command : visibleCommands(player)) {
            if (!command.name().equals("help")) {
                player.sendMessage(label("/supply " + command.usage(), NamedTextColor.GRAY));
            }
        }
    }

    private static List<Subcommand> visibleCommands(CommandSender sender) {
        return COMMANDS.stream().filter(command -> command.permission() == null
                || sender.hasPermission(command.permission())).toList();
    }

    private static List<String> matching(List<String> candidates, String prefix) {
        String filter = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.startsWith(filter)).toList();
    }

    private static Component label(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static void denied(Player player) {
        player.sendMessage(label("你没有使用 StarSupply 的权限。", NamedTextColor.RED));
    }

    private static final List<Subcommand> COMMANDS = List.of(
            new Subcommand("help", "help", 0, 0, null),
            new Subcommand("depot", "depot <仓库ID>", 1, 1, MANAGE),
            new Subcommand("front", "front <前线站ID> <军队ID>", 2, 2, MANAGE),
            new Subcommand("route", "route <路线ID> <起点> <终点> <效率> [StarCore供给线ID]", 4, 5, MANAGE),
            new Subcommand("block", "block <路线ID> <true|false>", 2, 2, MANAGE),
            new Subcommand("buy", "buy <仓库ID> <物资类型> <数量> [总价|market]", 3, 4, MANAGE),
            new Subcommand("market", "market <仓库ID> <物资类型>", 2, 2, null),
            new Subcommand("sell", "sell <采购订单ID>", 1, 1, null),
            new Subcommand("ship", "ship <采购订单ID> <前线站ID> <运费>", 3, 3, MANAGE),
            new Subcommand("accept", "accept <运输订单ID>", 1, 1, null),
            new Subcommand("escort", "escort <运输订单ID>", 1, 1, null),
            new Subcommand("deliver", "deliver <运输订单ID>", 1, 1, null),
            new Subcommand("cancel", "cancel <purchase|shipment> <订单ID>", 2, 2, null),
            new Subcommand("recover", "recover [物品交接ID]", 0, 1, null),
            new Subcommand("status", "status [军队ID]", 0, 1, null),
            new Subcommand("tavern", "tavern <集合点ID>", 1, 1, MANAGE),
            new Subcommand("untavern", "untavern <集合点ID>", 1, 1, MANAGE),
            new Subcommand("scout", "scout <道路ID>", 1, 1, null),
            new Subcommand("intel", "intel <报告UUID> <酒馆集合点ID>", 2, 2, null),
            new Subcommand("board", "board <酒馆集合点ID>", 1, 1, null),
            new Subcommand("insurance", "insurance <运输UUID>", 1, 1, null),
            new Subcommand("insure", "insure <运输UUID>", 1, 1, null),
            new Subcommand("uninsure", "uninsure <运输UUID>", 1, 1, null),
            new Subcommand("orders", "orders", 0, 0, null),
            new Subcommand("reconcile", "reconcile <结算ID> <paid|unpaid>", 2, 2, ADMIN),
            new Subcommand("reconcile-tax", "reconcile-tax <关税结算ID> <funded|unfunded>", 2, 2, ADMIN),
            new Subcommand("reconcile-insurance", "reconcile-insurance <保单UUID> <经济阶段> <applied|untouched>",
                    3, 3, ADMIN),
            new Subcommand("reconcile-inventory", "reconcile-inventory <玩家库存收据ID> <applied|untouched>",
                    2, 2, ADMIN));

    private record Subcommand(String name, String usage, int minimum, int maximum, String permission) {}

    private static final class MenuPage implements InventoryHolder {
        private final Inventory inventory;
        private final Runnable[] clicks;

        private MenuPage(int size, String title) {
            clicks = new Runnable[size];
            inventory = Bukkit.createInventory(this, size, label(title, NamedTextColor.GOLD));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void add(int slot, Material icon, String title, List<String> lines, Runnable click) {
            ItemStack item = new ItemStack(icon);
            ItemMeta meta = item.getItemMeta();
            meta.displayName(label(title, click == null ? NamedTextColor.WHITE : NamedTextColor.AQUA));
            meta.lore(lines.stream().map(line -> label(line, NamedTextColor.GRAY)).toList());
            item.setItemMeta(meta);
            inventory.setItem(slot, item);
            clicks[slot] = click;
        }
    }
}
