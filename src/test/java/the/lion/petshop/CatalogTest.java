package the.lion.petshop;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {

    @TempDir
    Path dir;
    private Path pets;
    private File petsFile;
    private final List<String> warnings = new ArrayList<>();
    private final Logger log = Logger.getAnonymousLogger();

    @BeforeEach
    void setUp() throws IOException {
        pets = dir.resolve("MCPets/Pets");
        Files.createDirectories(pets.resolve("sub"));
        petsFile = dir.resolve("PetShop/pets.yml").toFile();
        Files.createDirectories(petsFile.toPath().getParent());
        log.setUseParentHandlers(false);
        log.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    warnings.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        write("sub/dog.yml", "Id: Dog\nPermission: mcpets.dog\nIcon:\n  Name: '&6Golden <bold>Dog</bold>'\n  NexoId: doggo\n");
        write("cat.yml", "Permission: mcpets.cat\nIcon:\n  Name: '§aCat'\n");
        write("zebra.yml", "Id: zebra\nPermission: mcpets.zebra\nIcon:\n  DisplayName: Aardvark\n");
        write("ghost.yml", "Id: Ghost\nIcon:\n  Name: Ghost\n");
        write("zz_dup.yml", "Id: DOG\nPermission: mcpets.other\n");
        write("space.yml", "Id: My Pet\nPermission: mcpets.space\n");
        write("notes.txt", "not a pet");
    }

    @Test
    void scanReadsPetsAndRegistersThem() throws Exception {
        Catalog.Scan scan = Catalog.scan(pets, petsFile, settings("name"), log);
        List<String> keys = scan.entries().stream().map(Catalog.Entry::key).toList();
        assertEquals(List.of("zebra", "cat", "dog"), keys, "sorted by name: Aardvark, Cat, Golden Dog");

        Catalog.Entry dog = scan.entries().get(2);
        assertEquals("Dog", dog.mcpetsId());
        assertEquals("mcpets.dog", dog.permission());
        assertEquals("Golden Dog", dog.name());
        assertEquals(2000, dog.price());
        assertEquals("2000 coins", dog.priceLabel());
        assertEquals("label_dog", dog.labelKey());
        assertEquals(List.of("doggo", "dog", "dog_icon", "pet_dog", "dog_pet"), dog.iconCandidates());
        assertEquals("sub/dog.yml", dog.file());
        assertEquals("cat", scan.entries().get(1).mcpetsId(), "id falls back to the file name");
        assertEquals("Cat", scan.entries().get(1).name());

        assertEquals(1, warnings.stream().filter(w -> w.contains("without a Permission field") && w.contains("Ghost")).count());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("DOG") && w.contains("already uses this id")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("My Pet")));

        YamlConfiguration saved = YamlConfiguration.loadConfiguration(petsFile);
        assertEquals(Set.of("dog", "cat", "zebra"), saved.getConfigurationSection("pets").getKeys(false));
        assertEquals("Golden Dog", saved.getString("pets.dog.name"));
        assertEquals(2000, saved.getInt("pets.dog.price"));
        assertEquals("", saved.getString("pets.dog.nexo-item"));
        assertTrue(saved.getBoolean("pets.dog.enabled"));
    }

    @Test
    void adminChangesAreKeptAndUsed() throws Exception {
        Catalog.scan(pets, petsFile, settings("name"), log);
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(petsFile);
        yml.set("pets.cat.price", 500);
        yml.set("pets.cat.name", "Kitty");
        yml.set("pets.dog.enabled", false);
        yml.set("pets.dog.nexo-item", "custom_dog");
        yml.set("pets.removed.name", "Removed pet");
        yml.save(petsFile);
        String before = Files.readString(petsFile.toPath());

        write("new.yml", "Id: Newbie\nPermission: mcpets.new\n");
        Catalog.Scan scan = Catalog.scan(pets, petsFile, settings("price"), log);

        YamlConfiguration after = YamlConfiguration.loadConfiguration(petsFile);
        assertEquals(500, after.getInt("pets.cat.price"));
        assertEquals("Kitty", after.getString("pets.cat.name"));
        assertFalse(after.getBoolean("pets.dog.enabled"));
        assertEquals("Removed pet", after.getString("pets.removed.name"), "entries of removed pets stay");
        assertTrue(after.isConfigurationSection("pets.newbie"));
        assertNotEquals(before, Files.readString(petsFile.toPath()));

        Catalog catalog = new Catalog(scan.entries(), "Equip", "Equipped");
        assertEquals(List.of("cat", "zebra", "newbie"), catalog.visible.stream().map(Catalog.Entry::key).toList(), "sorted by price then name (zebra is called Aardvark), dog disabled");
        assertEquals("Kitty", catalog.all.get("cat").name());
        assertEquals("500 coins", catalog.all.get("cat").priceLabel());
        assertEquals(List.of("custom_dog"), catalog.all.get("dog").iconCandidates());
        assertNull(catalog.visibleByKey.get("dog"));
        assertEquals("3", catalog.baseView.get("total"));
        assertNull(catalog.all.get("removed"));
    }

    @Test
    void fingerprintChangesWithFiles() throws Exception {
        Catalog.Scan scan = Catalog.scan(pets, petsFile, settings("file"), log);
        assertEquals(List.of("cat", "sub/dog.yml", "zebra"),
                scan.entries().stream().map(e -> e.key().equals("dog") ? e.file() : e.key()).toList());
        assertEquals(scan.fingerprint(), Catalog.fingerprint(pets, petsFile, log));
        write("cat.yml", "Permission: mcpets.cat\nIcon:\n  Name: 'Renamed cat'\n");
        assertNotEquals(scan.fingerprint(), Catalog.fingerprint(pets, petsFile, log));
    }

    @Test
    void playerViewHoldsFinishedStrings() throws Exception {
        Catalog catalog = new Catalog(Catalog.scan(pets, petsFile, settings("name"), log).entries(), "Equip", "Equipped");
        PlayerData data = new PlayerData(UUID.randomUUID(), catalog);
        assertEquals("2000 coins", data.view.get("label_dog"));
        assertEquals("0", data.view.get("owned"));
        assertEquals("3", data.view.get("total"));
        assertEquals("", data.view.get("active"));

        data.load(Set.of("dog", "cat", "removed"), "dog", catalog);
        assertEquals("Equipped", data.view.get("label_dog"));
        assertEquals("Equip", data.view.get("label_cat"));
        assertEquals("2000 coins", data.view.get("label_zebra"));
        assertEquals("2", data.view.get("owned"), "only pets in the shop count");
        assertEquals("Golden Dog", data.view.get("active"));
        assertNull(data.view.get("label_unknown"));

        assertTrue(data.setActive("cat", catalog));
        assertFalse(data.setActive("cat", catalog));
        assertEquals("Equip", data.view.get("label_dog"));
        assertEquals("Equipped", data.view.get("label_cat"));

        data.removeOwned("cat", catalog);
        assertEquals("2000 coins", data.view.get("label_cat"));
        assertEquals("", data.view.get("active"));
        assertEquals("1", data.view.get("owned"));
    }

    @Test
    void namesLoseColourCodes() {
        assertEquals("Golden Dog", Catalog.clean("&6Golden <bold>Dog</bold>"));
        assertEquals("Hex Pet", Catalog.clean("&#FF00FFHex §x§f§f§0§0§f§fPet"));
        assertEquals("Fancy", Catalog.clean("<gradient:#ff0000:#00ff00>Fancy</gradient>"));
        assertEquals("", Catalog.clean(null));
    }

    private Settings settings(String sort) throws IOException, InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(resource("/config.yml"));
        config.set("sort", sort);
        YamlConfiguration messages = new YamlConfiguration();
        messages.loadFromString(resource("/messages.yml"));
        return new Settings(config, messages, log);
    }

    private void write(String name, String content) throws IOException {
        Path file = pets.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().setLastModified(System.currentTimeMillis() + name.hashCode() % 1000 + 2000);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = CatalogTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
