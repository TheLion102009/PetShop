package the.lion.petshop;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Immutable list of shop pets. A new instance replaces the old one atomically. */
final class Catalog {

    /** {@code hints} are Nexo ids derived from the icon of the MCPets file (NexoId, ItemsAdder, ItemModel). */
    record Entry(String key, String mcpetsId, String permission, String name, int price, String nexoItem, List<String> hints,
                 boolean enabled, String file, String priceLabel, String itemId) {

        Entry withItemId(String id) {
            return new Entry(key, mcpetsId, permission, name, price, nexoItem, hints, enabled, file, priceLabel, id);
        }

        String labelKey() {
            return "label_" + key;
        }

        String ownedKey() {
            return "owned_" + key;
        }

        String stateKey() {
            return "state_" + key;
        }

        List<String> iconCandidates() {
            if (!nexoItem.isEmpty()) {
                return List.of(nexoItem);
            }
            Set<String> candidates = new LinkedHashSet<>(hints);
            candidates.add(key);
            candidates.add(key + "_icon");
            candidates.add("pet_" + key);
            candidates.add(key + "_pet");
            return List.copyOf(candidates);
        }

        /** Words of the id and the name, plus joined neighbours of the id ("forest_keeper" also gives "forestkeeper"). */
        Set<String> words() {
            List<String> idWords = List.copyOf(tokens(key));
            Set<String> words = new LinkedHashSet<>(idWords);
            words.addAll(tokens(name));
            for (int from = 0; from < idWords.size(); from++) {
                StringBuilder joined = new StringBuilder(idWords.get(from));
                for (int to = from + 1; to < idWords.size(); to++) {
                    joined.append(idWords.get(to));
                    words.add(joined.toString());
                }
            }
            return words;
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
                base.put(entry.ownedKey(), "false");
                base.put(entry.stateKey(), "buy");
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
                    pets.getString(path + ".nexo-item", "").trim(), iconHints(yml),
                    pets.getBoolean(path + ".enabled", true), relative, settings.priceLabel(price), null));
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

    /** Nexo ids the icon of an MCPets file points at: NexoId as written, ItemsAdder and ItemModel "a:b" as a-b, a_b and b. */
    static List<String> iconHints(YamlConfiguration yml) {
        Set<String> hints = new LinkedHashSet<>();
        String nexoId = yml.getString("Icon.NexoId", "").trim();
        if (!nexoId.isEmpty()) {
            hints.add(nexoId);
        }
        for (String path : List.of("Icon.ItemsAdder", "Icon.ItemModel")) {
            String value = yml.getString(path, "").trim().toLowerCase(Locale.ROOT);
            int colon = value.indexOf(':');
            if (colon > 0 && colon < value.length() - 1) {
                String namespace = value.substring(0, colon);
                String id = value.substring(colon + 1);
                hints.add(namespace + "-" + id);
                hints.add(namespace + "_" + id);
                hints.add(id.substring(id.lastIndexOf('/') + 1));
            } else if (!value.isEmpty()) {
                hints.add(value);
            }
        }
        return List.copyOf(hints);
    }

    /** Lower-case words of an id or name; a plural s is dropped so "cubees" matches "cubee". */
    static Set<String> tokens(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
                word = word.substring(0, word.length() - 1);
            }
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    /** How many pets use each word. */
    static Map<String, Integer> wordFrequency(List<Entry> entries) {
        Map<String, Integer> frequency = new HashMap<>();
        for (Entry entry : entries) {
            for (String word : entry.words()) {
                frequency.merge(word, 1, Integer::sum);
            }
        }
        return frequency;
    }

    /**
     * The Nexo item whose name fits the pet best, such as "cubees-icon-cat" for "cubee-ebony_cat". It must share a word
     * no other pet has, an "icon" in the name counts extra, and a tie gives no result.
     */
    static String closestIcon(Entry entry, Map<String, Integer> frequency, Collection<String> nexoIds) {
        Set<String> words = entry.words();
        String best = null;
        double bestScore = 0;
        double secondScore = 0;
        for (String nexoId : nexoIds) {
            Set<String> itemWords = tokens(nexoId);
            double score = 0;
            boolean distinctive = false;
            int unmatched = 0;
            for (String word : itemWords) {
                if (words.contains(word)) {
                    int uses = Math.max(1, frequency.getOrDefault(word, 1));
                    score += 1.0 / uses;
                    distinctive |= uses == 1;
                } else if (!word.equals("icon")) {
                    unmatched++;
                }
            }
            if (!distinctive) {
                continue;
            }
            score += (itemWords.contains("icon") ? 0.5 : 0) - 0.01 * unmatched;
            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = nexoId;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }
        return best != null && bestScore - secondScore > 1e-9 ? best : null;
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
