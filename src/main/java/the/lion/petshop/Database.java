package the.lion.petshop;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** One long-lived connection that only the single database thread touches. */
final class Database {

    record Loaded(Set<String> owned, String active) {
    }

    @FunctionalInterface
    private interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private static final long RECONNECT_PAUSE_MS = 2000;

    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PetShop-Database");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Settings.Db config;
    private volatile boolean healthy;
    private Connection connection;
    private Settings.Db connectionConfig;
    private long lastFailure;
    private boolean outageReported;
    private boolean driverLoaded;

    Database(Logger log, Settings.Db config) {
        this.log = log;
        this.config = config;
    }

    void configure(Settings.Db config) {
        this.config = config;
    }

    boolean healthy() {
        return healthy;
    }

    void ping() {
        submit(c -> null);
    }

    CompletableFuture<Loaded> load(UUID uuid) {
        return submit(c -> {
            Set<String> owned = new HashSet<>();
            String active = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT pet_id, 0 FROM " + owned() + " WHERE uuid = ?"
                    + " UNION ALL SELECT active_pet, 1 FROM " + player() + " WHERE uuid = ?")) {
                byte[] id = bytes(uuid);
                ps.setBytes(1, id);
                ps.setBytes(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pet = rs.getString(1);
                        if (rs.getInt(2) == 0) {
                            owned.add(pet);
                        } else {
                            active = pet;
                        }
                    }
                }
            }
            return new Loaded(owned, active);
        });
    }

    /** True when the row was inserted, false when the player already owned the pet. */
    CompletableFuture<Boolean> addOwned(UUID uuid, String pet) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + owned() + " (uuid, pet_id, acquired_at) VALUES (?, ?, ?)")) {
                ps.setBytes(1, bytes(uuid));
                ps.setString(2, pet);
                ps.setLong(3, System.currentTimeMillis());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Takes back a reservation that was not paid. */
    CompletableFuture<Boolean> unreserve(UUID uuid, String pet) {
        return submit(c -> deleteOwned(c, uuid, pet));
    }

    /** Removes ownership and clears the active pet if it was this one. */
    CompletableFuture<Boolean> removeOwned(UUID uuid, String pet) {
        return submit(c -> {
            boolean removed = deleteOwned(c, uuid, pet);
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + player() + " SET active_pet = NULL, updated_at = ? WHERE uuid = ? AND active_pet = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setBytes(2, bytes(uuid));
                ps.setString(3, pet);
                ps.executeUpdate();
            }
            return removed;
        });
    }

    CompletableFuture<Void> setActive(UUID uuid, String pet) {
        return submit(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + player() + " (uuid, active_pet, updated_at) VALUES (?, ?, ?)"
                    + " ON DUPLICATE KEY UPDATE active_pet = VALUES(active_pet), updated_at = VALUES(updated_at)")) {
                ps.setBytes(1, bytes(uuid));
                if (pet == null) {
                    ps.setNull(2, Types.VARCHAR);
                } else {
                    ps.setString(2, pet);
                }
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Runs the queued writes, then closes the connection. Waits at most 5 seconds. */
    void shutdown() {
        try {
            executor.execute(this::close);
        } catch (RejectedExecutionException ignored) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warning("Database writes did not finish within 5 seconds and were dropped.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private <T> CompletableFuture<T> submit(Query<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    T result = query.run(connection());
                    healthy = true;
                    outageReported = false;
                    future.complete(result);
                } catch (SQLException e) {
                    if (!outageReported) {
                        outageReported = true;
                        log.warning("Database error, PetShop keeps the last known state and refuses purchases until it is back: " + e.getMessage());
                    }
                    healthy = false;
                    close();
                    future.completeExceptionally(e);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Unexpected database error", e);
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    private Connection connection() throws SQLException {
        Settings.Db current = config;
        if (connection != null) {
            if (current.equals(connectionConfig) && connection.isValid(1)) {
                return connection;
            }
            close();
        }
        if (System.currentTimeMillis() - lastFailure < RECONNECT_PAUSE_MS) {
            throw new SQLException("database not reachable, next connection attempt in a moment");
        }
        try {
            if (!driverLoaded) {
                Class.forName("org.mariadb.jdbc.Driver");
                driverLoaded = true;
            }
            Properties properties = new Properties();
            properties.putAll(current.properties());
            Connection fresh = DriverManager.getConnection(current.url(), properties);
            try (Statement st = fresh.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + current.prefix() + "owned ("
                        + "uuid BINARY(16) NOT NULL, pet_id VARCHAR(64) NOT NULL, acquired_at BIGINT NOT NULL, PRIMARY KEY (uuid, pet_id))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + current.prefix() + "player ("
                        + "uuid BINARY(16) NOT NULL PRIMARY KEY, active_pet VARCHAR(64) NULL, updated_at BIGINT NOT NULL)");
            } catch (SQLException e) {
                fresh.close();
                throw e;
            }
            connection = fresh;
            connectionConfig = current;
            if (!healthy) {
                log.info("Connected to MariaDB at " + current.url() + ".");
            }
            return fresh;
        } catch (ClassNotFoundException e) {
            lastFailure = System.currentTimeMillis();
            throw new SQLException("MariaDB driver not found. Paper loads it from the libraries entry in plugin.yml at startup.", e);
        } catch (SQLException e) {
            lastFailure = System.currentTimeMillis();
            throw e;
        }
    }

    private boolean deleteOwned(Connection c, UUID uuid, String pet) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + owned() + " WHERE uuid = ? AND pet_id = ?")) {
            ps.setBytes(1, bytes(uuid));
            ps.setString(2, pet);
            return ps.executeUpdate() > 0;
        }
    }

    private String owned() {
        return connectionConfig.prefix() + "owned";
    }

    private String player() {
        return connectionConfig.prefix() + "player";
    }

    private void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // the connection is discarded either way
            }
            connection = null;
            connectionConfig = null;
        }
    }

    private static byte[] bytes(UUID uuid) {
        return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
    }
}
