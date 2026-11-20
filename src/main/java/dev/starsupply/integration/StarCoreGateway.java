package dev.starsupply.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.core.extension.ExtensionContributor;
import dev.starcore.starcore.core.extension.ExtensionPoint;
import dev.starcore.starcore.core.extension.ExtensionRegistry;
import dev.starcore.starcore.core.extension.army.ArmyCombatExtensionPoint;
import dev.starcore.starcore.core.extension.army.ArmyCombatExtensionPoint.CombatContext;
import dev.starcore.starcore.core.extension.army.ArmyCombatExtensionPoint.CombatResult;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.foundation.economy.InternalEconomyService;
import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.module.army.ArmyService;
import dev.starcore.starcore.module.army.model.ArmyUnit;
import dev.starcore.starcore.module.army.siege.SiegeService;
import dev.starcore.starcore.module.army.siege.model.SiegeState;
import dev.starcore.starcore.mechanics.Season;
import dev.starcore.starcore.mechanics.SeasonService;
import dev.starcore.starcore.module.nation.NationService;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.officer.OfficerService;
import dev.starcore.starcore.module.resource.ResourceTradeService;
import dev.starcore.starcore.module.resource.model.TradeAgreement;
import dev.starcore.starcore.module.policy.PolicyService;
import dev.starcore.starcore.module.technology.TechnologyService;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starcore.starcore.module.treasury.TaxationService;
import dev.starcore.starcore.module.war.WarService;
import dev.starcore.starcore.war.BattlefieldService;
import dev.starcore.starcore.war.War;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Warehouse;
import dev.starsupply.api.SupplyModels.WarehouseKind;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/** Adapts the live StarCore services registered with Bukkit. */
public final class StarCoreGateway implements CoreGateway {
    private static final String COMBAT_POINT = "army_combat";
    private static final String COMBAT_CONTRIBUTOR = "starsupply:logistics";
    private static final long STRATEGY_CACHE_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
    private final NationService nations;
    private final Optional<OfficerService> officers;
    private final ArmyService armies;
    private final WarService wars;
    private final BattlefieldService battlefields;
    private final Optional<ResourceTradeService> resourceTrades;
    private final Optional<PolicyService> policies;
    private final Optional<TechnologyService> technologies;
    private final Optional<TaxationService> taxation;
    private final Optional<SeasonService> seasons;
    private final Optional<SiegeService> sieges;
    private final Map<String, Double> policyLogisticsBonuses;
    private final Map<String, Double> policyCombatBonuses;
    private final Map<String, Double> technologyLogisticsBonuses;
    private final ConcurrentMap<UUID, CachedLogisticsModifiers> logisticsCache = new ConcurrentHashMap<>();
    private final CorePayment payments;
    private volatile double campaignSiegeRadius = 128;
    private volatile Map<String, CampaignConditions> campaignCache = Map.of();

