package dev.starsupply.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlTransactionsTest {
    @TempDir Path directory;

    @Test
    void successfulTransactionCommitsAndCanBeReused() throws Exception {
        try (Probe probe = database()) {
            assertEquals("saved", SqlTransactions.run(probe.connection, () -> {
                insert(probe.connection);
                return "saved";
            }));
            assertTrue(probe.actual.getAutoCommit());
            assertFalse(probe.actual.isClosed());
            assertEquals(List.of("begin", "commit", "restore"), probe.calls);
            SqlTransactions.run(probe.connection, () -> { insert(probe.connection); return null; });
            assertEquals(2, count(probe.file));
        }
    }

    @Test
    void workFailureRollsBackBeforeRestoringAndRetainsOriginalFailure() throws Exception {
        try (Probe probe = database()) {
            SQLException original = new SQLException("work failed");
            assertSame(original, assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> {
                        insert(probe.connection);
                        throw original;
                    })));
            assertEquals(List.of("begin", "rollback", "restore"), probe.calls);
            assertEquals(0, count(probe.file));
            assertTrue(probe.actual.getAutoCommit());
            SqlTransactions.run(probe.connection, () -> { insert(probe.connection); return null; });
            assertEquals(1, count(probe.file));
        }
    }

    @Test
    void failedRollbackNeverEnablesAutoCommitOrCommitsPendingChanges() throws Exception {
        try (Probe probe = database()) {
            probe.failRollback = true;
            SQLException original = new SQLException("work failed");
            assertSame(original, assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> {
                        insert(probe.connection);
                        throw original;
                    })));
            assertEquals(List.of("begin", "rollback", "close"), probe.calls);
            assertEquals("rollback failed", original.getSuppressed()[0].getMessage());
            assertTrue(probe.actual.isClosed());
            assertEquals(0, count(probe.file), "restoring auto-commit here would commit this real SQLite row");
            assertThrows(SQLException.class, () -> insert(probe.connection));
        }
    }

    @Test
    void lostCommitReceiptClosesConnectionAndLeavesOneDurableWrite() throws Exception {
        try (Probe probe = database()) {
            probe.failAfterCommit = true;
            SQLException failure = assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> { insert(probe.connection); return null; }));
            assertEquals("commit receipt lost", failure.getMessage());
            assertEquals(List.of("begin", "commit", "close"), probe.calls);
            assertTrue(probe.actual.isClosed());
            assertEquals(1, count(probe.file), "an uncertain commit must never replay the transaction");
        }
    }

    @Test
    void failedCommitBeforeApplicationAlsoClosesWithoutRestoringAutoCommit() throws Exception {
        try (Probe probe = database()) {
            probe.failBeforeCommit = true;
            assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> { insert(probe.connection); return null; }));
            assertEquals(List.of("begin", "commit", "close"), probe.calls);
            assertTrue(probe.actual.isClosed());
            assertEquals(0, count(probe.file));
        }
    }

    @Test
    void failedRestoreAfterRollbackClosesAndPreservesWorkFailure() throws Exception {
        try (Probe probe = database()) {
            probe.failRestore = true;
            IllegalArgumentException original = new IllegalArgumentException("invalid work");
            assertSame(original, assertThrows(IllegalArgumentException.class, () ->
                    SqlTransactions.run(probe.connection, () -> { insert(probe.connection); throw original; })));
            assertEquals(List.of("begin", "rollback", "restore", "close"), probe.calls);
            assertEquals("restore failed", original.getSuppressed()[0].getMessage());
            assertTrue(probe.actual.isClosed());
            assertEquals(0, count(probe.file));
        }
    }

    @Test
    void failedRestoreAfterCommitClosesWithoutHidingConfirmedWrite() throws Exception {
        try (Probe probe = database()) {
            probe.failRestore = true;
            assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> { insert(probe.connection); return null; }));
            assertEquals(List.of("begin", "commit", "restore", "close"), probe.calls);
            assertTrue(probe.actual.isClosed());
            assertEquals(1, count(probe.file));
        }
    }

    @Test
    void failedBeginClosesAndNeverRunsWork() throws Exception {
        try (Probe probe = database()) {
            probe.failBegin = true;
            assertThrows(SQLException.class, () ->
                    SqlTransactions.run(probe.connection, () -> fail("work must not run")));
            assertEquals(List.of("begin", "close"), probe.calls);
            assertTrue(probe.actual.isClosed());
        }
    }

    @Test
    void errorsAlsoRollbackAndCloseFailuresRemainSuppressed() throws Exception {
        try (Probe probe = database()) {
            probe.failRollback = true;
            probe.failClose = true;
            AssertionError original = new AssertionError("work aborted");
            assertSame(original, assertThrows(AssertionError.class, () ->
                    SqlTransactions.run(probe.connection, () -> { insert(probe.connection); throw original; })));
            assertEquals(2, original.getSuppressed().length);
            assertEquals("rollback failed", original.getSuppressed()[0].getMessage());
            assertEquals("close failed", original.getSuppressed()[1].getMessage());
            assertEquals(List.of("begin", "rollback", "close"), probe.calls);
            assertTrue(probe.actual.isClosed());
            assertEquals(0, count(probe.file));
        }
    }

    @Test
    void unclosedConnectionIsRetiredEvenWhenCloseFailsBeforeTakingEffect() throws Exception {
        try (Probe probe = database()) {
            probe.failRollback = true;
            probe.failCloseBefore = true;
            assertThrows(SQLException.class, () -> SqlTransactions.run(probe.connection, () -> {
                insert(probe.connection);
                throw new SQLException("work failed");
            }));
            assertFalse(probe.actual.isClosed(), "the simulated close did not reach the driver");
            assertThrows(SQLException.class, () -> SqlTransactions.requireUsable(probe.connection));
            assertThrows(SQLException.class, () -> SqlTransactions.run(probe.connection,
                    () -> fail("a retired connection must never run work")));
            assertEquals(List.of("begin", "rollback", "close"), probe.calls);
        }
        assertEquals(0, count(directory.resolve("transactions.sqlite")));
    }

    private Probe database() throws Exception {
        Path file = directory.resolve("transactions.sqlite");
        Connection actual = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (var statement = actual.createStatement()) { statement.execute("CREATE TABLE writes (id INTEGER)"); }
        return new Probe(file, actual);
    }

    private static void insert(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute("INSERT INTO writes VALUES (1)"); }
    }

    private static int count(Path file) throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM writes")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    private static final class Probe implements AutoCloseable {
        final Path file;
        final Connection actual;
        final Connection connection;
        final List<String> calls = new ArrayList<>();
        boolean failBegin;
        boolean failRollback;
        boolean failBeforeCommit;
        boolean failAfterCommit;
        boolean failRestore;
        boolean failClose;
        boolean failCloseBefore;

        Probe(Path file, Connection actual) {
            this.file = file;
            this.actual = actual;
            connection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        String name = method.getName();
                        if (name.equals("setAutoCommit")) {
                            boolean restore = (boolean) arguments[0];
                            calls.add(restore ? "restore" : "begin");
                            if (restore && failRestore) throw new SQLException("restore failed");
                            if (!restore && failBegin) throw new SQLException("begin failed");
                        } else if (name.equals("commit")) {
                            calls.add("commit");
                            if (failBeforeCommit) throw new SQLException("commit failed");
                        } else if (name.equals("rollback")) {
                            calls.add("rollback");
                            if (failRollback) throw new SQLException("rollback failed");
                        } else if (name.equals("close")) {
                            calls.add("close");
                            if (failCloseBefore) throw new SQLException("close failed before application");
                        }
                        Object result;
                        try {
                            result = method.invoke(actual, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                        if (name.equals("commit") && failAfterCommit) throw new SQLException("commit receipt lost");
                        if (name.equals("close") && failClose) throw new SQLException("close failed");
                        return result;
                    });
        }

        @Override public void close() throws SQLException { actual.close(); }
    }
}
