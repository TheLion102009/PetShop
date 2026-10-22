package the.lion.petshop;

import com.nexomc.nexo.api.NexoItems;
import com.nexomc.nexo.api.events.NexoItemsLoadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.List;

/** Only loaded when Nexo is installed. */
final class NexoHook implements Listener {

    private final PetShop plugin;
    private boolean ready;

    NexoHook(PetShop plugin) {
        this.plugin = plugin;
        this.ready = !NexoItems.itemNames().isEmpty();
    }

    boolean ready() {
        return ready;
    }

    /** First candidate that exists as a Nexo item, or null. Must run on the main thread. */
    String find(List<String> candidates) {
        for (String candidate : candidates) {
            if (NexoItems.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    @EventHandler
    public void onItemsLoaded(NexoItemsLoadedEvent event) {
        ready = true;
        plugin.refreshIcons();
    }
}
