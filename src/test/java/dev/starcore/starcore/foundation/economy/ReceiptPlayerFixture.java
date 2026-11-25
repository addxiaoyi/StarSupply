package dev.starcore.starcore.foundation.economy;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Uses only original package constructors; the optional receipt API is exercised by reflection. */
public final class ReceiptPlayerFixture implements AutoCloseable {
    private final SqlBalanceStorage storage;
    private final InternalEconomyService economy;

    public ReceiptPlayerFixture(Path file) {
        dev.starcore.starcore.core.extension.ExtensionRegistry.init(Logger.getAnonymousLogger());
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        storage = new SqlBalanceStorage(() -> Optional.of(source), Properties::new, Logger.getAnonymousLogger(), Runnable::run);
        economy = new InternalEconomyService(storage);
        economy.start();
    }

    public InternalEconomyService economy() { return economy; }
    @Override public void close() { storage.shutdown(); }
}
