package top.gregtao.concerto.velocity.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import top.gregtao.concerto.core.config.CacheManager;
import top.gregtao.concerto.core.room.agent.ServerMusicAgent;
import top.gregtao.concerto.velocity.ConcertoVelocityPlugin;
import top.gregtao.concerto.velocity.network.VelocityMusicNetworkHandler;

import java.util.UUID;

public final class ConcertoServerCommand implements SimpleCommand {
    private final ConcertoVelocityPlugin plugin;
    private final VelocityMusicNetworkHandler networkHandler;

    public ConcertoServerCommand(ConcertoVelocityPlugin plugin, VelocityMusicNetworkHandler networkHandler) {
        this.plugin = plugin;
        this.networkHandler = networkHandler;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length == 0) {
            invocation.source().sendMessage(Component.text(
                    "Concerto Velocity: /concerto-server reload|reload-cookie|clean-cache|fetch-radios|agent|audit"));
            return;
        }
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> {
                if (!requireAdmin(invocation)) return;
                this.plugin.reload();
                invocation.source().sendMessage(Component.translatable("concerto.success"));
            }
            case "reload-cookie" -> {
                if (!requireAdmin(invocation)) return;
                this.plugin.reloadCookies();
                invocation.source().sendMessage(Component.translatable("concerto.success"));
            }
            case "clean-cache" -> {
                if (!requireAdmin(invocation)) return;
                CacheManager.cleanAllCache();
                invocation.source().sendMessage(Component.translatable("concerto.success"));
            }
            case "fetch-radios" -> {
                if (invocation.source() instanceof Player player) {
                    this.networkHandler.sendPresetRadios(player);
                } else {
                    invocation.source().sendMessage(Component.text("This command can only be used by a player."));
                }
            }
            case "agent" -> this.executeAgent(invocation, args);
            case "audit" -> this.executeAudit(invocation, args);
            case "list" -> this.executeAuditList(invocation, args);
            default -> invocation.source().sendMessage(Component.text(
                    "Usage: /concerto-server reload|reload-cookie|clean-cache|fetch-radios|agent|audit|list"));
        }
    }

    private void executeAgent(Invocation invocation, String[] args) {
        if (!requireAdmin(invocation)) return;
        if (args.length != 2 || ServerMusicAgent.INSTANCE == null) {
            invocation.source().sendMessage(Component.text("Usage: /concerto-server agent reset|cut|stop|start"));
            return;
        }
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "reset" -> ServerMusicAgent.INSTANCE.reset();
            case "cut" -> ServerMusicAgent.INSTANCE.schedulePlayNext(0, false);
            case "stop" -> ServerMusicAgent.INSTANCE.stop();
            case "start" -> ServerMusicAgent.INSTANCE.start();
            default -> invocation.source().sendMessage(Component.text(
                    "Usage: /concerto-server agent reset|cut|stop|start"));
        }
    }

    private void executeAudit(Invocation invocation, String[] args) {
        if (!requireAudit(invocation)) return;
        if (!(invocation.source() instanceof Player player) || args.length < 2) {
            invocation.source().sendMessage(Component.text("Usage: /concerto-server audit <uuid>|reject <uuid>|reject all"));
            return;
        }
        try {
            if (args[1].equalsIgnoreCase("reject")) {
                if (args.length == 3 && args[2].equalsIgnoreCase("all")) {
                    this.networkHandler.rejectAll(player);
                } else if (args.length == 3) {
                    this.networkHandler.rejectAudition(UUID.fromString(args[2]), player);
                } else {
                    player.sendMessage(Component.text("Usage: /concerto-server audit reject <uuid>|all"));
                }
            } else {
                this.networkHandler.passAudition(UUID.fromString(args[1]), player);
            }
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Component.translatable("concerto.audit.uuid_not_found"));
        }
    }

    private void executeAuditList(Invocation invocation, String[] args) {
        if (!requireAudit(invocation)) return;
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("This command can only be used by a player."));
            return;
        }
        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                player.sendMessage(Component.text("Page must be a positive integer."));
                return;
            }
        }
        this.networkHandler.sendAuditList(player, page);
    }

    private static boolean requireAdmin(Invocation invocation) {
        if (invocation.source().hasPermission("concerto.admin")) return true;
        invocation.source().sendMessage(Component.translatable("concerto.room.permission_denied"));
        return false;
    }

    private static boolean requireAudit(Invocation invocation) {
        if (invocation.source().hasPermission("concerto.audit")
                || invocation.source().hasPermission("concerto.admin")) return true;
        invocation.source().sendMessage(Component.translatable("concerto.room.permission_denied"));
        return false;
    }
}
