package io.github.worldisalsohardcore;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /wiahc <status|reset|finale|reload|testwebhook>} */
public final class WiahcCommand implements BasicCommand {

    private static final List<String> SUB_COMMANDS =
            List.of("status", "reset", "finale", "reload", "testwebhook");

    private final WorldIsAlsoHardcorePlugin plugin;
    private final ResetManager resetManager;
    private final FinaleManager finaleManager;

    public WiahcCommand(WorldIsAlsoHardcorePlugin plugin, ResetManager resetManager,
            FinaleManager finaleManager) {
        this.plugin = plugin;
        this.resetManager = resetManager;
        this.finaleManager = finaleManager;
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
                        "on-death: " + plugin.getConfig().getString("on-death",
                                WorldIsAlsoHardcorePlugin.MODE_RESET)
                                + (finaleManager.done() ? " (ハードコアは終了済み)" : ""),
                        finaleManager.done() ? NamedTextColor.GOLD : NamedTextColor.GRAY));
                sender.sendMessage(Component.text(
                        "猶予: " + plugin.getConfig().getLong("reset-delay-seconds",
                                ResetManager.DEFAULT_RESET_DELAY_SECONDS) + "秒"
                                + " / shutdown-mode: "
                                + plugin.getConfig().getString("shutdown-mode", "shutdown")
                                + " / randomize-seed: "
                                + plugin.getConfig().getBoolean("randomize-seed", true),
                        NamedTextColor.GRAY));
                sender.sendMessage(Component.text(
                        "ワールド経過時間: " + plugin.elapsedWorldTime()
                                .map(WorldClock::formatElapsed).orElse("不明")
                                + " / Discord 通知: "
                                + (plugin.notifier().isPresent() ? "有効" : "無効"),
                        NamedTextColor.GRAY));
            }
            case "reset" -> {
                sender.sendMessage(Component.text("ワールドリセットを開始します。", NamedTextColor.RED));
                if (!resetManager.trigger(ResetCause.ofCommand(sender.getName(), uuidOf(sender)))) {
                    sender.sendMessage(Component.text("リセットを開始できませんでした。ログを確認してください。",
                            NamedTextColor.RED));
                }
            }
            case "finale" -> {
                if (finaleManager.done()) {
                    sender.sendMessage(Component.text("ハードコアは既に終了しています。",
                            NamedTextColor.YELLOW));
                    return;
                }
                sender.sendMessage(Component.text(
                        "ハードコアを終了します。ワールドは削除しません。", NamedTextColor.GOLD));
                if (!finaleManager.trigger(ResetCause.ofFinale(sender.getName(), uuidOf(sender)))) {
                    sender.sendMessage(Component.text("終了処理を開始できませんでした。ログを確認してください。",
                            NamedTextColor.RED));
                }
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(Component.text("config.yml を再読み込みしました。", NamedTextColor.GREEN));
            }
            case "testwebhook" -> testWebhook(sender);
            default -> sender.sendMessage(Component.text(
                    "使い方: /wiahc <status|reset|finale|reload|testwebhook>", NamedTextColor.RED));
        }
    }

    /**
     * ワールドを消さずに Embed だけ送ってみる。Webhook の設定確認用。
     *
     * <p>題の差し替え (finale モードなら終了用の文言) も本番と揃うよう、送るのは
     * プラグイン側の口を通す。
     */
    private void testWebhook(CommandSender sender) {
        if (plugin.notifier().isEmpty()) {
            sender.sendMessage(Component.text(
                    "Discord への通知が無効です。config.yml の discord を確認してください。",
                    NamedTextColor.RED));
            return;
        }
        plugin.notifyDiscord(ResetCause.ofTest(sender.getName(), uuidOf(sender)));
        sender.sendMessage(Component.text(
                "テスト通知を送りました。結果はサーバーのログを確認してください。", NamedTextColor.GREEN));
    }

    private static UUID uuidOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
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
