package the.lion.petshop;

import org.bukkit.permissions.PermissionAttachment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cached pets of one online player. {@link #view} holds every placeholder value as a finished string,
 * rebuilt only when ownership, the active pet or the catalog changes.
 */
final class PlayerData {

    final UUID uuid;
    final AtomicBoolean purchasing = new AtomicBoolean();
    volatile Map<String, String> view;

    private Set<String> owned = Set.of();
    private String active;
    private boolean available;

    // Only touched on the player's own thread.
    long lastAction;
    PermissionAttachment attachment;
    Set<String> granted = Set.of();

    PlayerData(UUID uuid, Catalog catalog) {
        this.uuid = uuid;
        this.view = catalog.baseView;
    }

    synchronized void load(Set<String> owned, String active, Catalog catalog) {
        this.owned = Set.copyOf(owned);
        this.active = active;
        this.available = true;
        rebuild(catalog);
    }

    synchronized boolean available() {
        return available;
    }

    synchronized boolean owns(String key) {
        return owned.contains(key);
    }

    synchronized Set<String> owned() {
        return owned;
    }

    synchronized String active() {
        return active;
    }

    synchronized void addOwned(String key, Catalog catalog) {
        if (!owned.contains(key)) {
            Set<String> next = new HashSet<>(owned);
            next.add(key);
            owned = Set.copyOf(next);
            rebuild(catalog);
        }
    }

    synchronized void removeOwned(String key, Catalog catalog) {
        if (owned.contains(key)) {
            Set<String> next = new HashSet<>(owned);
            next.remove(key);
            owned = Set.copyOf(next);
        }
        if (key.equals(active)) {
            active = null;
        }
        rebuild(catalog);
    }

    /** True when the active pet changed. */
    synchronized boolean setActive(String key, Catalog catalog) {
        if (Objects.equals(active, key)) {
            return false;
        }
        active = key;
        rebuild(catalog);
        return true;
    }

    synchronized void rebuild(Catalog catalog) {
        Map<String, String> values = new HashMap<>(catalog.baseView);
        int count = 0;
        for (String key : owned) {
            Catalog.Entry entry = catalog.visibleByKey.get(key);
            if (entry != null) {
                count++;
                boolean equipped = key.equals(active);
                values.put(entry.labelKey(), equipped ? catalog.equippedLabel : catalog.equipLabel);
                values.put(entry.ownedKey(), "true");
                values.put(entry.stateKey(), equipped ? "equipped" : "owned");
            }
        }
        values.put("owned", Integer.toString(count));
        Catalog.Entry activeEntry = active == null ? null : catalog.all.get(active);
        values.put("active", activeEntry == null ? "" : activeEntry.name());
        view = values;
    }
}
