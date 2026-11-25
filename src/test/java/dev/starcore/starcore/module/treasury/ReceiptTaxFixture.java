package dev.starcore.starcore.module.treasury;

import dev.starcore.starcore.core.StarCoreContext;
import dev.starcore.starcore.core.database.DatabaseService;
import dev.starcore.starcore.core.service.ServiceRegistry;
import java.nio.file.Path;
import java.sql.*;
import java.util.function.Function;

/** Original API types only; optional tax receipt methods are invoked by the production bridge. */
public final class ReceiptTaxFixture {
    private final TaxationServiceImpl taxes = new TaxationServiceImpl();
    public ReceiptTaxFixture(Path file, NationCreditService credits) throws ReflectiveOperationException {
        ServiceRegistry services = new ServiceRegistry();
        services.register(NationCreditService.class, credits);
        var context = new StarCoreContext(null,null,null,null,null,null,new Sql(file),null,null,null,services);
        var field = TaxationServiceImpl.class.getDeclaredField("currentContext");
        field.setAccessible(true); field.set(taxes,context);
    }
    public Object service() { return taxes; }

    private static final class Sql extends DatabaseService {
        private final String url;
        Sql(Path file) { super(null,null); url="jdbc:sqlite:" + file.toAbsolutePath(); }
        @Override public boolean execute(String sql) {
            try (var c=DriverManager.getConnection(url); var s=c.createStatement()) { return s.execute(sql); }
            catch (SQLException e) { throw new IllegalStateException(e); }
        }
        @Override public int update(String sql,Object... args) {
            try (var c=DriverManager.getConnection(url); var s=c.prepareStatement(sql)) { bind(s,args); return s.executeUpdate(); }
            catch (SQLException e) { throw new IllegalStateException(e); }
        }
        @Override public <T> T query(String sql,Function<ResultSet,T> mapper,Object... args) {
            try (var c=DriverManager.getConnection(url); var s=c.prepareStatement(sql)) {
                bind(s,args); try(var r=s.executeQuery()) { return mapper.apply(r); }
            } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        private void bind(PreparedStatement s,Object[] args) throws SQLException { for(int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    }
}
