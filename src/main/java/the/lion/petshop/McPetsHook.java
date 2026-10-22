package the.lion.petshop;

import fr.nocsy.mcpets.api.MCPetsAPI;
import fr.nocsy.mcpets.data.Pet;
import fr.nocsy.mcpets.data.PetDespawnReason;
import fr.nocsy.mcpets.events.PetDespawnEvent;
import fr.nocsy.mcpets.events.PetSpawnedEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** MCPets Java API. Only loaded when MCPets is installed; all calls run on the player's thread. */
final class McPetsHook implements Listener {

    private final Shop shop;

    McPetsHook(Shop shop) {
        this.shop = shop;
    }

    /** Sends other shop pets home and summons this one. True when it is out afterwards. */
    boolean equip(Player player, String mcpetsId, Set<String> shopKeys) {
        boolean alreadyOut = false;
        for (Pet active : activePets(player.getUniqueId())) {
            String id = active.getId();
            if (id.equals(mcpetsId)) {
                alreadyOut = true;
            } else if (shopKeys.contains(id.toLowerCase(Locale.ROOT))) {
                active.despawn(PetDespawnReason.REPLACED);
            }
        }
        if (alreadyOut) {
            return true;
        }
        Pet pet = MCPetsAPI.getObjectPet(mcpetsId);
        if (pet == null) {
            return false;
        }
        pet.setCheckPermission(false);
        int result = pet.spawn(player, player.getLocation());
        return result == Pet.MOB_SPAWN || result == Pet.DESPAWNED_PREVIOUS;
    }

    /** Sends all shop pets of the player home. Returns how many were out. */
    int unequip(Player player, Set<String> shopKeys) {
        int count = 0;
        for (Pet active : activePets(player.getUniqueId())) {
            if (shopKeys.contains(active.getId().toLowerCase(Locale.ROOT))) {
                active.despawn(PetDespawnReason.REVOKE);
                count++;
            }
        }
        return count;
    }

    boolean isOut(UUID player, String mcpetsId) {
        for (Pet active : activePets(player)) {
            if (active.getId().equals(mcpetsId)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSpawned(PetSpawnedEvent event) {
        Pet pet = event.getPet();
        if (pet.getOwner() != null) {
            shop.externalSpawn(pet.getOwner(), pet.getId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDespawn(PetDespawnEvent event) {
        Pet pet = event.getPet();
        if (event.getReason() == PetDespawnReason.REVOKE && pet.getOwner() != null) {
            shop.externalRevoke(pet.getOwner(), pet.getId());
        }
    }

    private static List<Pet> activePets(UUID player) {
        List<Pet> pets = MCPetsAPI.getActivePetsForPlayer(player);
        return pets == null ? List.of() : new ArrayList<>(pets);
    }
}
