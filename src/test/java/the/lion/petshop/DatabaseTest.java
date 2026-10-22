package the.lion.petshop;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a real MariaDB: ./gradlew test -PtestDb=jdbc:mariadb://host:port/db -PtestDbUser=.. -PtestDbPassword=.. */
class DatabaseTest {

    private static final String URL = System.getProperty("petshop.testDb", "");
    private static final String USER = System.getProperty("petshop.testDbUser", "");
    private static final String PASSWORD = System.getProperty("petshop.testDbPassword", "");
    private static final String LIMITED_USER = System.getProperty("petshop.testDbLimitedUser", "");
    private static final String PREFIX = "pstest" + (System.nanoTime() % 100000) + "_";
    private static final Logger LOG = Logger.getAnonymousLogger();

    @BeforeAll
    static void needsDatabase() {
        assumeTrue(!URL.isEmpty(), "no test database given");
    }

    @AfterAll
    static void dropTables() throws Exception {
        if (URL.isEmpty()) {
            return;
        }
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement st = c.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS " + PREFIX + "owned");
            st.executeUpdate("DROP TABLE IF EXISTS " + PREFIX + "player");
        }
    }

    @Test
    void storesOwnershipAndActivePet() throws Exception {
        Database db = new Database(LOG, settings(USER, PASSWORD));
        AtomicInteger connects = new AtomicInteger();
        db.onConnect(connects::incrementAndGet);
        UUID uuid = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try {
            Database.Loaded empty = get(db.load(uuid));
            assertEquals(Set.of(), empty.owned());
            assertNull(empty.active());

            assertTrue(get(db.addOwned(uuid, "cubee-jupiter")));
            assertFalse(get(db.addOwned(uuid, "cubee-jupiter")), "INSERT IGNORE reports 0 rows for a pet already owned");
            assertTrue(get(db.addOwned(uuid, "cubee-mars")));
            assertTrue(get(db.addOwned(other, "cubee-sun")));
            get(db.setActive(uuid, "cubee-jupiter"));
            get(db.setActive(uuid, "cubee-mars"));

            Database.Loaded loaded = get(db.load(uuid));
            assertEquals(Set.of("cubee-jupiter", "cubee-mars"), loaded.owned());
            assertEquals("cubee-mars", loaded.active());

            assertTrue(get(db.unreserve(uuid, "cubee-jupiter")));
            assertFalse(get(db.unreserve(uuid, "cubee-jupiter")));
            assertTrue(get(db.removeOwned(uuid, "cubee-mars")));
            Database.Loaded after = get(db.load(uuid));
            assertEquals(Set.of(), after.owned());
            assertNull(after.active(), "taking the active pet clears it");
            assertEquals(Set.of("cubee-sun"), get(db.load(other)).owned());

            get(db.setActive(uuid, null));
            assertNull(get(db.load(uuid)).active());
            assertTrue(db.healthy());
            assertNull(db.lastError());
            assertEquals(1, connects.get(), "one connection for all of it");
        } finally {
            db.shutdown();
        }
    }

    @Test
    void recoversAfterBadSettings() throws Exception {
        Database db = new Database(LOG, settings(USER, "wrong-password"));
        AtomicInteger connects = new AtomicInteger();
        db.onConnect(connects::incrementAndGet);
        try {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> get(db.load(UUID.randomUUID())));
            assertNotNull(failure.getCause());
            assertFalse(db.healthy());
            assertNotNull(db.lastError());
            assertEquals(0, connects.get());

            db.configure(settings(USER, PASSWORD));
            Thread.sleep(2100);
            assertEquals(Set.of(), get(db.load(UUID.randomUUID())).owned());
            assertTrue(db.healthy());
            assertNull(db.lastError());
            assertEquals(1, connects.get());
        } finally {
            db.shutdown();
        }
    }

    @Test
    void worksWithoutCreateRightWhenTablesExist() throws Exception {
        assumeTrue(!LIMITED_USER.isEmpty(), "no user without CREATE right given");
        Database setup = new Database(LOG, settings(USER, PASSWORD));
        get(setup.load(UUID.randomUUID()));
        setup.shutdown();

        Database db = new Database(LOG, settings(LIMITED_USER, PASSWORD));
        UUID uuid = UUID.randomUUID();
        try {
            assertTrue(get(db.addOwned(uuid, "cubee-earth")));
            assertEquals(Set.of("cubee-earth"), get(db.load(uuid)).owned());
        } finally {
            db.shutdown();
        }
    }

    private static Settings.Db settings(String user, String password) {
        return new Settings.Db(URL, Map.of("user", user, "password", password, "connectTimeout", "3000"), PREFIX);
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
}
