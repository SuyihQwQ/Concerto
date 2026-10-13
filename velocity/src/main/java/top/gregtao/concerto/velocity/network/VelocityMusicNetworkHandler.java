package top.gregtao.concerto.velocity.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import top.gregtao.concerto.core.api.MusicJsonParsers;
import top.gregtao.concerto.core.config.PresetPlaylistsConfig;
import top.gregtao.concerto.core.config.ServerConfig;
import top.gregtao.concerto.core.enums.Sources;
import top.gregtao.concerto.core.music.Music;
import top.gregtao.concerto.core.room.MusicRoom;
import top.gregtao.concerto.core.room.agent.ServerMusicAgent;
import top.gregtao.concerto.velocity.ConcertoVelocityPlugin;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class VelocityMusicNetworkHandler {
    private static final int WAIT_LIST_MAX_SIZE = 300;
    private static final List<String> ALLOWED_SHARE_SOURCES = List.of(
            Sources.NETEASE_CLOUD.asString(), Sources.QQ_MUSIC.asString(), Sources.KUGOU_MUSIC.asString());

    private final ProxyServer proxy;
    private final Logger logger;
    private final ConcurrentMap<UUID, PendingShare> pendingAuditions = new ConcurrentHashMap<>();

    public VelocityMusicNetworkHandler(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    public void handle(VelocityPayload payload, Player player) {
        switch (payload.channel()) {
            case MUSIC_DATA -> this.receiveMusicShare(payload.string(), player);
            case MUSIC_ROOM -> this.receiveRoomCommand(payload.string(), player);
            case MUSIC_AGENT -> this.receiveAgentCommand(payload.string(), player);
            default -> this.logger.debug("Ignoring client message on server-only channel {}", payload.channel());
        }
    }

    public MusicRoom.ServerNetworkBridge createRoomBridge() {
        return new MusicRoom.ServerNetworkBridge() {
            @Override
            public void sendMessage(String targetPlayer, String translationKey, Object... args) {
                findPlayer(targetPlayer).ifPresent(player -> player.sendMessage(translatable(translationKey, args)));
            }

            @Override
            public void sendRoomCommand(String targetPlayer, MusicRoom.Command command, String payload) {
                findPlayer(targetPlayer).ifPresent(player -> send(player, VelocityPayload.Channel.MUSIC_ROOM,
                        command.name() + ":" + payload));
            }

            @Override
            public boolean hasExternalPermission(String playerName, int level) {
                return findPlayer(playerName).map(player ->
                        player.hasPermission("concerto.room.create") || player.hasPermission("concerto.admin"))
                        .orElse(false);
            }
        };
    }

    public void sendVoteRequest(String playerName) {
        findPlayer(playerName).ifPresent(player -> player.sendMessage(Component.translatable("concerto.agent.vote")
                .append(Component.text("  ["))
                .append(Component.translatable("concerto.accept")
                        .clickEvent(ClickEvent.runCommand("/musicroom agent vote true"))
                        .color(NamedTextColor.GREEN))
                .append(Component.text("]  ["))
                .append(Component.translatable("concerto.reject")
                        .clickEvent(ClickEvent.runCommand("/musicroom agent vote false"))
                        .color(NamedTextColor.RED))
                .append(Component.text("]"))));
    }

    public void sendPresetRadios(Player player) {
        this.send(player, VelocityPayload.Channel.PRESET_RADIOS, PresetPlaylistsConfig.PRESET_RADIOS.toString());
    }

    public void sendPendingAuditions(Player player) {
        if (!player.hasPermission("concerto.audit") && !player.hasPermission("concerto.admin")) return;
        this.pendingAuditions.forEach((uuid, share) -> this.sendAuditionSync(player, uuid, share.music(), false));
    }

    public void passAudition(UUID uuid, Player auditor) {
        PendingShare share = this.pendingAuditions.remove(uuid);
        if (share == null) {
            auditor.sendMessage(Component.translatable("concerto.audit.uuid_not_found"));
            return;
        }
        this.broadcastMusic(share.music(), share.sender(), share.target());
        this.syncAudition(uuid, share, true);
        auditor.sendMessage(Component.translatable("concerto.audit.pass",
                Component.text(share.sender()), Component.text(share.music().getMeta().title())));
    }

    public void rejectAudition(UUID uuid, Player auditor) {
        PendingShare share = this.pendingAuditions.remove(uuid);
        if (share == null) {
            auditor.sendMessage(Component.translatable("concerto.audit.uuid_not_found"));
            return;
        }
        findPlayer(share.sender()).ifPresent(player ->
                player.sendMessage(Component.translatable("concerto.share.rejected",
                        Component.text(share.music().getMeta().title()))));
        this.syncAudition(uuid, share, true);
        auditor.sendMessage(Component.translatable("concerto.audit.reject",
                Component.text(share.sender()), Component.text(share.music().getMeta().title())));
    }

    public void rejectAll(Player auditor) {
        this.pendingAuditions.forEach((uuid, share) -> {
            findPlayer(share.sender()).ifPresent(player ->
                    player.sendMessage(Component.translatable("concerto.share.rejected",
                            Component.text(share.music().getMeta().title()))));
            this.syncAudition(uuid, share, true);
        });
        this.pendingAuditions.clear();
        auditor.sendMessage(Component.translatable("concerto.audit.reject",
                Component.text("ALL"), Component.text("ALL")));
    }

    public void sendAuditList(Player player, int page) {
        player.sendMessage(Component.translatable("concerto.screen.audition"));
        List<Map.Entry<UUID, PendingShare>> entries = new ArrayList<>(this.pendingAuditions.entrySet());
        long requestedStart = (long) Math.max(0, page - 1) * 10;
        int start = (int) Math.min(entries.size(), requestedStart);
        int end = Math.min(entries.size(), start + 10);
        for (int index = start; index < end; index++) {
            Map.Entry<UUID, PendingShare> entry = entries.get(index);
            UUID uuid = entry.getKey();
            PendingShare share = entry.getValue();
            player.sendMessage(Component.translatable("concerto.audit.message",
                            Component.text(share.sender()), Component.text(share.music().getMeta().title()))
                    .append(Component.text("  ["))
                    .append(Component.translatable("concerto.accept")
                            .clickEvent(ClickEvent.runCommand("/concerto-server audit " + uuid))
                            .color(NamedTextColor.GREEN))
                    .append(Component.text("] ["))
                    .append(Component.translatable("concerto.reject")
                            .clickEvent(ClickEvent.runCommand("/concerto-server audit reject " + uuid))
                            .color(NamedTextColor.RED))
                    .append(Component.text("]")));
        }
        if (entries.isEmpty()) player.sendMessage(Component.text("No pending music requests."));
    }

    private void receiveRoomCommand(String body, Player player) {
        String[] args = body.split(":", 3);
        if (args.length != 3) throw new IllegalArgumentException("Invalid music room payload");
        MusicRoom.handleServerCommand(args[0], args[1], args[2], player.getUsername(), this.createRoomBridge());
    }

    private void receiveAgentCommand(String body, Player player) {
        String[] args = body.split(":", 2);
        if (args.length != 2) throw new IllegalArgumentException("Invalid music agent payload");
        ServerMusicAgent.handleServerCommand(args[0], args[1], player.getUsername());
    }

    private void receiveMusicShare(String body, Player sender) {
        String[] args = body.split("\n", 2);
        if (args.length != 2) throw new IllegalArgumentException("Invalid music share payload");
        JsonObject json = JsonParser.parseString(args[0]).getAsJsonObject();
        Music music = MusicJsonParsers.from(json);
        if (music == null || !ALLOWED_SHARE_SOURCES.contains(music.getJsonParser().name())) {
            sender.sendMessage(Component.translatable("concerto.share.unsafe"));
            return;
        }
        String target = args[1];
        if (!target.equals("@a") && findPlayer(target).isEmpty()) {
            sender.sendMessage(Component.translatable("concerto.share.c2s_player_not_found",
                    Component.text(target)));
            return;
        }
        if (target.equals("@a") && ServerConfig.INSTANCE.options.auditionRequired) {
            this.queueAudition(sender, target, music);
            return;
        }
        this.broadcastMusic(music, sender.getUsername(), target);
        sender.sendMessage(Component.translatable("concerto.share.success",
                Component.text(music.getMeta().title())));
    }

    private void queueAudition(Player sender, String target, Music music) {
        if (this.pendingAuditions.size() >= WAIT_LIST_MAX_SIZE) {
            sender.sendMessage(Component.translatable("concerto.share.failed",
                    Component.text(music.getMeta().title())));
            return;
        }
        UUID uuid = UUID.randomUUID();
        PendingShare share = new PendingShare(sender.getUsername(), target, music);
        this.pendingAuditions.put(uuid, share);
        this.syncAudition(uuid, share, false);
        sender.sendMessage(Component.translatable("concerto.share.success",
                Component.text(music.getMeta().title())));
    }

    private void broadcastMusic(Music music, String sender, String target) {
        JsonObject json = MusicJsonParsers.to(music);
        if (json == null) throw new IllegalArgumentException("Music could not be serialized");
        JsonObject metadata = json.getAsJsonObject("meta");
        if (metadata != null && metadata.has("src")) {
            metadata.addProperty("src", metadata.get("src").getAsString() + ", " + sender);
        }
        String message = json + "\n" + sender;
        if (target.equals("@a")) {
            this.proxy.getAllPlayers().forEach(player -> this.send(player, VelocityPayload.Channel.MUSIC_DATA, message));
        } else {
            findPlayer(target).ifPresentOrElse(
                    player -> this.send(player, VelocityPayload.Channel.MUSIC_DATA, message),
                    () -> findPlayer(sender).ifPresent(player -> player.sendMessage(
                            Component.translatable("concerto.share.s2c_player_not_found",
                                    Component.text(target)))));
        }
    }

    private void syncAudition(UUID uuid, PendingShare share, boolean delete) {
        for (Player player : this.proxy.getAllPlayers()) {
            if (player.hasPermission("concerto.audit") || player.hasPermission("concerto.admin")) {
                this.sendAuditionSync(player, uuid, share.music(), delete);
            }
        }
    }

    private void sendAuditionSync(Player player, UUID uuid, Music music, boolean delete) {
        String serializedMusic = delete ? "QwQ" : String.valueOf(MusicJsonParsers.to(music));
        this.send(player, VelocityPayload.Channel.AUDITION_SYNC,
                (delete ? "DEL;" : "ADD;") + uuid + ";" + serializedMusic);
    }

    private void send(Player player, VelocityPayload.Channel channel, String body) {
        player.sendPluginMessage(ConcertoVelocityPlugin.CHANNEL, new VelocityPayload(channel, body).encode());
    }

    private java.util.Optional<Player> findPlayer(String username) {
        return this.proxy.getPlayer(username);
    }

    private static Component translatable(String key, Object... args) {
        Component[] components = java.util.Arrays.stream(args)
                .map(value -> value instanceof Component component ? component : Component.text(String.valueOf(value)))
                .toArray(Component[]::new);
        return Component.translatable(key, components);
    }

    private record PendingShare(String sender, String target, Music music) {
    }
}