    public StarCoreGateway() {
        this(Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class),
                "StarCore has not registered StarCoreApi"));
    }

    public StarCoreGateway(Map<String, Double> policyLogisticsBonuses,
                           Map<String, Double> technologyLogisticsBonuses) {
        this(Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class),
                "StarCore has not registered StarCoreApi"), Logger.getLogger(StarCoreGateway.class.getName()),
                policyLogisticsBonuses, Map.of(), technologyLogisticsBonuses);
    }

    public StarCoreGateway(Map<String, Double> policyLogisticsBonuses,
                           Map<String, Double> policyCombatBonuses,
                           Map<String, Double> technologyLogisticsBonuses) {
        this(Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class),
                "StarCore has not registered StarCoreApi"), Logger.getLogger(StarCoreGateway.class.getName()),
                policyLogisticsBonuses, policyCombatBonuses, technologyLogisticsBonuses);
    }

    public StarCoreGateway(StarCoreApi api) {
        this(api, Logger.getLogger(StarCoreGateway.class.getName()));
    }

    public StarCoreGateway(StarCoreApi api, Logger logger) {
        this(api, logger, Map.of(), Map.of(), Map.of());
    }

    public StarCoreGateway(StarCoreApi api, Logger logger, Map<String, Double> policyLogisticsBonuses,
                           Map<String, Double> technologyLogisticsBonuses) {
        this(api, logger, policyLogisticsBonuses, Map.of(), technologyLogisticsBonuses);
    }

    public StarCoreGateway(StarCoreApi api, Logger logger, Map<String, Double> policyLogisticsBonuses,
                           Map<String, Double> policyCombatBonuses,
                           Map<String, Double> technologyLogisticsBonuses) {
        nations = require(api, NationService.class);
        officers = api.service(OfficerService.class);
        armies = require(api, ArmyService.class);
        wars = require(api, WarService.class);
        battlefields = require(api, BattlefieldService.class);
        resourceTrades = api.service(ResourceTradeService.class);
        policies = api.service(PolicyService.class);
        technologies = api.service(TechnologyService.class);
        taxation = api.service(TaxationService.class);
        seasons = api.service(SeasonService.class);
        sieges = api.service(SiegeService.class);
        this.policyLogisticsBonuses = checkedBonuses(policyLogisticsBonuses, "policy");
        this.policyCombatBonuses = checkedBonuses(policyCombatBonuses, "combat policy");
        this.technologyLogisticsBonuses = checkedBonuses(technologyLogisticsBonuses, "technology");
        EconomyService activeProvider = require(api, EconomyService.class);
        if (!(activeProvider instanceof InternalEconomyService internal)) {
            throw new IllegalStateException("StarSupply requires StarCore economy.provider: internal. "
                    + "The selected provider does not guarantee durable, worker-thread-safe credits.");
        }
        payments = new CorePayment(require(api, NationSpendingService.class),
                require(api, TransactionFabric.class),
                (id, amount) -> internal.deposit(id, amount, true, true, "starsupply:payment"),
                this::nationExists, logger);
    }

    private static <T> T require(StarCoreApi api, Class<T> type) {
        return api.service(type).orElseThrow(() -> new IllegalStateException(
                "StarCore service is unavailable: " + type.getSimpleName()));
    }

    /** Shares the same serialized receipt/credit coordinator with the optional insurance product. */
    public InsuranceEconomy insuranceEconomy(Logger logger) {
        StarCoreApi api = Objects.requireNonNull(Bukkit.getServicesManager().load(StarCoreApi.class),
                "StarCore API no longer registered");
        return new CoreInsurancePayment(payments, api, logger);
    }

    @Override
    public Optional<UUID> nationOf(UUID playerId) {
        return nations.nationOf(playerId).map(nation -> nation.id().value());
    }

    @Override
    public boolean nationExists(UUID nationId) {
        return nations.nationById(NationId.of(nationId)).isPresent();
    }

    @Override
    public boolean canManage(UUID actorId, UUID nationId) {
        NationId id = NationId.of(nationId);
        return nationOf(actorId).filter(nationId::equals).isPresent()
                && (nations.isFounder(actorId, id) || officers
                .flatMap(service -> service.officer(id, "treasurer"))
                .filter(appointment -> appointment.playerId().equals(actorId)).isPresent());
    }

    @Override
    public Optional<Army> army(UUID armyId) {
        // Upstream army mutations take this same monitor. Copy before crossing thread boundaries.
        synchronized (armies) {
            return armies.getArmy(armyId).map(StarCoreGateway::snapshot);
        }
    }

    @Override
    public List<Army> armies(UUID nationId) {
        synchronized (armies) {
            return armies.getNationArmies(nationId).stream().map(StarCoreGateway::snapshot).toList();
        }
    }

    private static Army snapshot(ArmyUnit unit) {
        Location location = unit.location();
        String world = location.getWorld() == null ? "" : location.getWorld().getName();
        return new Army(unit.id(), unit.nationId(), unit.soldiers(), unit.supply(), world,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    @Override
    public boolean isAtWar(UUID nationId) {
        NationId id = NationId.of(nationId);
        return wars.activeWarsOf(id).stream().map(war -> wars.getWar(war.id()))
                .flatMap(Optional::stream).anyMatch(war -> war.status().isCombatAllowed());
    }

    @Override
    public boolean atWar(UUID left, UUID right) {
        return !left.equals(right) && wars.atWar(NationId.of(left), NationId.of(right));
    }

    @Override
    public LogisticsModifiers logisticsModifiers(UUID nationId) {
        Objects.requireNonNull(nationId, "nationId");
        long now = System.nanoTime();
        CachedLogisticsModifiers cached = logisticsCache.compute(nationId, (id, previous) -> {
            if (previous != null && now < previous.expiresAtNanos()) return previous;
            NationId coreNation = NationId.of(id);
            double supply = 0;
            double transport = 0;
            double combat = 0;
            if (policies.isPresent()) {
                Optional<String> active = policies.get().activePolicy(coreNation);
                if (active.isPresent()) {
                    supply += policyLogisticsBonuses.getOrDefault(active.get(), 0.0);
                    combat += policyCombatBonuses.getOrDefault(active.get(), 0.0);
                }
            }
            if (technologies.isPresent()) {
                for (String key : technologies.get().unlockedTechnologies(coreNation)) {
                    transport += technologyLogisticsBonuses.getOrDefault(key, 0.0);
                }
            }
            LogisticsModifiers modifiers = new LogisticsModifiers(Math.clamp(supply, 0, 0.5),
                    Math.clamp(transport, 0, 1.5), Math.clamp(combat, 0, 0.10));
            return new CachedLogisticsModifiers(now + STRATEGY_CACHE_NANOS, modifiers);
        });
        return cached.modifiers();
    }

    public void configureCampaigns(double siegeRadius) {
        if (!Double.isFinite(siegeRadius) || siegeRadius < 16 || siegeRadius > 512) {
            throw new IllegalArgumentException("Siege supply radius must be between 16 and 512 blocks");
        }
        campaignSiegeRadius = siegeRadius;
    }

    /** Sample mutable StarCore/World state only on Paper's server thread, never on the SQL worker. */
    public void captureCampaigns(List<Warehouse> fronts, long now) {
        if (!Bukkit.isGlobalTickThread()) {
            throw new IllegalStateException("Campaign sampling requires the global server tick thread");
        }
        if (now <= 0) throw new IllegalArgumentException("Invalid campaign sample time");
        Objects.requireNonNull(fronts, "fronts");
        Map<String, CampaignConditions> captured = new HashMap<>();
        Map<String, Boolean> winterByWorld = new HashMap<>();
        Map<UUID, List<CampaignSiegeTarget>> targetsByNation = new HashMap<>();
        Map<UUID, List<CampaignSiegeTarget>> attackerTargets = new HashMap<>();
        int[] remainingScans = {2_048};
        double radius = campaignSiegeRadius;
        // Limits bound optional gameplay work even when an upstream service contains very large histories.
        for (Warehouse front : fronts.stream().limit(4_096).toList()) {
            if (front.kind() != WarehouseKind.FRONT || front.armyId() == null) continue;
            boolean winter = winterByWorld.computeIfAbsent(front.position().world(), worldName ->
                    seasons.filter(SeasonService::isEnabled).flatMap(service ->
                            Optional.ofNullable(Bukkit.getWorld(worldName)).map(world ->
                                    service.getSeason(world) == Season.WINTER)).orElse(false));
            List<CampaignSiegeTarget> targets = targetsByNation.computeIfAbsent(front.nationId(), nation -> {
                if (targetsByNation.size() >= 256 || sieges.isEmpty()) return List.of();
                NationId defender = NationId.of(nation);
                Set<UUID> opponents = new HashSet<>();
                wars.activeWarsOf(defender).stream().limit(64).map(snapshot -> wars.getWar(snapshot.id()))
                        .flatMap(Optional::stream).filter(war -> war.status().isCombatAllowed()
                                && war.isParticipant(defender)).forEach(war -> war.allParticipants().stream()
                                .filter(other -> !war.areSameSide(defender, other)).limit(128)
                                .forEach(other -> opponents.add(other.value())));
                List<CampaignSiegeTarget> result = new ArrayList<>();
                for (UUID opponent : opponents.stream().sorted().limit(128).toList()) {
                    List<CampaignSiegeTarget> hostileTargets = attackerTargets.computeIfAbsent(opponent,
                            attacker -> captureSiegeTargets(attacker, remainingScans));
                    hostileTargets.stream().filter(target -> target.defender().equals(nation)).forEach(result::add);
                }
                return List.copyOf(result);
            });
            boolean underSiege = targets.stream().anyMatch(target -> target.affects(front, radius));
            captured.put(front.id(), new CampaignConditions(winter, underSiege, now));
        }
        campaignCache = Map.copyOf(captured);
    }

    private List<CampaignSiegeTarget> captureSiegeTargets(UUID attacker, int[] remainingScans) {
        if (sieges.isEmpty() || remainingScans[0] <= 0) return List.of();
        List<CampaignSiegeTarget> targets = new ArrayList<>();
        for (var unit : sieges.get().getNationSieges(attacker).stream().limit(128).toList()) {
            if (remainingScans[0]-- <= 0) break;
            if (!attacker.equals(unit.nationId()) || unit.state() != SiegeState.BESIEGING
                    || unit.siegeTarget() == null || !unit.isAlive() || !unit.canFire()) continue;
            sieges.get().getWall(unit.siegeTarget()).filter(wall -> wall.isUnderSiege()
                    && unit.id().equals(wall.besiegingSiegeId())
                    && wall.currentHealth() > 0 && !wall.nationId().equals(attacker)).ifPresent(wall ->
                    targets.add(new CampaignSiegeTarget(wall.nationId(), new Position(wall.world(),
                            wall.blockX(), wall.blockY(), wall.blockZ()))));
        }
        return List.copyOf(targets);
    }

    @Override
    public CampaignConditions campaignConditions(Warehouse front) {
        CampaignConditions sample = campaignCache.getOrDefault(front.id(), CampaignConditions.NORMAL);
        return sample.freshAt(System.currentTimeMillis()) ? sample : CampaignConditions.NORMAL;
    }

    @Override
    public TaxAssessment quoteTariff(UUID settlementId, UUID importer, UUID seller, BigDecimal gross) {
        TaxationService service = taxation.orElseThrow(() ->
                new IllegalStateException("StarCore TaxationService is unavailable; cross-border supply is disabled"));
        TaxationService.TaxQuote quote = service.quoteTax(settlementId, NationId.of(importer),
                TaxationService.TaxType.TARIFF, seller, gross);
        return new TaxAssessment(settlementId, quote.type().name(), importer, seller,
                quote.taxableAmount(), quote.taxAmount());
    }

    @Override
    public boolean fundTariff(TaxAssessment assessment) {
        TaxationService service = taxation.orElseThrow(() ->
                new IllegalStateException("StarCore TaxationService is unavailable"));
        TaxationService.TaxQuote quote = new TaxationService.TaxQuote(assessment.settlementId(),
                NationId.of(assessment.nationId()), TaxationService.TaxType.valueOf(assessment.type()),
                assessment.payerId(), assessment.grossAmount(), assessment.taxAmount());
        // A duplicate quote returns zero. Only a positive, first-call receipt proves funding here.
        return service.fundTax(quote).compareTo(assessment.taxAmount()) == 0
                && assessment.taxAmount().signum() > 0;
    }

    private record CachedLogisticsModifiers(long expiresAtNanos, LogisticsModifiers modifiers) {}

    private static Map<String, Double> checkedBonuses(Map<String, Double> source, String kind) {
        Objects.requireNonNull(source, kind + "LogisticsBonuses");
        source.forEach((key, value) -> {
            if (key == null || !key.matches("[a-z0-9][a-z0-9_-]{0,63}")
                    || value == null || !Double.isFinite(value) || value < 0 || value > 1.5) {
                throw new IllegalArgumentException("Invalid " + kind + " logistics bonus mapping");
            }
        });
        return Map.copyOf(source);
    }

    @Override
    public boolean tradeEmbargoed(UUID exporterNation, UUID importerNation,
                                  dev.starsupply.api.ResourceType resource) {
        if (exporterNation.equals(importerNation)) return false;
        // Cross-nation trade fails closed when StarCore's diplomacy/resource service is unavailable.
        return resourceTrades.map(service -> service.isEmbargoed(NationId.of(exporterNation),
                NationId.of(importerNation), resource.starCoreTradeId())
                || service.isEmbargoed(NationId.of(importerNation), NationId.of(exporterNation),
                resource.starCoreTradeId())).orElse(true);
    }

    @Override
    public Optional<TradePermit> tradePermit(UUID exporterNation, UUID importerNation,
                                             dev.starsupply.api.ResourceType resource) {
        if (exporterNation.equals(importerNation)) return Optional.empty();
        return resourceTrades.flatMap(service -> {
            NationId exporter = NationId.of(exporterNation);
            NationId importer = NationId.of(importerNation);
            if (service.isEmbargoed(exporter, importer, resource.starCoreTradeId())) return Optional.empty();
            return service.getTradeAgreements(exporter).stream()
                    .filter(TradeAgreement::isActive)
                    .filter(agreement -> agreement.exporterNationId().equals(exporter)
                            && agreement.importerNationId().equals(importer)
                            && agreement.resourceId().equalsIgnoreCase(resource.starCoreTradeId()))
                    .map(agreement -> new TradePermit(agreement.agreementId(), agreement.amount(),
                            BigDecimal.valueOf(agreement.pricePerUnit())))
                    .max(java.util.Comparator.comparingLong(TradePermit::maximumUnits)
                            .thenComparing(permit -> permit.agreementId().toString()));
        });
    }

    @Override
    public Optional<UUID> territoryOwner(String world, int chunkX, int chunkZ) {
        return nations.claimAt(world, chunkX, chunkZ).flatMap(claim -> {
            try {
                return Optional.of(UUID.fromString(claim.ownerId()));
            } catch (IllegalArgumentException notANationClaim) {
                return Optional.empty();
            }
        });
    }

    @Override
    public Optional<Line> line(UUID supplyLineId) {
        return battlefields.getSupplyLine(supplyLineId).flatMap(nativeLine -> {
            Location start = nativeLine.start();
            Location end = nativeLine.end();
            if (start.getWorld() == null || !start.getWorld().equals(end.getWorld())) {
                return Optional.empty();
            }
            boolean disrupted = nativeLine.isDisrupted() || nativeLine.effectiveCapacity() <= 0
                    || wars.getWar(nativeLine.warId()).filter(War::isActive).isEmpty();
            return Optional.of(new Line(nativeLine.id(), nativeLine.warId(), start.getWorld().getName(),
                    start.getBlockX(), start.getBlockY(), start.getBlockZ(), end.getBlockX(),
                    end.getBlockY(), end.getBlockZ(), nativeLine.capacity(), nativeLine.efficiency(),
                    disrupted));
        });
    }

    @Override
    public boolean canUseLine(UUID nationId, UUID supplyLineId) {
        return line(supplyLineId).flatMap(line -> wars.getWar(line.warId()))
                .filter(war -> war.isActive() && war.isParticipant(NationId.of(nationId))).isPresent();
    }

    @Override
    public PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId, BigDecimal amount) {
        return payments.pay(settlementId, nationId, playerId, amount);
    }

    @Override
    public PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId, BigDecimal amount,
                             TaxAssessment tax, java.util.function.Predicate<TaxAssessment> fundTax) {
        return payments.pay(settlementId, nationId, playerId, amount, tax, fundTax);
    }

    @Override
    public PaymentResult reconcileTariff(UUID settlementId, TaxAssessment assessment, boolean alreadyFunded,
                                         java.util.function.Predicate<TaxAssessment> fundTax) {
        return payments.reconcileTax(settlementId, assessment, alreadyFunded, fundTax);
    }

    /** Only call after checking both the local review and independent evidence for the player credit. */
    public PaymentResult reconcilePayment(UUID settlementId, boolean playerActuallyCredited) {
        return payments.reconcile(settlementId, playerActuallyCredited);
    }

    /** Adds a bounded modifier to StarCore's native army damage calculation. */
    public Runnable registerArmyCombatModifier(ToDoubleFunction<UUID> factor) {
        Objects.requireNonNull(factor, "factor");
        ExtensionRegistry registry = ExtensionRegistry.getInstance();
        ExtensionPoint<CombatResult, CombatContext> point = registry.getExtensionPoint(COMBAT_POINT);
        if (point == null) {
            // Its constructor registers the existing StarCore extension point.
            point = new ArmyCombatExtensionPoint();
        }
        ExtensionPoint<CombatResult, CombatContext> registered = point;
        ExtensionContributor<CombatResult, CombatContext> contributor = new ExtensionContributor<>() {
            @Override
            public String getId() {
                return COMBAT_CONTRIBUTOR;
            }

            @Override
            public String getName() {
                return "StarSupply logistics";
            }

            @Override
            public CombatResult contribute(CombatContext context) {
                UUID attackerId = parseUuid(context.attackerId());
                UUID defenderId = parseUuid(context.defenderId());
                if (attackerId == null || defenderId == null) {
                    return null;
                }
                Optional<ArmyUnit> attacker = armies.getArmy(attackerId);
                Optional<ArmyUnit> defender = armies.getArmy(defenderId);
                if (attacker.isEmpty() || defender.isEmpty()
                        || !combatAllowed(attacker.get().nationId(), defender.get().nationId())) {
                    return null;
                }
                double modifier = factor.applyAsDouble(attackerId);
                if (!Double.isFinite(modifier) || modifier == 1.0) {
                    return null;
                }
                modifier = Math.clamp(modifier, 0.80, 1.15);
                return new CombatResult(context.baseDamage() * modifier, COMBAT_CONTRIBUTOR,
                        List.of("logistics"));
            }
        };
        registry.registerContributor(registered, contributor);
        return () -> registry.unregisterContributor(registered, contributor);
    }

    private boolean combatAllowed(UUID left, UUID right) {
        NationId a = NationId.of(left);
        NationId b = NationId.of(right);
        return !a.equals(b) && wars.activeWarsOf(a).stream().map(snapshot -> wars.getWar(snapshot.id()))
                .flatMap(Optional::stream).anyMatch(war -> war.status().isCombatAllowed()
                        && war.isParticipant(a) && war.isParticipant(b) && !war.areSameSide(a, b));
    }

    private static UUID parseUuid(String text) {
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
