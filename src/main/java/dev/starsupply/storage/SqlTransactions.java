package dev.starsupply.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Local SQL transactions never restore auto-commit on an unconfirmed transaction. */
final class SqlTransactions {
    private static final Set<Connection> RETIRED = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private SqlTransactions() {}

    static <T> T run(Connection connection, Work<T> work) throws SQLException {
        requireUsable(connection);
        try {
            connection.setAutoCommit(false);
        } catch (SQLException | RuntimeException | Error failure) {
            close(connection, failure);
            throw failure;
        }
        boolean commitAttempted = false;
        T result;
        try {
            result = work.run();
            commitAttempted = true;
            connection.commit();
        } catch (SQLException | RuntimeException | Error failure) {
            // A commit exception can mean that the database committed but its receipt was lost.
            // Retire the connection in either case; the durable IDs resolve state after reopening.
            if (commitAttempted) {
                close(connection, failure);
                throw failure;
            }
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                close(connection, failure);
                throw failure;
            }
            try {
                restore(connection);
            } catch (SQLException | RuntimeException | Error restoreFailure) {
                failure.addSuppressed(restoreFailure);
            }
            throw failure;
        }
        restore(connection);
        return result;
    }

    static void requireUsable(Connection connection) throws SQLException {
        if (RETIRED.contains(connection)) {
            throw new SQLException("Database connection retired after an unconfirmed transaction; reopen the store");
        }
    }

    private static void restore(Connection connection) throws SQLException {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException | RuntimeException | Error failure) {
            close(connection, failure);
            throw failure;
        }
    }

    private static void close(Connection connection, Throwable failure) {
        RETIRED.add(connection);
        try {
            connection.close();
        } catch (SQLException | RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T run() throws SQLException;
    }
}
