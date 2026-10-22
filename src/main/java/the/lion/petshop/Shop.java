package the.lion.petshop;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Everything a player or admin does with pets. Methods taking a Player run on that player's thread. */
final class Shop {

    private record Pending(PlayerData data, long created) {
    }

    private record Reservation(UUID uuid, String key) {
    }

    private record Target(UUID uuid, String name) {
    }

    private static final long COOLDOWN_MS = 1000;

    final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Set<Reservation> reservations = ConcurrentHashMap.newKeySet();
    private final PetShop plugin;
    private final Database db;
    private final PlayerPointsAPI points;
    private volatile McPetsHook mcpets;
    private volatile boolean apiBroken;

    Shop(PetShop plugin, Database db, PlayerPointsAPI points) {
        this.plugin = plugin;
        this.db = db;
        this.points = points;
    }

    void mcpets(McPetsHook hook) {
        this.mcpets = hook;
    }

    // ---- login, join, quit ----

    /** Runs on the async pre-login thread, which may block. */
    void preLogin(UUID uuid) {
        Catalog catalog = plugin.catalog();
        PlayerData data = new PlayerData(uuid, catalog);
        Database.Loaded loaded = null;
        for (int attempt = 1; attempt <= 3 && loaded == null; attempt++) {
            try {
                loaded = db.load(uuid).get(4, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                if (attempt == 3) {
                    plugin.getLogger().warning("Could not load the pets of " + uuid + ". Purchases stay blocked for this player until the shop is opened again. "
                            + (e.getCause() != null ? e.getCause().getMessage() : e.toString()));
                } else {
                    try {
                        Thread.sleep(250L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (loaded != null) {
            data.load(loaded.owned(), loaded.active(), catalog);
        }
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.created() > 60_000);
        pending.put(uuid, new Pending(data, now));
    }

    void join(Player player) {
        UUID uuid = player.getUniqueId();
        Pending prepared = pending.remove(uuid);
        PlayerData data = prepared != null ? prepared.data() : new PlayerData(uuid, plugin.catalog());
        players.put(uuid, data);
        data.rebuild(plugin.catalog());
        applyPermissions(player, data);
        if (prepared == null) {
            refresh(player, data, null);
        }
        if (plugin.settings().restoreActiveOnJoin) {
            player.getScheduler().runDelayed(plugin, task -> restoreActive(player), null, 60L);
        }
    }

    void quit(Player player) {
        PlayerData data = players.remove(player.getUniqueId());
        if (data != null && data.attachment != null) {
            player.removeAttachment(data.attachment);
            data.attachment = null;
        }
    }

    /** New catalog: rebuild every cached placeholder value and the permissions. */
    void refreshAll(Catalog catalog) {
        for (PlayerData data : players.values()) {
            data.rebuild(catalog);
            Player player = Bukkit.getPlayer(data.uuid);
            if (player != null) {
                onPlayerThread(player, () -> applyPermissions(player, data));
            }
        }
    }

    /** Takes back unpaid reservations and removes the permission attachments. Main thread, during onDisable. */
    void shutdown() {
        for (Reservation reservation : reservations) {
            db.unreserve(reservation.uuid(), reservation.key());
        }
        reservations.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = players.get(player.getUniqueId());
            if (data != null && data.attachment != null) {
                player.removeAttachment(data.attachment);
                data.attachment = null;
            }
        }
        players.clear();
    }

    // ---- menu actions ----

    /** Reloads the player's pets from the database, so purchases from other servers show, then opens the menu. */
    void open(Player player) {
        PlayerData data = players.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerData(uuid, plugin.catalog()));
        refresh(player, data, () -> {
            String command = plugin.settings().openCommand;
            if (!command.isEmpty()) {
                player.performCommand(command);
            }
        });
    }

    void click(Player player, String key) {
        Catalog.Entry pet = plugin.catalog().visibleByKey.get(key);
        PlayerData data = players.get(player.getUniqueId());
        if (pet == null || data == null || !cooldown(data)) {
            return;
        }
        if (data.owns(key)) {
            equip(player, data, pet);
        } else {
            purchase(player, data, pet);
        }
    }

    void unequip(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        if (data == null || !cooldown(data)) {
            return;
        }
        int out = sendHome(player);
        if (data.active() == null && out == 0) {
            send(player, "no-active-pet");
            return;
        }
        setActive(data, null);
        send(player, "unequipped");
    }

    private void purchase(Player player, PlayerData data, Catalog.Entry pet) {
        if (!data.available()) {
            error(player, "data-unavailable");
            return;
        }
        if (!db.healthy()) {
            error(player, "database-unavailable");
            db.ping();
            return;
        }
        UUID uuid = player.getUniqueId();
        int price = pet.price();
        int balance = points.look(uuid);
        if (balance < price) {
            error(player, "not-enough-coins", tags(pet, price, balance));
            return;
        }
        if (!data.purchasing.compareAndSet(false, true)) {
            return;
        }
        Reservation reservation = new Reservation(uuid, pet.key());
        db.addOwned(uuid, pet.key()).whenComplete((inserted, failure) -> {
            boolean reserved = Boolean.TRUE.equals(inserted);
            if (reserved) {
                reservations.add(reservation);
            }
            Runnable playerLeft = () -> {
                if (reserved) {
                    release(reservation);
                }
                data.purchasing.set(false);
            };
            ScheduledTask task = player.getScheduler().run(plugin,
                    t -> completePurchase(player, data, pet, price, reserved, failure, reservation), playerLeft);
            if (task == null) {
                playerLeft.run();
            }
        });
    }

    private void completePurchase(Player player, PlayerData data, Catalog.Entry pet, int price, boolean reserved, Throwable failure, Reservation reservation) {
        try {
            if (failure != null) {
                error(player, "database-unavailable");
                return;
            }
            if (!reserved) {
                data.addOwned(pet.key(), plugin.catalog());
                applyPermissions(player, data);
                error(player, "already-owned", tags(pet, price, 0));
                return;
            }
            boolean paid;
            try {
                paid = points.take(player.getUniqueId(), price);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "PlayerPoints failed while charging " + player.getName() + " for " + pet.key(), e);
                paid = false;
            }
            if (!paid) {
                release(reservation);
                error(player, "not-enough-coins", tags(pet, price, points.look(player.getUniqueId())));
                return;
            }
            reservations.remove(reservation);
            data.addOwned(pet.key(), plugin.catalog());
            applyPermissions(player, data);
            send(player, "purchase-success", tags(pet, price, 0));
            sound(player, plugin.settings().purchaseSound);
        } finally {
            data.purchasing.set(false);
        }
    }

    private void release(Reservation reservation) {
        if (reservations.remove(reservation)) {
            db.unreserve(reservation.uuid(), reservation.key());
        }
    }

    private void equip(Player player, PlayerData data, Catalog.Entry pet) {
        if (pet.key().equals(data.active()) && isOut(player, pet, true)) {
            sendHome(player);
            setActive(data, null);
            send(player, "unequipped");
            return;
        }
        if (!summon(player, pet)) {
            error(player, "equip-failed", tags(pet, pet.price(), 0));
            return;
        }
        setActive(data, pet.key());
        send(player, "equipped", tags(pet, pet.price(), 0));
        sound(player, plugin.settings().equipSound);
    }

    private void restoreActive(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        String key = data == null ? null : data.active();
        Catalog.Entry pet = key == null ? null : plugin.catalog().all.get(key);
        if (pet != null && data.owns(key) && !isOut(player, pet, false)) {
            summon(player, pet);
        }
    }

    // ---- admin ----

    void give(CommandSender sender, String playerName, String rawKey) {
        String key = rawKey.toLowerCase(Locale.ROOT);
        Catalog.Entry pet = plugin.catalog().all.get(key);
        if (pet == null) {
            tell(sender, "unknown-pet", Placeholder.unparsed("pet", rawKey));
            return;
        }
        Target target = target(playerName);
        if (target == null) {
            tell(sender, "unknown-player", Placeholder.unparsed("player", playerName));
            return;
        }
        TagResolver[] tags = {Placeholder.unparsed("pet", pet.name()), Placeholder.unparsed("player", target.name())};
        db.addOwned(target.uuid(), key).whenComplete((added, failure) -> global(() -> {
            if (failure != null) {
                tell(sender, "database-unavailable");
                return;
            }
            if (!added) {
                tell(sender, "give-already", tags);
                return;
            }
            Player online = Bukkit.getPlayer(target.uuid());
            if (online != null) {
                onPlayerThread(online, () -> withData(online, data -> {
                    data.addOwned(key, plugin.catalog());
                    applyPermissions(online, data);
                }));
            } else if (plugin.settings().grantByCommand) {
                console(plugin.settings().grantCommand, target, pet.mcpetsId(), pet.permission());
            }
            tell(sender, "give-success", tags);
        }));
    }

    void take(CommandSender sender, String playerName, String rawKey) {
        String key = rawKey.toLowerCase(Locale.ROOT);
        Catalog.Entry pet = plugin.catalog().all.get(key);
        if (pet == null && !Catalog.validKey(key)) {
            tell(sender, "unknown-pet", Placeholder.unparsed("pet", rawKey));
            return;
        }
        Target target = target(playerName);
        if (target == null) {
            tell(sender, "unknown-player", Placeholder.unparsed("player", playerName));
            return;
        }
        TagResolver[] tags = {Placeholder.unparsed("pet", pet == null ? key : pet.name()), Placeholder.unparsed("player", target.name())};
        db.removeOwned(target.uuid(), key).whenComplete((removed, failure) -> global(() -> {
            if (failure != null) {
                tell(sender, "database-unavailable");
                return;
            }
            if (!removed) {
                tell(sender, "take-not-owned", tags);
                return;
            }
            Player online = Bukkit.getPlayer(target.uuid());
            if (online != null) {
                onPlayerThread(online, () -> withData(online, data -> {
                    if (key.equals(data.active())) {
                        sendHome(online);
                    }
                    data.removeOwned(key, plugin.catalog());
                    applyPermissions(online, data);
                }));
            }
            if (plugin.settings().grantByCommand && pet != null) {
                console(plugin.settings().revokeCommand, target, pet.mcpetsId(), pet.permission());
            }
            tell(sender, "take-success", tags);
        }));
    }

    // ---- MCPets events (main thread) ----

    void externalSpawn(UUID owner, String mcpetsId) {
        PlayerData data = players.get(owner);
        String key = mcpetsId.toLowerCase(Locale.ROOT);
        if (data != null && data.owns(key) && plugin.catalog().all.containsKey(key)) {
            setActive(data, key);
        }
    }

    void externalRevoke(UUID owner, String mcpetsId) {
        PlayerData data = players.get(owner);
        if (data != null && mcpetsId.toLowerCase(Locale.ROOT).equals(data.active())) {
            setActive(data, null);
        }
    }

    // ---- permissions ----

    /** Gives the MCPets permission of every owned pet. Player thread. */
    void applyPermissions(Player player, PlayerData data) {
        Settings settings = plugin.settings();
        Catalog catalog = plugin.catalog();
        Set<String> desired = new HashSet<>();
        for (String key : data.owned()) {
            Catalog.Entry entry = catalog.all.get(key);
            if (entry != null) {
                desired.add(entry.permission());
            }
        }
        if (settings.grantByCommand) {
            if (data.attachment != null) {
                player.removeAttachment(data.attachment);
                data.attachment = null;
                data.granted = Set.of();
            }
            Target target = new Target(player.getUniqueId(), player.getName());
            for (String permission : desired) {
                if (!data.granted.contains(permission) && !player.hasPermission(permission)) {
                    console(settings.grantCommand, target, "", permission);
                }
            }
            data.granted = Set.copyOf(desired);
            return;
        }
        if (data.attachment == null) {
            data.granted = Set.of();
            if (desired.isEmpty()) {
                return;
            }
            data.attachment = player.addAttachment(plugin);
        }
        for (String permission : data.granted) {
            if (!desired.contains(permission)) {
                data.attachment.unsetPermission(permission);
            }
        }
        for (String permission : desired) {
            if (!data.granted.contains(permission)) {
                data.attachment.setPermission(permission, true);
            }
        }
        data.granted = Set.copyOf(desired);
    }

    // ---- MCPets access ----

    private boolean summon(Player player, Catalog.Entry pet) {
        McPetsHook hook = hook();
        if (hook != null) {
            try {
                return hook.equip(player, pet.mcpetsId(), plugin.catalog().all.keySet());
            } catch (LinkageError e) {
                apiFailed(e);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "MCPets failed to summon " + pet.mcpetsId() + " for " + player.getName(), e);
                return false;
            }
        }
        return console(plugin.settings().equipCommand, new Target(player.getUniqueId(), player.getName()), pet.mcpetsId(), "");
    }

    /** Number of shop pets sent home, or -1 when the console command was used and the number is unknown. */
    private int sendHome(Player player) {
        McPetsHook hook = hook();
        if (hook != null) {
            try {
                return hook.unequip(player, plugin.catalog().all.keySet());
            } catch (LinkageError e) {
                apiFailed(e);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "MCPets failed to send the pets of " + player.getName() + " home", e);
                return 0;
            }
        }
        console(plugin.settings().unequipCommand, new Target(player.getUniqueId(), player.getName()), "", "");
        return -1;
    }

