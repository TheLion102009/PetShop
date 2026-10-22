package the.lion.petshop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Only loaded when PlaceholderAPI is installed. */
final class PetShopExpansion extends PlaceholderExpansion {

    private final PetShop plugin;
    private final String author;

    PetShopExpansion(PetShop plugin) {
        this.plugin = plugin;
        this.author = String.join(", ", plugin.getPluginMeta().getAuthors());
    }

    @Override
    public String getIdentifier() {
        return "petshop";
    }

    @Override
    public String getAuthor() {
        return author;
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return plugin.placeholder(player, params);
    }
}
