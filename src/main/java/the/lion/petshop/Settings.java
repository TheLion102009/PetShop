package the.lion.petshop;

import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Immutable snapshot of config.yml and messages.yml. Replaced as a whole on reload. */
final class Settings {

    enum Sort { NAME, PRICE, FILE }

    record Db(String url, Map<String, String> properties, String prefix) {
    }

    static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern TABLE_PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");
    private static final int[] DEFAULT_COLUMNS = {590, 945, 1300};
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    final Db db;
    final int defaultPrice;
    final String currency;
    final Sort sort;
    final String openCommand;
    final String menuFile;
    final String menuReloadCommand;
    final boolean generateOnStart;
    final MenuGenerator.Layout layout;
    final boolean grantByCommand;
    final String grantCommand;
    final String revokeCommand;
    final boolean useMcpetsApi;
    final String equipCommand;
    final String unequipCommand;
    final boolean restoreActiveOnJoin;
    final boolean autoRescan;
    final String itemFormatNexo;
    final String itemFormatMaterial;
    final String fallbackMaterial;
    final Sound purchaseSound;
    final Sound equipSound;
    final Sound errorSound;
    final String labelEquip;
    final String labelEquipped;
    private final String labelPrice;
    private final Map<String, String> messages;
    private final TagResolver prefix;

    Settings(YamlConfiguration c, YamlConfiguration m, Logger log) {
        String prefixValue = c.getString("database.table-prefix", "petshop_");
        if (!TABLE_PREFIX.matcher(prefixValue).matches()) {
            log.warning("database.table-prefix '" + prefixValue + "' may only contain letters, digits and underscores. Using petshop_.");
            prefixValue = "petshop_";
        }
        Map<String, String> props = new LinkedHashMap<>();
        props.put("user", c.getString("database.user", ""));
        props.put("password", c.getString("database.password", ""));
        props.put("connectTimeout", "3000");
        props.put("socketTimeout", "10000");
        props.put("sslMode", c.getBoolean("database.use-ssl") ? "trust" : "disable");
        ConfigurationSection extra = c.getConfigurationSection("database.properties");
        if (extra != null) {
            for (String key : extra.getKeys(false)) {
                props.put(key, String.valueOf(extra.get(key)));
            }
        }
        db = new Db("jdbc:mariadb://" + c.getString("database.host", "localhost") + ":" + c.getInt("database.port", 3306)
                + "/" + c.getString("database.database", "minecraft"), Map.copyOf(props), prefixValue);

        defaultPrice = Math.max(0, c.getInt("default-price", 2000));
        currency = c.getString("currency-name", "coins");
        sort = parse(Sort.class, c.getString("sort", "name"), Sort.NAME, "sort", log);
        openCommand = stripSlash(c.getString("open-command", "shop"));
        menuFile = c.getString("menu-file", "").trim();
        menuReloadCommand = stripSlash(c.getString("menu-reload-command", ""));
        generateOnStart = c.getBoolean("generate-on-start", true);

        List<Integer> columnList = c.getIntegerList("menu-layout.columns");
        int[] columns = columnList.isEmpty() ? DEFAULT_COLUMNS : columnList.stream().mapToInt(Integer::intValue).toArray();
        String selectedColor = c.getString("menu-layout.selected-color", "").trim();
        if (!selectedColor.isEmpty() && !COLOR.matcher(selectedColor).matches()) {
            log.warning("menu-layout.selected-color '" + selectedColor + "' is not a colour like '#FFC24A'. The active pet is not highlighted.");
            selectedColor = "";
        }
        layout = new MenuGenerator.Layout(columns, c.getInt("menu-layout.first-row-y", 668), Math.max(1, c.getInt("menu-layout.row-spacing", 240)),
                Math.max(1, c.getInt("menu-layout.min-thumb", 32)), selectedColor);

        grantByCommand = "command".equalsIgnoreCase(c.getString("grant-mode", "attachment"));
        grantCommand = stripSlash(c.getString("grant-command", ""));
        revokeCommand = stripSlash(c.getString("revoke-command", ""));
        useMcpetsApi = !"command".equalsIgnoreCase(c.getString("mcpets-hook", "api"));
        equipCommand = stripSlash(c.getString("equip-command", ""));
        unequipCommand = stripSlash(c.getString("unequip-command", ""));
        restoreActiveOnJoin = c.getBoolean("restore-active-on-join", false);
        autoRescan = c.getBoolean("auto-rescan", true);
        itemFormatNexo = c.getString("item-format-nexo", "nexo:{id}");
        itemFormatMaterial = c.getString("item-format-material", "{material}");
        fallbackMaterial = c.getString("fallback-material", "PLAYER_HEAD").trim().toUpperCase(Locale.ROOT);

        boolean sounds = c.getBoolean("sounds.enabled", true);
        purchaseSound = sound(sounds, c.getString("sounds.purchase", ""), log);
        equipSound = sound(sounds, c.getString("sounds.equip", ""), log);
        errorSound = sound(sounds, c.getString("sounds.error", ""), log);

        Map<String, String> texts = new HashMap<>();
        for (String key : m.getKeys(true)) {
            if (m.isString(key)) {
                texts.put(key, m.getString(key));
            }
        }
        messages = Map.copyOf(texts);
        prefix = Placeholder.parsed("prefix", texts.getOrDefault("prefix", ""));
        labelPrice = MINI.stripTags(texts.getOrDefault("labels.price", "{price} {currency}"));
        labelEquip = MINI.stripTags(texts.getOrDefault("labels.equip", "Equip"));
        labelEquipped = MINI.stripTags(texts.getOrDefault("labels.equipped", "Equipped"));
    }

