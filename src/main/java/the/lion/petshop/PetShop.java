package the.lion.petshop;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.black_ixx.playerpoints.PlayerPoints;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class PetShop extends JavaPlugin implements Listener {

    enum MenuMode { MISSING, STARTUP, AUTO, FORCE }

    private static final Pattern MCPETS_RELOAD = Pattern.compile("^/?(?:mcpets:)?(?:mcpets|pets)\\s+reload(?:\\s.*)?$", Pattern.CASE_INSENSITIVE);

    private volatile Settings settings;
    private volatile Catalog catalog = Catalog.EMPTY;
    private Database database;
    private Shop shop;
    private NexoHook nexo;
    private PetShopExpansion expansion;

    private final Object scanLock = new Object();
    private final Object menuLock = new Object();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final Set<String> warnedIcons = ConcurrentHashMap.newKeySet();
    private volatile String lastFingerprint = "";
    private volatile int petsWithoutIcon;
    private ScheduledTask rescanTask;
    private String lastMenu;

    // Global region thread only.
    private List<Catalog.Entry> rawEntries;
    private boolean startupMenuPending;
    private boolean nexoWaitOver;

    @Override
    public void onEnable() {
        PluginManager plugins = getServer().getPluginManager();
        if (!plugins.isPluginEnabled("PlayerPoints")) {
            getLogger().severe("PlayerPoints is not installed or did not start. PetShop uses it as currency and disables itself.");
            plugins.disablePlugin(this);
            return;
        }
        settings = Settings.load(this);
        for (String resource : List.of("pets.yml", "menu-template.yml")) {
            if (!new File(getDataFolder(), resource).exists()) {
                saveResource(resource, false);
            }
        }
        database = new Database(getLogger(), settings.db);
        database.ping();
        shop = new Shop(this, database, PlayerPoints.getInstance().getAPI());
        plugins.registerEvents(this, this);

        if (plugins.isPluginEnabled("MCPets")) {
            McPetsHook hook = new McPetsHook(shop);
            shop.mcpets(hook);
            plugins.registerEvents(hook, this);
        } else {
            getLogger().warning("MCPets is not installed, equip and unequip use equip-command and unequip-command.");
        }
        if (plugins.isPluginEnabled("Nexo")) {
            nexo = new NexoHook(this);
            plugins.registerEvents(nexo, this);
            if (!nexo.ready()) {
                getServer().getGlobalRegionScheduler().runDelayed(this, task -> nexoTimeout(), 20L * 60);
            }
        }
        if (plugins.isPluginEnabled("PlaceholderAPI")) {
            expansion = new PetShopExpansion(this);
            expansion.register();
        } else {
            getLogger().warning("PlaceholderAPI is not installed, the %petshop_...% placeholders of the menu are not available.");
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("petshop", "Pet shop and wardrobe", List.of("ps"), new PetShopCommand(this)));

        // GameUI bakes scroll regions into its pack at startup, so the menu file must exist before it enables (loadbefore).
        Catalog.Scan first = Catalog.scan(petsFolder(), petsFile(), settings, getLogger());
        lastFingerprint = first.fingerprint();
        rawEntries = first.entries();
        catalog = new Catalog(resolveIcons(rawEntries, settings), settings.labelEquip, settings.labelEquipped);
        boolean iconsFinal = nexo == null || nexo.ready();
        startupMenuPending = !iconsFinal;
        synchronized (menuLock) {
            writeMenu(iconsFinal ? MenuMode.STARTUP : MenuMode.MISSING, null, false);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            shop.join(player);
        }
        scheduleRescan();
    }

    @Override
    public void onDisable() {
        if (database == null) {
            return;
        }
        getServer().getAsyncScheduler().cancelTasks(this);
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        if (expansion != null) {
            expansion.unregister();
        }
        shop.shutdown();
        database.shutdown();
    }

    Settings settings() {
        return settings;
    }

    Catalog catalog() {
        return catalog;
    }

    Shop shop() {
        return shop;
    }

    /** Called by PlaceholderAPI from any thread: one map lookup on a finished string. */
    String placeholder(OfflinePlayer player, String params) {
        PlayerData data = player == null ? null : shop.players.get(player.getUniqueId());
        String value = (data != null ? data.view : catalog.baseView).get(params);
        return value != null ? value : "";
    }

    // ---- listeners ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            shop.preLogin(event.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        shop.join(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        shop.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (MCPETS_RELOAD.matcher(event.getCommand().trim()).matches()) {
            scanAfterMcpetsReload();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        if (MCPETS_RELOAD.matcher(event.getCommand().trim()).matches()) {
            scanAfterMcpetsReload();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (MCPETS_RELOAD.matcher(event.getMessage().trim()).matches() && event.getPlayer().hasPermission("mcpets.admin")) {
            scanAfterMcpetsReload();
        }
    }

    // ---- catalog ----

    private void scanAfterMcpetsReload() {
        if (scanQueued.compareAndSet(false, true)) {
            getServer().getAsyncScheduler().runDelayed(this, task -> {
                scanQueued.set(false);
                scan(null);
            }, 3, TimeUnit.SECONDS);
        }
    }

    /** Reads the MCPets files off the main thread, then installs the result on the global thread. */
    void scan(Consumer<Catalog> then) {
        getServer().getAsyncScheduler().runNow(this, task -> {
            synchronized (scanLock) {
                Catalog.Scan result = Catalog.scan(petsFolder(), petsFile(), settings, getLogger());
                lastFingerprint = result.fingerprint();
                getServer().getGlobalRegionScheduler().execute(this, () -> {
                    installCatalog(result.entries());
                    if (then != null) {
                        then.accept(catalog);
                    }
                });
            }
        });
    }

    private void scheduleRescan() {
        if (rescanTask != null) {
            rescanTask.cancel();
            rescanTask = null;
        }
        if (settings.autoRescan) {
            rescanTask = getServer().getAsyncScheduler().runAtFixedRate(this, task -> {
                if (!Catalog.fingerprint(petsFolder(), petsFile(), getLogger()).equals(lastFingerprint)) {
                    scan(null);
                }
            }, 5, 5, TimeUnit.MINUTES);
        }
    }

    private void installCatalog(List<Catalog.Entry> entries) {
        rawEntries = entries;
        Settings current = settings;
        Catalog next = new Catalog(resolveIcons(entries, current), current.labelEquip, current.labelEquipped);
        catalog = next;
        shop.refreshAll(next);
        if (!startupMenuPending) {
            generateMenu(MenuMode.AUTO, null);
        } else if (nexo == null || nexo.ready() || nexoWaitOver) {
            startupMenuPending = false;
            generateMenu(MenuMode.STARTUP, null);
        }
    }

    /** Nexo finished (re)loading its items. */
    void refreshIcons() {
        if (rawEntries != null) {
            installCatalog(rawEntries);
        }
    }

    private void nexoTimeout() {
        nexoWaitOver = true;
        if (startupMenuPending && rawEntries != null) {
            getLogger().warning("Nexo has not loaded its items after 60 seconds. The menu is written with fallback icons for now.");
            installCatalog(rawEntries);
        }
    }

    private List<Catalog.Entry> resolveIcons(List<Catalog.Entry> entries, Settings current) {
        boolean nexoReady = nexo != null && nexo.ready();
        boolean iconsFinal = nexo == null || nexoReady || nexoWaitOver;
        String fallback = current.itemFormatMaterial.replace("{material}", current.fallbackMaterial);
        List<String> nexoIds = nexoReady ? nexo.ids() : List.of();
        Map<String, Integer> frequency = Catalog.wordFrequency(entries);
        List<Catalog.Entry> resolved = new ArrayList<>(entries.size());
        int withoutIcon = 0;
        for (Catalog.Entry entry : entries) {
            List<String> candidates = entry.iconCandidates();
            String nexoId = nexoReady ? nexo.find(candidates) : null;
            if (nexoId == null && nexoReady && entry.nexoItem().isEmpty()) {
                nexoId = Catalog.closestIcon(entry, frequency, nexoIds);
                if (nexoId != null && entry.enabled() && warnedIcons.add(entry.key() + "=" + nexoId)) {
                    getLogger().info("Pet " + entry.key() + " uses Nexo item '" + nexoId + "' (closest name). Set nexo-item in pets.yml to pick another one.");
                }
            }
            if (nexoId != null) {
                resolved.add(entry.withItemId(current.itemFormatNexo.replace("{id}", nexoId)));
                continue;
            }
            if (entry.enabled()) {
                withoutIcon++;
            }
            if (iconsFinal && entry.enabled() && warnedIcons.add(entry.key())) {
                if (nexo == null) {
                    getLogger().warning("Pet " + entry.key() + " uses the fallback icon because Nexo is not installed.");
                } else if (!entry.nexoItem().isEmpty()) {
                    getLogger().warning("Pet " + entry.key() + ": Nexo item '" + entry.nexoItem() + "' from pets.yml does not exist, using the fallback icon.");
                } else {
                    getLogger().warning("Pet " + entry.key() + ": no Nexo item found (tried " + String.join(", ", candidates)
                            + " and similar names), using the fallback icon. Set nexo-item in pets.yml.");
                }
            }
            resolved.add(entry.withItemId(fallback));
        }
        petsWithoutIcon = withoutIcon;
        return resolved;
    }

    private Path petsFolder() {
        return getDataFolder().getParentFile().toPath().resolve("MCPets").resolve("Pets");
    }

    private File petsFile() {
        return new File(getDataFolder(), "pets.yml");
    }

    void status(CommandSender sender) {
        String error = database.lastError();
        String db = database.healthy() ? "connected" : error == null ? "connecting" : "not reachable (" + error + ")";
        String nexoState = nexo == null ? "not installed" : nexo.ready() ? "items loaded" : "loading items";
        Catalog current = catalog;
        shop.tell(sender, "status",
                Placeholder.unparsed("database", db),
                Placeholder.unparsed("pets", Integer.toString(current.visible.size())),
                Placeholder.unparsed("fallback", Integer.toString(petsWithoutIcon)),
                Placeholder.unparsed("nexo", nexoState),
                Placeholder.unparsed("mcpets", shop.mcpetsMode()),
                Placeholder.unparsed("papi", expansion != null ? "registered" : "not installed"),
                Placeholder.unparsed("file", settings.menuFile));
    }

    // ---- reload and menu ----

    void reload(CommandSender sender) {
        getServer().getAsyncScheduler().runNow(this, task -> {
            Settings next = Settings.load(this);
            getServer().getGlobalRegionScheduler().execute(this, () -> {
                settings = next;
                database.configure(next.db);
                warnedIcons.clear();
                scheduleRescan();
                scan(loaded -> shop.tell(sender, "reloaded", Placeholder.unparsed("count", Integer.toString(loaded.visible.size()))));
            });
        });
    }

    void generateMenu(MenuMode mode, CommandSender requester) {
        getServer().getAsyncScheduler().runNow(this, task -> {
            synchronized (menuLock) {
                writeMenu(mode, requester, true);
            }
        });
    }

    private void writeMenu(MenuMode mode, CommandSender requester, boolean runReloadCommand) {
        Settings current = settings;
        Catalog snapshot = catalog;
        if (current.menuFile.isEmpty()) {
            menuFailed(requester, "menu-file is empty in config.yml");
            return;
        }
        String content;
        try {
            List<MenuGenerator.Card> cards = new ArrayList<>(snapshot.visible.size());
            for (Catalog.Entry entry : snapshot.visible) {
                cards.add(new MenuGenerator.Card(entry.key(), entry.name(), entry.itemId(),
                        List.of(entry.priceLabel(), snapshot.equipLabel, snapshot.equippedLabel)));
            }
            content = MenuGenerator.generate(readTemplate(), cards, current.layout);
        } catch (IOException | IllegalArgumentException e) {
            menuFailed(requester, "menu-template.yml: " + e.getMessage());
            return;
        }
        boolean changed = !content.equals(lastMenu);
        try {
            Path target = Path.of(current.menuFile);
            boolean exists = Files.exists(target);
            boolean write = switch (mode) {
                case MISSING -> !exists;
                case FORCE -> true;
                case STARTUP -> !exists || current.generateOnStart;
                case AUTO -> changed;
            };
            if (!write || (mode != MenuMode.FORCE && exists && content.equals(Files.readString(target, StandardCharsets.UTF_8)))) {
                lastMenu = content;
                return;
            }
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            lastMenu = content;
            getLogger().info("Menu file " + target + " written with " + snapshot.visible.size() + " pets.");
            if (runReloadCommand && !current.menuReloadCommand.isEmpty()) {
                getServer().getGlobalRegionScheduler().execute(this, () -> dispatchConsole(current.menuReloadCommand));
            }
            if (requester != null) {
                shop.tell(requester, "generated", Placeholder.unparsed("file", target.toString()));
            }
        } catch (IOException | InvalidPathException e) {
            menuFailed(requester, current.menuFile + ": " + e);
        }
    }

    /** Runs a configured console command; a missing or failing command only logs a warning. Global thread. */
    void dispatchConsole(String command) {
        try {
            if (!getServer().dispatchCommand(getServer().getConsoleSender(), command)) {
                getLogger().warning("Console command '" + command + "' did not run (unknown command or wrong usage).");
            }
        } catch (CommandException e) {
            getLogger().warning("Console command '" + command + "' failed: " + e.getMessage());
        }
    }

    private void menuFailed(CommandSender requester, String reason) {
        getLogger().warning("The menu file was not written: " + reason);
        if (requester != null) {
            shop.tell(requester, "generate-failed", Placeholder.unparsed("reason", reason));
        }
    }

    private String readTemplate() throws IOException {
        File file = new File(getDataFolder(), "menu-template.yml");
        if (file.exists()) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
        try (InputStream in = getResource("menu-template.yml")) {
            if (in == null) {
                throw new IOException("missing in the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
