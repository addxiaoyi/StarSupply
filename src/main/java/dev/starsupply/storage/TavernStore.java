package dev.starsupply.storage;

import static dev.starsupply.storage.SupplyStore.require;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.TavernModels.Hub;
import dev.starsupply.api.TavernModels.ScoutReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Independent SQLite storage for informational hubs/reports; it never holds money or cargo. */
public final class TavernStore implements AutoCloseable {
    private static final int MAX_HUBS = 16;
    private static final int MAX_REPORTS = 128;
    private final Connection connection;

    public TavernStore(Path path) throws SQLException, IOException {
        Objects.requireNonNull(path);
        Driver driver = new org.sqlite.JDBC();
        connection = driver.connect("jdbc:sqlite:" + path.toAbsolutePath(), new java.util.Properties());
        if (connection == null) throw new SQLException("Tavern SQLite driver rejected its database URL");
        try {
            execute("PRAGMA foreign_keys=ON");
            execute("PRAGMA busy_timeout=5000");
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            int version = one("PRAGMA user_version", row -> row.getInt(1)).orElseThrow();
            if (version == 0) {
                try (InputStream input = TavernStore.class.getResourceAsStream("/db/tavern-v1.sql")) {
                    if (input == null) throw new IOException("Missing tavern database schema");
                    String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String statement : schema.split(";")) {
                            if (!statement.isBlank()) execute(statement);
                        }
                        return null;
                    });
                }
                version = 1;
            }
            if (version != 1) throw new SQLException("Unsupported Tavern database schema " + version);
        } catch (SQLException | IOException | RuntimeException failure) {
            connection.close();
            throw failure;
        }
    }

    public synchronized Hub addHub(Hub hub) throws SQLException {
        Objects.requireNonNull(hub);
        return tx(() -> {
            require(one("SELECT COUNT(*) FROM tavern_hub WHERE nation=?", row -> row.getInt(1), hub.nationId())
                    .orElseThrow() < MAX_HUBS, "每个国家最多登记 16 个酒馆集合点");
            require(hub(hub.id()).isEmpty(), "酒馆集合点编号已存在");
            Position p = hub.position();
            execute("INSERT INTO tavern_hub VALUES(?,?,?,?,?,?,?,?,?)", hub.id(), hub.nationId(), p.world(),
                    p.x(), p.y(), p.z(), hub.registeredBy(), hub.createdAt(), hub.tavernVerified());
            return hub;
        });
    }

    public synchronized Optional<Hub> hub(String id) throws SQLException {
        return one("SELECT * FROM tavern_hub WHERE id=?", TavernStore::hub, id);
    }

    public synchronized List<Hub> hubs(UUID nation) throws SQLException {
        return rows("SELECT * FROM tavern_hub WHERE nation=? ORDER BY created,id LIMIT 16", TavernStore::hub, nation);
    }

    /** Reports retain their original observation/expiry if another hub later republishes them. */
    public synchronized boolean removeHub(String id, UUID nation) throws SQLException {
        return tx(() -> {
            if (hub(id).filter(hub -> hub.nationId().equals(nation)).isEmpty()) return false;
            execute("UPDATE scout_report SET published_hub=NULL,published=0 WHERE published_hub=?", id);
            return execute("DELETE FROM tavern_hub WHERE id=? AND nation=?", id, nation) == 1;
        });
    }

    public synchronized ScoutReport addReport(ScoutReport report, long now) throws SQLException {
        Objects.requireNonNull(report);
        require(report.publishedHub() == null && !report.expired(now), "情报快照已失效或已经发布");
        return tx(() -> {
            prune(now);
            require(one("SELECT COUNT(*) FROM scout_report WHERE nation=?", row -> row.getInt(1), report.nationId())
                    .orElseThrow() < MAX_REPORTS, "每个国家最多保留 128 份有效侦察报告");
            Position p = report.observedFrom();
            execute("INSERT INTO scout_report VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", report.id(),
                    report.nationId(), report.scoutId(), report.roadId(), report.roadNationId(), report.origin(),
                    report.target(), report.distance(), report.roadEfficiency(), report.blocked(), report.hostileRisk(),
                    p.world(), p.x(), p.y(), p.z(), report.observedAt(), report.expiresAt(), null, 0);
            return report;
        });
    }

    public synchronized Optional<ScoutReport> report(UUID id, UUID nation, long now) throws SQLException {
        return one("SELECT * FROM scout_report WHERE id=? AND nation=? AND observed<=? AND expires>?",
                TavernStore::report, id, nation, now, now);
    }

    /** Publish once. A duplicate at the same hub preserves both expiry and original publication time. */
    public synchronized ScoutReport publish(UUID id, UUID nation, String hubId, long now) throws SQLException {
        return tx(() -> {
            require(hub(hubId).filter(hub -> hub.nationId().equals(nation)).isPresent(), "酒馆集合点不属于该国家");
            ScoutReport report = report(id, nation, now).orElseThrow(() -> new IllegalArgumentException("报告不存在或已过期"));
            if (hubId.equals(report.publishedHub())) return report;
            require(report.publishedHub() == null, "报告已经发布到另一处酒馆");
            require(execute("UPDATE scout_report SET published_hub=?,published=? WHERE id=? AND nation=? "
                    + "AND published_hub IS NULL AND observed<=? AND expires>?", hubId, now, id, nation, now, now) == 1,
                    "报告状态已变化");
            return report(id, nation, now).orElseThrow();
        });
    }

    public synchronized List<ScoutReport> board(String hub, UUID nation, long now) throws SQLException {
        return rows("SELECT * FROM scout_report WHERE published_hub=? AND nation=? AND observed<=? AND expires>? "
                + "ORDER BY observed DESC,id LIMIT 128", TavernStore::report, hub, nation, now, now);
    }

    public synchronized void prune(long now) throws SQLException {
        execute("DELETE FROM scout_report WHERE expires<=?", now);
    }

    private static Hub hub(ResultSet row) throws SQLException {
        return new Hub(row.getString("id"), UUID.fromString(row.getString("nation")), position(row),
                UUID.fromString(row.getString("registered_by")), row.getLong("created"), row.getBoolean("verified"));
    }

    private static ScoutReport report(ResultSet row) throws SQLException {
        return new ScoutReport(UUID.fromString(row.getString("id")), UUID.fromString(row.getString("nation")),
                UUID.fromString(row.getString("scout")), row.getString("road"), UUID.fromString(row.getString("road_nation")),
                row.getString("origin"), row.getString("target"), row.getDouble("distance"), row.getDouble("efficiency"),
                row.getBoolean("blocked"), row.getDouble("risk"), position(row), row.getLong("observed"),
                row.getLong("expires"), row.getString("published_hub"), row.getLong("published"));
    }

    private static Position position(ResultSet row) throws SQLException {
        return new Position(row.getString("world"), row.getDouble("x"), row.getDouble("y"), row.getDouble("z"));
    }

    private int execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = statement(sql, args)) {
            statement.execute();
            return statement.getUpdateCount();
        }
    }

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof UUID) value = value.toString();
                statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException | RuntimeException failure) {
            statement.close();
            throw failure;
        }
    }

    private <T> Optional<T> one(String sql, Reader<T> reader, Object... args) throws SQLException {
        List<T> rows = rows(sql, reader, args);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private <T> List<T> rows(String sql, Reader<T> reader, Object... args) throws SQLException {
        try (PreparedStatement statement = statement(sql, args); ResultSet rows = statement.executeQuery()) {
            List<T> result = new ArrayList<>();
            while (rows.next()) result.add(reader.read(rows));
            return List.copyOf(result);
        }
    }

    private <T> T tx(Transaction<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @Override public synchronized void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface private interface Reader<T> { T read(ResultSet row) throws SQLException; }
    @FunctionalInterface private interface Transaction<T> { T run() throws SQLException; }
}