    static Settings load(JavaPlugin plugin) {
        return new Settings(loadWithDefaults(plugin, "config.yml"), loadWithDefaults(plugin, "messages.yml"), plugin.getLogger());
    }

    /** Null when the message is empty, so admins can switch single messages off. */
    Component message(String key, TagResolver... placeholders) {
        String raw = messages.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        return MINI.deserialize(raw, TagResolver.resolver(prefix, TagResolver.resolver(placeholders)));
    }

    String priceLabel(int price) {
        return labelPrice.replace("{price}", Integer.toString(price)).replace("{currency}", currency);
    }

    /** Loads a file from the data folder and adds keys missing compared to the bundled default, keeping existing values. */
    static YamlConfiguration loadWithDefaults(JavaPlugin plugin, String name) {
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing in the plugin jar");
            }
            defaults.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("bundled " + name + " is broken", e);
        }
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration yml = new YamlConfiguration();
        try {
            yml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe(name + " could not be read, using the default values until it is fixed: " + e.getMessage());
            return defaults;
        }
        boolean changed = false;
        for (String path : defaults.getKeys(true)) {
            if (yml.contains(path, true)) {
                continue;
            }
            if (defaults.isConfigurationSection(path)) {
                yml.createSection(path);
            } else {
                yml.set(path, defaults.get(path));
            }
            yml.setComments(path, defaults.getComments(path));
            changed = true;
        }
        if (changed) {
            try {
                yml.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not add the missing keys to " + name + ": " + e.getMessage());
            }
        }
        return yml;
    }

    private static Sound sound(boolean enabled, String key, Logger log) {
        if (!enabled || key == null || key.isBlank()) {
            return null;
        }
        try {
            return Sound.sound(Key.key(key.trim().toLowerCase(Locale.ROOT)), Sound.Source.MASTER, 1f, 1f);
        } catch (InvalidKeyException e) {
            log.warning("Invalid sound '" + key + "' in config.yml, it is not played.");
            return null;
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String path, Logger log) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning(path + ": unknown value '" + value + "', using " + fallback.name().toLowerCase(Locale.ROOT) + ".");
            return fallback;
        }
    }

    private static String stripSlash(String command) {
        String trimmed = command == null ? "" : command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }
}
