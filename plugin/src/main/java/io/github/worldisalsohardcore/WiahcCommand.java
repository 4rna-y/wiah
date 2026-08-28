package io.github.worldisalsohardcore;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/** {@code /wiahc <status|reset|reload>} */
public final class WiahcCommand implements BasicCommand {

    private static final List<String> SUB_COMMANDS = List.of("status", "reset", "reload");

    private final WorldIsAlsoHardcorePlugin plugin;
    private final ResetManager resetManager;

    public WiahcCommand(WorldIsAlsoHardcorePlugin plugin, ResetManager resetManager) {
        this.plugin = plugin;
        this.resetManager = resetManager;
    }

    @Override
    public String permission() {
        return "worldisalsohardcore.admin";
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, String @NotNull [] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage(Component.text("監視対象ワールド: " + resetManager.managedWorldNames(),
                        NamedTextColor.AQUA));
                sender.sendMessage(Component.text(
                        "shutdown-mode: " + plugin.getConfig().getString("shutdown-mode", "shutdown")
                                + " / randomize-seed: " + plugin.getConfig().getBoolean("randomize-seed", true),
                        NamedTextColor.GRAY));
            }
            case "reset" -> {
                sender.sendMessage(Component.text("ワールドリセットを開始します。", NamedTextColor.RED));
                if (!resetManager.trigger(sender.getName(), "/wiahc reset (" + sender.getName() + ")")) {
                    sender.sendMessage(Component.text("リセットを開始できませんでした。ログを確認してください。",
                            NamedTextColor.RED));
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(Component.text("config.yml を再読み込みしました。", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("使い方: /wiahc <status|reset|reload>",
                    NamedTextColor.RED));
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack source, String @NotNull [] args) {
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return SUB_COMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