    private boolean isOut(Player player, Catalog.Entry pet, boolean unknown) {
        McPetsHook hook = hook();
        if (hook != null) {
            try {
                return hook.isOut(player.getUniqueId(), pet.mcpetsId());
            } catch (LinkageError e) {
                apiFailed(e);
            } catch (RuntimeException e) {
                return unknown;
            }
        }
        return unknown;
    }

    private McPetsHook hook() {
        return apiBroken || !plugin.settings().useMcpetsApi ? null : mcpets;
    }

    private void apiFailed(LinkageError e) {
        apiBroken = true;
        plugin.getLogger().log(Level.WARNING, "The installed MCPets does not match its API. PetShop uses equip-command and unequip-command from now on.", e);
    }

    // ---- helpers ----

    private void refresh(Player player, PlayerData data, Runnable then) {
        db.load(data.uuid).completeOnTimeout(null, 1500, TimeUnit.MILLISECONDS).whenComplete((loaded, failure) -> onPlayerThread(player, () -> {
            if (loaded != null && players.get(data.uuid) == data) {
                data.load(loaded.owned(), loaded.active(), plugin.catalog());
                applyPermissions(player, data);
            }
            if (then != null) {
                then.run();
            }
        }));
    }

    private void setActive(PlayerData data, String key) {
        if (data.setActive(key, plugin.catalog())) {
            db.setActive(data.uuid, key);
        }
    }

