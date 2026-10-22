package the.lion.petshop;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Immutable list of shop pets. A new instance replaces the old one atomically. */
final class Catalog {

    record Entry(String key, String mcpetsId, String permission, String name, int price, String nexoItem, String nexoHint,
                 boolean enabled, String file, String labelKey, String priceLabel, String itemId) {

        Entry withItemId(String id) {
            return new Entry(key, mcpetsId, permission, name, price, nexoItem, nexoHint, enabled, file, labelKey, priceLabel, id);
        }

        List<String> iconCandidates() {
            if (!nexoItem.isEmpty()) {
                return List.of(nexoItem);
            }
            List<String> candidates = new ArrayList<>(5);
            if (!nexoHint.isEmpty()) {
                candidates.add(nexoHint);
            }
            candidates.add(key);
            candidates.add(key + "_icon");
            candidates.add("pet_" + key);
            candidates.add(key + "_pet");
            return candidates;
        }
    }

    record Scan(List<Entry> entries, String fingerprint) {
    }

    static final Catalog EMPTY = new Catalog(List.of(), "", "");

    private static final Pattern KEY = Pattern.compile("[a-z0-9_\\-]{1,64}");
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)[&§](?:x(?:[&§][0-9a-f]){6}|#[0-9a-f]{6}|[0-9a-fk-or])");

    /** All pets that have an MCPets file, sorted, including disabled ones. */
    final List<Entry> entries;
    final List<Entry> visible;
    final Map<String, Entry> all;
    final Map<String, Entry> visibleByKey;
    /** Placeholder values for a player who owns nothing. */
    final Map<String, String> baseView;
    final String equipLabel;
    final String equippedLabel;

    Catalog(List<Entry> entries, String equipLabel, String equippedLabel) {
        this.entries = List.copyOf(entries);
        this.equipLabel = equipLabel;
        this.equippedLabel = equippedLabel;
        Map<String, Entry> allMap = new HashMap<>();
        Map<String, Entry> visibleMap = new HashMap<>();
        List<Entry> visibleList = new ArrayList<>();
        Map<String, String> base = new HashMap<>();
        for (Entry entry : entries) {
            allMap.put(entry.key(), entry);
            if (entry.enabled()) {
                visibleList.add(entry);
                visibleMap.put(entry.key(), entry);
                base.put(entry.labelKey(), entry.priceLabel());
            }
        }
        base.put("total", Integer.toString(visibleList.size()));
        base.put("owned", "0");
        base.put("active", "");
        this.all = Map.copyOf(allMap);
        this.visibleByKey = Map.copyOf(visibleMap);
        this.visible = List.copyOf(visibleList);
        this.baseView = Map.copyOf(base);
    }

    static Scan scan(Path petsDir, File petsFile, Settings settings, Logger log) {
        List<Path> files = petFiles(petsDir, log);
        if (!Files.isDirectory(petsDir)) {
            log.warning("MCPets pet folder " + petsDir + " not found, the shop is empty.");
        }
        YamlConfiguration pets = new YamlConfiguration();
        boolean petsReadable = true;
        if (petsFile.exists()) {
            try {
                pets.load(petsFile);
            } catch (IOException | InvalidConfigurationException e) {
                log.severe("pets.yml could not be read. No new pets are added to it until it is fixed: " + e.getMessage());
                petsReadable = false;
            }
        }

        Map<String, Entry> found = new LinkedHashMap<>();
        List<String> withoutPermission = new ArrayList<>();
        int added = 0;
        for (Path file : files) {
            String relative = relative(petsDir, file);
            YamlConfiguration yml = new YamlConfiguration();
            try {
                yml.load(file.toFile());
            } catch (IOException | InvalidConfigurationException e) {
                log.warning("Skipped MCPets file " + relative + ": " + e.getMessage());
                continue;
            }
            String fileName = file.getFileName().toString();
            String id = yml.getString("Id", "").trim();
            if (id.isEmpty()) {
                id = fileName.substring(0, fileName.length() - 4);
            }
            String key = id.toLowerCase(Locale.ROOT);
            if (!KEY.matcher(key).matches()) {
                log.warning("Skipped pet '" + id + "' (" + relative + "): PetShop needs an id of at most 64 letters, digits, _ or -.");
                continue;
            }
            if (found.containsKey(key)) {
                log.warning("Skipped pet '" + id + "' (" + relative + "): another MCPets file already uses this id.");
                continue;
            }
            String permission = yml.getString("Permission", "").trim();
            if (permission.isEmpty()) {
                withoutPermission.add(id + " (" + relative + ")");
                continue;
            }
            String iconName = yml.getString("Icon.Name", "");
            if (iconName.isBlank()) {
                iconName = yml.getString("Icon.DisplayName", "");
            }
            String mcpetsName = clean(iconName);
            if (mcpetsName.isEmpty()) {
                mcpetsName = id;
            }

            String path = "pets." + key;
            if (petsReadable && !pets.isConfigurationSection(path)) {
                pets.set(path + ".name", mcpetsName);
                pets.set(path + ".price", settings.defaultPrice);
                pets.set(path + ".nexo-item", "");
                pets.set(path + ".enabled", true);
                added++;
            }
            String name = clean(pets.getString(path + ".name", ""));
            int price = Math.max(0, pets.getInt(path + ".price", settings.defaultPrice));
            found.put(key, new Entry(key, id, permission, name.isEmpty() ? mcpetsName : name, price,
                    pets.getString(path + ".nexo-item", "").trim(), yml.getString("Icon.NexoId", "").trim(),
                    pets.getBoolean(path + ".enabled", true), relative, "label_" + key, settings.priceLabel(price), null));
        }
        if (!withoutPermission.isEmpty()) {
            log.warning("Skipped " + withoutPermission.size() + " MCPets pet(s) without a Permission field: " + String.join(", ", withoutPermission));
        }
        if (added > 0) {
            try {
                pets.save(petsFile);
                log.info("Added " + added + " new pet(s) to pets.yml.");
            } catch (IOException e) {
                log.warning("Could not save pets.yml: " + e.getMessage());
            }
        }

        Comparator<Entry> byName = Comparator.comparing((Entry e) -> e.name().toLowerCase(Locale.ROOT)).thenComparing(Entry::key);
        Comparator<Entry> order = switch (settings.sort) {
            case NAME -> byName;
            case PRICE -> Comparator.comparingInt(Entry::price).thenComparing(byName);
            case FILE -> Comparator.comparing(Entry::file).thenComparing(Entry::key);
        };
        List<Entry> sorted = new ArrayList<>(found.values());
        sorted.sort(order);
        return new Scan(sorted, fingerprint(petsDir, files, petsFile));
    }

    /** Changes when an MCPets pet file or pets.yml is added, removed or modified. */
    static String fingerprint(Path petsDir, File petsFile, Logger log) {
        return fingerprint(petsDir, petFiles(petsDir, log), petsFile);
    }

    static boolean validKey(String key) {
        return KEY.matcher(key).matches();
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return Settings.MINI.stripTags(LEGACY_COLOR.matcher(raw).replaceAll("")).strip();
    }

    private static String fingerprint(Path petsDir, List<Path> files, File petsFile) {
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            File f = file.toFile();
            sb.append(relative(petsDir, file)).append(':').append(f.lastModified()).append(':').append(f.length()).append('\n');
        }
        return sb.append("pets.yml:").append(petsFile.lastModified()).append(':').append(petsFile.length()).toString();
    }

    private static List<Path> petFiles(Path petsDir, Logger log) {
        if (!Files.isDirectory(petsDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(petsDir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(p -> relative(petsDir, p)))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            log.warning("Could not read the MCPets pet folder " + petsDir + ": " + e.getMessage());
            return List.of();
        }
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }
}
