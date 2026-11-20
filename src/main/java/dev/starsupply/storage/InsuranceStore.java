package dev.starsupply.storage;

import dev.starsupply.api.InsuranceApi.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Independent insurance ledger. Use from the same serialized worker as cargo operations. */
public final class InsuranceStore implements AutoCloseable {
    private final Connection connection;
    private String activeCursor = "";

    public InsuranceStore(Path file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            initialize();
        } catch (SQLException | RuntimeException failure) {
            try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            int version;
            try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
                version = result.next() ? result.getInt(1) : -1;
                if (version < 0 || version > 2) throw new SQLException("Unsupported insurance schema " + version);
            }
            connection.setAutoCommit(false);
            try {
                if (version == 0) {
                    statement.execute("CREATE TABLE insurance_policy (id TEXT PRIMARY KEY,shipment TEXT UNIQUE NOT NULL,"
                            + "purchase TEXT UNIQUE NOT NULL,nation TEXT NOT NULL,carrier TEXT NOT NULL,freight TEXT NOT NULL,"
                            + "claim_amount TEXT NOT NULL,premium TEXT NOT NULL,risk REAL NOT NULL,state TEXT NOT NULL,"
                            + "refund_premium INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL,updated INTEGER NOT NULL,"
                            + "activated INTEGER NOT NULL DEFAULT 0)");
                    statement.execute("CREATE TABLE insurance_money (id TEXT UNIQUE NOT NULL,policy TEXT NOT NULL "
                            + "REFERENCES insurance_policy(id),leg TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(policy,leg))");
                    statement.execute("CREATE INDEX insurance_pending ON insurance_policy(state,updated)");
                } else if (version == 1) {
                    statement.execute("ALTER TABLE insurance_policy ADD COLUMN activated INTEGER NOT NULL DEFAULT 0");
                    // Old ACTIVE rows have no exact activation evidence. Their latest durable update
                    // is the conservative available boundary; INSURING rows get their real time later.
                    statement.execute("UPDATE insurance_policy SET activated=MAX(0,created,updated) WHERE state='ACTIVE'");
                }
                statement.execute("PRAGMA user_version=2");
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally { connection.setAutoCommit(true); }
        }
        // These legs lack an authority receipt. Calling again after a crash could debit/credit twice.
        update("UPDATE insurance_money SET state='REVIEW' WHERE state='STARTED' "
                + "AND leg IN ('PREMIUM_DEBIT','PREMIUM_CREDIT','PREMIUM_REFUND')");
    }

    public synchronized Policy create(Quote quote, UUID carrier, long now) throws SQLException {
        UUID id = UUID.randomUUID();
        transaction(() -> {
            update("INSERT INTO insurance_policy VALUES(?,?,?,?,?,?,?,?,?,'INSURING',0,?,?,0)", id,
                    quote.shipmentId(), quote.purchaseId(), quote.nationId(), carrier, quote.freight(),
                    quote.claimAmount(), quote.premium(), quote.maximumRisk(), now, now);
            for (MoneyLeg leg : MoneyLeg.values()) {
                UUID moneyId = UUID.nameUUIDFromBytes(("starsupply:insurance:" + id + ":" + leg)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                update("INSERT INTO insurance_money VALUES(?,?,?,'PENDING')", moneyId, id, leg);
            }
            return null;
        });
        return find(id).orElseThrow();
    }

    public synchronized Optional<Policy> forShipment(UUID shipment) throws SQLException {
        return read("shipment", shipment);
    }

    public synchronized Optional<Policy> forPurchase(UUID purchase) throws SQLException {
        return read("purchase", purchase);
    }

    public synchronized Optional<Policy> find(UUID id) throws SQLException { return read("id", id); }

    private Optional<Policy> read(String column, UUID id) throws SQLException {
        try (PreparedStatement statement = prepare("SELECT * FROM insurance_policy WHERE " + column + "=?", id);
             ResultSet r = statement.executeQuery()) {
            if (!r.next()) return Optional.empty();
            UUID policy = UUID.fromString(r.getString("id"));
            Map<MoneyLeg,MoneyState> money = new EnumMap<>(MoneyLeg.class);
            try (PreparedStatement legs = prepare("SELECT leg,state FROM insurance_money WHERE policy=?", policy);
                 ResultSet m = legs.executeQuery()) {
                while (m.next()) money.put(MoneyLeg.valueOf(m.getString(1)), MoneyState.valueOf(m.getString(2)));
            }
            return Optional.of(new Policy(policy, UUID.fromString(r.getString("shipment")),
                    UUID.fromString(r.getString("purchase")), UUID.fromString(r.getString("nation")),
                    UUID.fromString(r.getString("carrier")), new BigDecimal(r.getString("freight")),
                    new BigDecimal(r.getString("claim_amount")), new BigDecimal(r.getString("premium")),
                    r.getDouble("risk"), State.valueOf(r.getString("state")), r.getBoolean("refund_premium"),
                    r.getLong("created"), r.getLong("updated"), r.getLong("activated"), money));
        }
    }

    public synchronized List<Policy> pending() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement s = prepare("SELECT id FROM insurance_policy p WHERE state IN "
                + "('INSURING','CLAIM_PENDING','CLOSING') AND NOT EXISTS "
                + "(SELECT 1 FROM insurance_money m WHERE m.policy=p.id AND m.state='REVIEW') "
                + "ORDER BY updated,id LIMIT 64"); ResultSet r = s.executeQuery()) {
            while (r.next()) ids.add(UUID.fromString(r.getString(1)));
        }
        List<Policy> result = new ArrayList<>();
        for (UUID id : ids) result.add(find(id).orElseThrow());
        return List.copyOf(result);
    }

    /** Active/activating contracts must be joined with the cargo ledger after a restart. */
    public synchronized List<Policy> active() throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement s = prepare("SELECT id FROM insurance_policy WHERE state IN "
                + "('INSURING','ACTIVE') AND id>? ORDER BY id LIMIT 128", activeCursor); ResultSet r = s.executeQuery()) {
            while (r.next()) ids.add(UUID.fromString(r.getString(1)));
        }
        activeCursor = ids.isEmpty() ? "" : ids.getLast().toString();
        List<Policy> result = new ArrayList<>();
        for (UUID id : ids) result.add(find(id).orElseThrow());
        return List.copyOf(result);
    }

    public synchronized void state(UUID policy, State state, boolean refundPremium, long now) throws SQLException {
        update("UPDATE insurance_policy SET state=?,refund_premium=?,updated=?,"
                        + "activated=CASE WHEN ?='ACTIVE' AND activated=0 THEN ? ELSE activated END WHERE id=?",
                state, refundPremium, now, state, now, policy);
    }

    public synchronized void money(UUID policy, MoneyLeg leg, MoneyState state, long now) throws SQLException {
        transaction(() -> {
            update("UPDATE insurance_money SET state=? WHERE policy=? AND leg=?", state, policy, leg);
            update("UPDATE insurance_policy SET updated=? WHERE id=?", now, policy);
            return null;
        });
    }

    public synchronized MoneyOperation operation(Policy policy, MoneyLeg leg) throws SQLException {
        UUID id;
        try (PreparedStatement s = prepare("SELECT id FROM insurance_money WHERE policy=? AND leg=?", policy.id(), leg);
             ResultSet r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Missing insurance money leg");
            id = UUID.fromString(r.getString(1));
        }
        BigDecimal amount = switch (leg) {
            case RESERVE, CLAIM, RELEASE -> policy.claimAmount();
            default -> policy.premium();
        };
        return new MoneyOperation(id, policy.id(), policy.shipmentId(), policy.purchaseId(), policy.nationId(),
                policy.carrierId(), leg, amount);
    }

    private int update(String sql, Object... args) throws SQLException {
        try (PreparedStatement s = prepare(sql, args)) { return s.executeUpdate(); }
    }

    private PreparedStatement prepare(String sql, Object... args) throws SQLException {
        PreparedStatement s = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            s.setObject(i + 1, value instanceof UUID || value instanceof Enum<?> || value instanceof BigDecimal
                    ? value.toString() : value);
        }
        return s;
    }

    private <T> T transaction(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try { T value = work.run(); connection.commit(); return value; }
        catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        finally { connection.setAutoCommit(true); }
    }

    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }
    @Override public synchronized void close() throws SQLException { connection.close(); }
}
