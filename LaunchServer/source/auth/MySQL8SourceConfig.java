package launchserver.auth;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.zaxxer.hikari.HikariDataSource;
import launcher.LauncherAPI;
import launcher.helper.LogHelper;
import launcher.helper.VerifyHelper;
import launcher.serialize.config.ConfigObject;
import launcher.serialize.config.entry.BlockConfigEntry;
import launcher.serialize.config.entry.BooleanConfigEntry;
import launcher.serialize.config.entry.IntegerConfigEntry;
import launcher.serialize.config.entry.StringConfigEntry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public final class MySQL8SourceConfig extends ConfigObject implements AutoCloseable, SQLSourceConfig
{
    @LauncherAPI
    public static final int TIMEOUT = VerifyHelper.verifyInt(
            Integer.parseInt(System.getProperty("launcher.mysql.idleTimeout", Integer.toString(5000))),
            VerifyHelper.POSITIVE, "launcher.mysql.idleTimeout can't be <= 5000");
    private static final int MAX_POOL_SIZE = VerifyHelper.verifyInt(
            Integer.parseInt(System.getProperty("launcher.mysql.maxPoolSize", Integer.toString(3))),
            VerifyHelper.POSITIVE, "launcher.mysql.maxPoolSize can't be <= 0");

    // Instance
    private final String poolName;

    // Config
    private final String address;
    private final int port;
    private final String username;
    private final String password;
    private final String database;
    private final boolean useSSL;
    private String timeZone;

    // Cache
    private DataSource source;
    private boolean hikari;

    @LauncherAPI
    public MySQL8SourceConfig(String poolName, BlockConfigEntry block)
    {
        super(block);
        this.poolName = poolName;
        address = VerifyHelper.verify(block.getEntryValue("address", StringConfigEntry.class),
                VerifyHelper.NOT_EMPTY, "MySQL address can't be empty");
        port = VerifyHelper.verifyInt(block.getEntryValue("port", IntegerConfigEntry.class),
                VerifyHelper.range(0, 65535), "Illegal MySQL port");
        username = VerifyHelper.verify(block.getEntryValue("username", StringConfigEntry.class),
                VerifyHelper.NOT_EMPTY, "MySQL username can't be empty");
        password = block.getEntryValue("password", StringConfigEntry.class);
        database = VerifyHelper.verify(block.getEntryValue("database", StringConfigEntry.class),
                VerifyHelper.NOT_EMPTY, "MySQL database can't be empty");
        timeZone = block.hasEntry("timezone") ? VerifyHelper.verify(block.getEntryValue("timezone", StringConfigEntry.class),
                VerifyHelper.NOT_EMPTY, "MySQL time zone can't be empty") : null;
        useSSL = block.hasEntry("useSSL") ? block.getEntryValue("useSSL", BooleanConfigEntry.class) : true;

        // Password shouldn't be verified
    }

    @Override
    public synchronized void close()
    {
        if (hikari)
        { // Shutdown hikari pool
            ((HikariDataSource) source).close();
        }
    }

    @LauncherAPI
    public synchronized Connection getConnection() throws SQLException
    {
        if (source == null)
        { // New data source
            MysqlDataSource mysqlSource = new MysqlDataSource();
            mysqlSource.setCharacterEncoding("UTF-8");
            mysqlSource.setUseSSL(useSSL);

            // Prep statements cache
            mysqlSource.setPrepStmtCacheSize(250);
            mysqlSource.setPrepStmtCacheSqlLimit(2048);
            mysqlSource.setCachePrepStmts(true);
            mysqlSource.setUseServerPrepStmts(true);

            // General optimizations
            mysqlSource.setCacheServerConfiguration(true);
            mysqlSource.setUseLocalSessionState(true);
            mysqlSource.setRewriteBatchedStatements(true);
            mysqlSource.setMaintainTimeStats(false);
            mysqlSource.setUseUnbufferedInput(false);
            mysqlSource.setUseReadAheadInput(false);
            mysqlSource.setTcpNoDelay(true);

            // Set credentials
            mysqlSource.setServerName(address);
            mysqlSource.setPortNumber(port);
            mysqlSource.setUser(username);
            mysqlSource.setPassword(password);
            mysqlSource.setDatabaseName(database);

            if (timeZone != null) mysqlSource.setServerTimezone(timeZone);

            // Try using HikariCP
            source = mysqlSource;
            try
            {
                Class.forName("com.zaxxer.hikari.HikariDataSource");
                hikari = true; // Used for shutdown. Not instanceof because of possible classpath error

                // Set HikariCP pool
                HikariDataSource hikariSource = new HikariDataSource();
                hikariSource.setDataSource(source);

                // Set pool settings
                hikariSource.setPoolName(poolName);
                hikariSource.setMinimumIdle(0);
                hikariSource.setMaximumPoolSize(MAX_POOL_SIZE);
                hikariSource.setIdleTimeout(TIMEOUT * 1000L);

                // Replace source with hds
                source = hikariSource;
                LogHelper.info("HikariCP pooling enabled for '%s'", poolName);
            }
            catch (ClassNotFoundException ignored)
            {
                LogHelper.warning("HikariCP isn't in classpath for '%s'", poolName);
            }
        }
        return source.getConnection();
    }
}
