package local.test;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.core.module.ModuleDescriptor;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TaxationService;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starcore.starcore.module.treasury.TreasuryService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/** ISOLATED TEST SERVER ONLY. Delegates public authorities; no production plugin hooks or DB writes. */
public final class SupplyFaultBootstrap extends JavaPlugin {
    private enum Mode { TAX_BEFORE, TAX_AFTER, PREMIUM_BEFORE, PREMIUM_AFTER, SPEND_RETRY }
    private record Armed(Mode mode, UUID nation, BigDecimal amount) {}
    private record SpendingFollowup(String transactionId, UUID nation, BigDecimal amount) {}

    private final AtomicReference<Armed> armed = new AtomicReference<>();
    private final AtomicReference<SpendingFollowup> spendingFollowup = new AtomicReference<>();
    private StarCoreApi original;
    private TreasuryService treasury;
    private TaxationService taxation;
    private NationSpendingService spending;
    private EconomyService economy;
    private TaxationService taxProxy;
    private TreasuryService treasuryProxy;
    private NationSpendingService spendingProxy;
    private Path evidence;

    @Override public void onEnable() {
        try {
            evidence = getDataFolder().toPath().resolve("fault-evidence.jsonl");
            Files.createDirectories(evidence.getParent());
            original = Objects.requireNonNull(getServer().getServicesManager().load(StarCoreApi.class),
                    "StarCoreApi must exist before test bootstrap");
            treasury = original.service(TreasuryService.class).orElseThrow();
            taxation = original.service(TaxationService.class).orElseThrow();
            spending = original.service(NationSpendingService.class).orElseThrow();
            economy = original.service(EconomyService.class).orElseThrow();
            taxProxy = (TaxationService) Proxy.newProxyInstance(TaxationService.class.getClassLoader(),
                    new Class<?>[]{TaxationService.class}, (proxy, method, args) -> taxCall(method, args));
            treasuryProxy = (TreasuryService) Proxy.newProxyInstance(TreasuryService.class.getClassLoader(),
                    new Class<?>[]{TreasuryService.class}, (proxy, method, args) -> treasuryCall(method, args));
            spendingProxy = (NationSpendingService) Proxy.newProxyInstance(
                    NationSpendingService.class.getClassLoader(), new Class<?>[]{NationSpendingService.class},
                    (proxy, method, args) -> spendingCall(method, args));
            StarCoreApi wrapped = new StarCoreApi() {
                @Override public String version() { return original.version(); }
                @Override public Collection<ModuleDescriptor> modules() { return original.modules(); }
                @Override public <T> Optional<T> service(Class<T> type) {
                    if (type == TaxationService.class) return Optional.of(type.cast(taxProxy));
                    if (type == TreasuryService.class) return Optional.of(type.cast(treasuryProxy));
                    if (type == NationSpendingService.class) return Optional.of(type.cast(spendingProxy));
                    return original.service(type);
                }
            };
            getServer().getServicesManager().register(StarCoreApi.class, wrapped, this, ServicePriority.Highest);
            record(Map.of("event", "bootstrap_enabled",
                    "wrapped", "TaxationService,TreasuryService,NationSpendingService",
                    "note", "isolated acceptance harness; faults disarmed after restart"));
            getLogger().warning("ISOLATED TEST HARNESS ACTIVE; remove before any production deployment");
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Cannot enable isolated fault bootstrap", failure);
            getServer().getServicesManager().unregisterAll(this);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        armed.set(null);
        getServer().getServicesManager().unregisterAll(this);
    }

    private Object taxCall(Method method, Object[] args) throws Throwable {
        if (!method.getName().equals("fundTax") || args == null || args.length != 1
                || !(args[0] instanceof TaxationService.TaxQuote quote)) return invoke(taxation, method, args);
        Armed fault = take(true, quote.nationId().value(), quote.taxAmount());
        if (fault == null) return invoke(taxation, method, args);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("method", "TaxationService.fundTax");
        facts.put("settlement", quote.transactionId().toString());
        facts.put("nation", quote.nationId().value().toString());
        facts.put("payer", quote.payerId().toString());
        facts.put("gross", quote.taxableAmount().toPlainString());
        facts.put("amount", quote.taxAmount().toPlainString());
        facts.put("tax_type", quote.type().name());
        return faultCall(taxation, method, args, fault, facts, quote.nationId());
    }

    private Object treasuryCall(Method method, Object[] args) throws Throwable {
        if (!method.getName().equals("tryDeposit") || args == null || args.length != 2
                || !(args[0] instanceof NationId nation) || !(args[1] instanceof BigDecimal amount)) {
            return invoke(treasury, method, args);
        }
        Armed fault = take(false, nation.value(), amount);
        if (fault == null) return invoke(treasury, method, args);
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("method", "TreasuryService.tryDeposit");
        facts.put("nation", nation.value().toString());
        facts.put("amount", amount.toPlainString());
        return faultCall(treasury, method, args, fault, facts, nation);
    }

    private Object spendingCall(Method method, Object[] args) throws Throwable {
        if (!method.getName().equals("spend") || args == null || args.length != 1
                || !(args[0] instanceof NationSpendingService.SpendRequest request)) {
            return invoke(spending, method, args);
        }
        Armed fault = takeSpending(request);
        if (fault == null) return observeSpendingRetry(method, args, request);

        BigDecimal balanceBefore = treasury.balance(request.nationId());
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("event", "spending_result_injected");
        facts.put("method", "NationSpendingService.spend");
        facts.put("mode", fault.mode().name());
        facts.put("transaction_id", request.transactionId());
        facts.put("nation", request.nationId().value().toString());
        facts.put("amount", request.amount().toPlainString());
        facts.put("category", request.category().name());
        facts.put("before_balance", balanceBefore.toPlainString());
        facts.put("after_balance", balanceBefore.toPlainString());
        facts.put("delegate_called", "false");
        if (balanceBefore.compareTo(request.amount()) < 0) {
            facts.put("event", "fault_rejected_insufficient_live_balance");
            record(facts);
            return invoke(spending, method, args);
        }
        facts.put("returned_status", NationSpendingService.SpendingStatus.INSUFFICIENT_FUNDS.name());
        facts.put("returned_balance_after", balanceBefore.toPlainString());
        record(facts);
        spendingFollowup.set(new SpendingFollowup(request.transactionId(), request.nationId().value(),
                request.amount()));
        return new NationSpendingService.SpendResult(NationSpendingService.SpendingStatus.INSUFFICIENT_FUNDS,
                request.transactionId(), balanceBefore);
    }

    private Object observeSpendingRetry(Method method, Object[] args,
                                        NationSpendingService.SpendRequest request) throws Throwable {
        SpendingFollowup pending = spendingFollowup.get();
        if (pending == null || !pending.transactionId().equals(request.transactionId())
                || !pending.nation().equals(request.nationId().value())
                || pending.amount().compareTo(request.amount()) != 0) return invoke(spending, method, args);
        BigDecimal balanceBefore = treasury.balance(request.nationId());
        try {
            NationSpendingService.SpendResult result = (NationSpendingService.SpendResult) invoke(spending, method, args);
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("event", "spending_retry_delegate_result");
            facts.put("method", "NationSpendingService.spend");
            facts.put("transaction_id", request.transactionId());
            facts.put("nation", request.nationId().value().toString());
            facts.put("amount", request.amount().toPlainString());
            facts.put("balance_before", balanceBefore.toPlainString());
            facts.put("balance_after", treasury.balance(request.nationId()).toPlainString());
            facts.put("delegate_called", "true");
            facts.put("delegate_status", result.status().name());
            facts.put("delegate_transaction_id", result.transactionId());
            facts.put("delegate_balance_after", String.valueOf(result.balanceAfter()));
            record(facts);
            spendingFollowup.compareAndSet(pending, null);
            return result;
        } catch (Throwable failure) {
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("event", "spending_retry_delegate_failed");
            facts.put("method", "NationSpendingService.spend");
            facts.put("transaction_id", request.transactionId());
            facts.put("nation", request.nationId().value().toString());
            facts.put("amount", request.amount().toPlainString());
            facts.put("balance_before", balanceBefore.toPlainString());
            facts.put("balance_after", treasury.balance(request.nationId()).toPlainString());
            facts.put("delegate_called", "true");
            facts.put("error_type", failure.getClass().getName());
            record(facts);
            throw failure;
        }
    }

    private Armed take(boolean tax, UUID nation, BigDecimal amount) {
        Armed fault = armed.get();
        boolean taxMode = fault != null && (fault.mode() == Mode.TAX_BEFORE || fault.mode() == Mode.TAX_AFTER);
        boolean premiumMode = fault != null
                && (fault.mode() == Mode.PREMIUM_BEFORE || fault.mode() == Mode.PREMIUM_AFTER);
        if (fault == null || (tax ? !taxMode : !premiumMode)
                || fault.nation() != null && !fault.nation().equals(nation)
                || fault.amount() != null && fault.amount().compareTo(amount) != 0) return null;
        return armed.compareAndSet(fault, null) ? fault : null;
    }

    private Armed takeSpending(NationSpendingService.SpendRequest request) {
        Armed fault = armed.get();
        if (fault == null || fault.mode() != Mode.SPEND_RETRY
                || !fault.nation().equals(request.nationId().value())
                || fault.amount().compareTo(request.amount()) != 0) return null;
        return armed.compareAndSet(fault, null) ? fault : null;
    }

    private Object faultCall(Object delegate, Method method, Object[] args, Armed fault,
                             Map<String, String> facts, NationId nation) throws Throwable {
        facts.put("mode", fault.mode().name());
        facts.put("before_balance", balance(nation));
        boolean before = fault.mode() == Mode.TAX_BEFORE || fault.mode() == Mode.PREMIUM_BEFORE;
        if (before) {
            facts.put("event", "fault_before_delegate");
            facts.put("delegate_called", "false");
            record(facts);
            throw new IllegalStateException("Injected isolated acceptance failure BEFORE " + facts.get("method"));
        }
        Object result;
        try {
            result = invoke(delegate, method, args);
        } catch (Throwable failure) {
            facts.put("event", "delegate_failed");
            facts.put("delegate_called", "true");
            facts.put("error_type", failure.getClass().getName());
            facts.put("after_balance", balance(nation));
            record(facts);
            throw failure;
        }
        facts.put("event", "fault_after_delegate");
        facts.put("delegate_called", "true");
        facts.put("delegate_result", String.valueOf(result));
        facts.put("after_balance", balance(nation));
        record(facts);
        throw new IllegalStateException("Injected isolated acceptance failure AFTER " + facts.get("method"));
    }

    private String balance(NationId nation) {
        try { return treasury.balance(nation).toPlainString(); }
        catch (RuntimeException failure) { return "UNAVAILABLE:" + failure.getClass().getSimpleName(); }
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player) {
            sender.sendMessage("Isolated test faults are console/RCON only.");
            return true;
        }
        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
                sender.sendMessage("SUPPLYFAULT " + (armed.get() == null ? "OFF" : armed.get())
                        + " evidence=" + evidence.getFileName());
                return true;
            }
            if (args[0].equalsIgnoreCase("off")) {
                armed.set(null);
                record(Map.of("event", "disarmed"));
                sender.sendMessage("SUPPLYFAULT OFF");
                return true;
            }
            if (args[0].equalsIgnoreCase("balances") && args.length == 3) {
                NationId nation = NationId.of(UUID.fromString(args[1]));
                UUID player = UUID.fromString(args[2]);
                sender.sendMessage("SUPPLYFAULT BALANCES nation=" + nation.value()
                        + " treasury=" + treasury.balance(nation).toPlainString()
                        + " player=" + player + " playerBalance=" + economy.getBalance(player).toPlainString());
                return true;
            }
            if (args[0].equalsIgnoreCase("receipt") && args.length == 2) {
                NationSpendingService.SpendingReceipt receipt = spending.spendingReceipt(args[1]).orElse(null);
                sender.sendMessage("SUPPLYFAULT RECEIPT " + (receipt == null ? "ABSENT"
                        : receipt.transactionId() + " nation=" + receipt.nationId().value()
                        + " amount=" + receipt.amount().toPlainString() + " refunded=" + receipt.refunded()));
                return true;
            }
            Mode mode = Mode.valueOf(args[0].toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
            UUID nation = args.length >= 2 ? UUID.fromString(args[1]) : null;
            BigDecimal amount = args.length >= 3 ? new BigDecimal(args[2]).setScale(2,
                    java.math.RoundingMode.UNNECESSARY) : null;
            boolean exactFilterRequired = mode == Mode.PREMIUM_BEFORE || mode == Mode.PREMIUM_AFTER
                    || mode == Mode.SPEND_RETRY;
            if (args.length > 3 || (amount != null && amount.signum() <= 0)
                    || exactFilterRequired && (nation == null || amount == null)) {
                throw new IllegalArgumentException("Premium and spending faults require exact nation UUID and positive amount");
            }
            Armed next = new Armed(mode, nation, amount);
            if (!armed.compareAndSet(null, next)) throw new IllegalArgumentException("A fault is already armed; off first");
            record(Map.of("event", "armed", "mode", mode.name(), "nation", String.valueOf(nation),
                    "amount", String.valueOf(amount)));
            sender.sendMessage("SUPPLYFAULT ARMED ONCE " + next);
        } catch (Exception failure) {
            sender.sendMessage("SUPPLYFAULT ERROR " + failure.getMessage()
                    + " | usage: supplyfault tax-before|tax-after [nationUUID] [taxAmount]"
                    + " OR premium-before|premium-after <nationUUID> <premiumAmount>"
                    + " OR spend-retry <nationUUID> <amount>"
                    + " OR balances <nationUUID> <playerUUID> OR receipt <transactionId> OR off|status");
        }
        return true;
    }

    private synchronized void record(Map<String, String> data) {
        Map<String, String> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
        line.putAll(data);
        String json = line.entrySet().stream().map(entry -> quote(entry.getKey()) + ":" + quote(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}\n"));
        try (FileChannel channel = FileChannel.open(evidence, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(json);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (Exception failure) {
            getLogger().log(Level.SEVERE, "Cannot persist independent fault evidence", failure);
        }
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (ch < 32) result.append(String.format("\\u%04x", (int) ch)); else result.append(ch);
                }
            }
        }
        return result.append('"').toString();
    }
}
