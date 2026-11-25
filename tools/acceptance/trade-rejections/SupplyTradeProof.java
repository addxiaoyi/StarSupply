package local.test;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.resource.ResourceTradeService;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starcore.starcore.module.treasury.TreasuryService;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class SupplyTradeProof extends JavaPlugin {
    private StarCoreApi api;

    @Override
    public void onEnable() {
        api = Bukkit.getServicesManager().load(StarCoreApi.class);
        if (api == null) throw new IllegalStateException("StarCore API is unavailable");
        getCommand("supplytradeproof").setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !(sender instanceof RemoteConsoleCommandSender)) {
            sender.sendMessage("console only");
            return true;
        }
        try {
            NationService nations = api.service(NationService.class).orElseThrow();
            NationId importer = nations.nationByName("Alpha").orElseThrow().id();
            NationId exporter = nations.nationByName("Beta").orElseThrow().id();
            ResourceTradeService trades = api.service(ResourceTradeService.class).orElseThrow();
            switch (args[0]) {
                case "create" -> {
                    requireArgs(args, 4);
                    long seconds = positive(args[1]);
                    long units = positive(args[2]);
                    double price = new BigDecimal(args[3]).doubleValue();
                    var agreement = trades.createTradeAgreement(exporter, importer, "wheat", units, price, seconds);
                    sender.sendMessage("STARTRADE AGREEMENT " + agreement.agreementId()
                            + " active=" + agreement.isActive() + " units=" + agreement.amount()
                            + " price=" + agreement.pricePerUnit() + " seconds=" + seconds);
                }
                case "state" -> {
                    requireArgs(args, 2);
                    UUID id = UUID.fromString(args[1]);
                    var agreement = trades.getTradeAgreement(id);
                    sender.sendMessage("STARTRADE STATE " + id + " present=" + agreement.isPresent()
                            + " active=" + agreement.map(value -> value.isActive()).orElse(false));
                }
                case "cancel" -> {
                    requireArgs(args, 2);
                    UUID id = UUID.fromString(args[1]);
                    boolean removed = trades.cancelTradeAgreement(exporter, id);
                    sender.sendMessage("STARTRADE CANCEL " + id + " removed=" + removed);
                }
                case "embargo" -> {
                    requireArgs(args, 2);
                    long seconds = positive(args[1]);
                    var embargo = trades.createEmbargo(exporter, Set.of(importer), "wheat", seconds,
                            "isolated StarSupply acceptance");
                    sender.sendMessage("STARTRADE EMBARGO " + embargo.embargoId()
                            + " effective=" + embargo.isEffective() + " seconds=" + seconds);
                }
                case "lift" -> {
                    requireArgs(args, 2);
                    UUID id = UUID.fromString(args[1]);
                    boolean removed = trades.liftEmbargo(exporter, id);
                    sender.sendMessage("STARTRADE LIFT " + id + " removed=" + removed);
                }
                case "balances" -> {
                    requireArgs(args, 2);
                    UUID seller = UUID.fromString(args[1]);
                    TreasuryService treasury = api.service(TreasuryService.class).orElseThrow();
                    EconomyService economy = api.service(EconomyService.class).orElseThrow();
                    sender.sendMessage("STARTRADE BALANCES alpha=" + treasury.balance(importer)
                            + " beta=" + treasury.balance(exporter) + " seller=" + economy.getBalance(seller));
                }
                case "identity" -> {
                    requireArgs(args, 2);
                    UUID player = UUID.fromString(args[1]);
                    sender.sendMessage("STARTRADE IDENTITY " + player + " nation="
                            + nations.nationOf(player).map(nation -> nation.id().value()).orElse(null));
                }
                case "receipt" -> {
                    requireArgs(args, 2);
                    String transaction = "starsupply:payment:" + UUID.fromString(args[1]);
                    var receipt = api.service(NationSpendingService.class).orElseThrow()
                            .spendingReceipt(transaction);
                    sender.sendMessage("STARTRADE RECEIPT " + transaction + " present=" + receipt.isPresent()
                            + " amount=" + receipt.map(row -> row.amount().toPlainString()).orElse("none")
                            + " refunded=" + receipt.map(row -> Boolean.toString(row.refunded())).orElse("none"));
                }
                default -> sender.sendMessage(
                        "STARTRADE usage=create|state|cancel|embargo|lift|balances|identity|receipt");
            }
        } catch (RuntimeException failure) {
            sender.sendMessage("STARTRADE ERROR " + failure.getClass().getSimpleName()
                    + ": " + String.valueOf(failure.getMessage()));
        }
        return true;
    }

    private static long positive(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) throw new IllegalArgumentException("Expected a positive integer");
        return parsed;
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("Unexpected argument count");
    }
}
