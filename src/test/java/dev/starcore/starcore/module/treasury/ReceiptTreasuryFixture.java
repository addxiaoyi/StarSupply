package dev.starcore.starcore.module.treasury;

import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;
import org.sqlite.SQLiteDataSource;

/** Uses the pinned Core treasury and its real SQL storage without enabling Bukkit modules. */
public final class ReceiptTreasuryFixture implements AutoCloseable {
    private final SqlTreasuryStateStorage storage;
    private final TreasuryModule module = new TreasuryModule();

    public ReceiptTreasuryFixture(Path database) throws ReflectiveOperationException {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        storage = new SqlTreasuryStateStorage(source, new Properties(), Logger.getAnonymousLogger());
        var field = TreasuryModule.class.getDeclaredField("stateStorage");
        field.setAccessible(true);
        field.set(module, storage);
        var load = TreasuryModule.class.getDeclaredMethod("loadState");
        load.setAccessible(true);
        load.invoke(module);
    }

    public NationCreditService credits() { return module; }
    public TreasuryService treasury() { return module; }
    @Override public void close() { storage.shutdown(); }
}
