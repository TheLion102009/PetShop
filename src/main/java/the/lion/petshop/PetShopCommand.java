package the.lion.petshop;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

final class PetShopCommand implements BasicCommand {

    private static final String ADMIN = "petshop.admin";
    private static final List<String> PLAYER_COMMANDS = List.of("click", "unequip");
    private static final List<String> ALL_COMMANDS = List.of("click", "unequip", "generate", "reload", "give", "take");

    private final PetShop plugin;

    PetShopCommand(PetShop plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Player player = source.getExecutor() instanceof Player executor ? executor : sender instanceof Player self ? self : null;
        Shop shop = plugin.shop();
        if (args.length == 0) {
            if (player == null) {
                shop.tell(sender, "player-only");
            } else {
                shop.open(player);
            }
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "click" -> {
                if (player != null && args.length >= 2) {
                    shop.click(player, args[1].toLowerCase(Locale.ROOT));
                }
            }
            case "unequip" -> {
                if (player == null) {
                    shop.tell(sender, "player-only");
                } else {
                    shop.unequip(player);
                }
            }
            case "generate", "reload", "give", "take" -> {
                if (!sender.hasPermission(ADMIN)) {
                    shop.tell(sender, "no-permission");
                } else if (sub.equals("generate")) {
                    plugin.generateMenu(PetShop.MenuMode.FORCE, sender);
                } else if (sub.equals("reload")) {
                    plugin.reload(sender);
                } else if (args.length < 3) {
                    shop.tell(sender, "usage");
                } else if (sub.equals("give")) {
                    shop.give(sender, args[1], args[2]);
                } else {
                    shop.take(sender, args[1], args[2]);
                }
            }
            default -> shop.tell(sender, "usage");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        boolean admin = source.getSender().hasPermission(ADMIN);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length <= 1) {
            return filter((admin ? ALL_COMMANDS : PLAYER_COMMANDS).stream(), last);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Catalog catalog = plugin.catalog();
        if (sub.equals("click") && args.length == 2) {
            return filter(catalog.visible.stream().map(Catalog.Entry::key), last);
        }
        if (admin && (sub.equals("give") || sub.equals("take"))) {
            if (args.length == 2) {
                return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName), last);
            }
            if (args.length == 3) {
                Player target = Bukkit.getPlayerExact(args[1]);
                PlayerData data = target == null ? null : plugin.shop().players.get(target.getUniqueId());
                if (sub.equals("take") && data != null) {
                    return filter(data.owned().stream().sorted(), last);
                }
                return filter(catalog.entries.stream().map(Catalog.Entry::key), last);
            }
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "petshop.use";
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        return options.filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