    private boolean cooldown(PlayerData data) {
        long now = System.currentTimeMillis();
        if (now - data.lastAction < COOLDOWN_MS) {
            return false;
        }
        data.lastAction = now;
        return true;
    }

    private void withData(Player player, Consumer<PlayerData> action) {
        PlayerData data = players.get(player.getUniqueId());
        if (data != null) {
            action.accept(data);
        }
    }

    private Target target(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName());
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached == null) {
            return null;
        }
        return new Target(cached.getUniqueId(), cached.getName() != null ? cached.getName() : name);
    }

    /** Runs a console command on the global thread. False when the command is not configured. */
    private boolean console(String template, Target target, String pet, String permission) {
        if (template.isEmpty()) {
            return false;
        }
        String command = template.replace("{player}", target.name()).replace("{uuid}", target.uuid().toString())
                .replace("{pet}", pet).replace("{permission}", permission);
        global(() -> plugin.dispatchConsole(command));
        return true;
    }

    private void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    private void onPlayerThread(Player player, Runnable task) {
        player.getScheduler().run(plugin, t -> task.run(), null);
    }

    private TagResolver[] tags(Catalog.Entry pet, int price, int balance) {
        return new TagResolver[]{
                Placeholder.unparsed("pet", pet.name()),
                Placeholder.unparsed("price", Integer.toString(price)),
                Placeholder.unparsed("currency", plugin.settings().currency),
                Placeholder.unparsed("balance", Integer.toString(balance))
        };
    }

    private void send(Player player, String key, TagResolver... tags) {
        Component message = plugin.settings().message(key, tags);
        if (message != null) {
            player.sendMessage(message);
        }
    }

    private void error(Player player, String key, TagResolver... tags) {
        send(player, key, tags);
        sound(player, plugin.settings().errorSound);
    }

    private static void sound(Player player, Sound sound) {
        if (sound != null) {
            player.playSound(sound);
        }
    }

    /** Sends a message on the sender's own thread. */
    void tell(CommandSender sender, String key, TagResolver... tags) {
        Component message = plugin.settings().message(key, tags);
        if (message == null) {
            return;
        }
        if (sender instanceof Player player) {
            onPlayerThread(player, () -> player.sendMessage(message));
        } else {
            global(() -> sender.sendMessage(message));
        }
    }
}
