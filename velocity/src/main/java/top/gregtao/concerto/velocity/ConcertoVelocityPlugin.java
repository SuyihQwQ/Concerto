package top.gregtao.concerto.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import top.gregtao.concerto.core.Concerto;
import top.gregtao.concerto.core.config.PresetPlaylistsConfig;
import top.gregtao.concerto.core.config.ServerConfig;
import top.gregtao.concerto.core.http.kugou.KuGouMusicApiClient;
import top.gregtao.concerto.core.http.netease.NeteaseCloudApiClient;
import top.gregtao.concerto.core.http.qq.QQMusicApiClient;
import top.gregtao.concerto.core.room.MusicRoom;
import top.gregtao.concerto.core.room.agent.ServerMusicAgent;
import top.gregtao.concerto.core.util.ConcertoRunner;
import top.gregtao.concerto.velocity.command.ConcertoServerCommand;
import top.gregtao.concerto.velocity.network.VelocityMusicNetworkHandler;
import top.gregtao.concerto.velocity.network.VelocityPayload;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Plugin(id = "concerto", name = "Concerto", version = "2.1.0", authors = {"GregTao"})
public final class ConcertoVelocityPlugin {
    public static final ChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("concerto:main");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final VelocityMusicNetworkHandler networkHandler;

    @Inject
    public ConcertoVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.networkHandler = new VelocityMusicNetworkHandler(proxy, logger);
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        this.logger.info("Starting Concerto Velocity support");
        Concerto.registerCoreBridge(new VelocityCoreBridge(this.logger), new VelocityLoggerFactory(this.logger));
        this.proxy.getChannelRegistrar().register(CHANNEL);
        this.proxy.getEventManager().register(this, this);
        this.proxy.getCommandManager().register(
                this.proxy.getCommandManager().metaBuilder("concerto-server").plugin(this).build(),
                new ConcertoServerCommand(this, this.networkHandler));
        ServerMusicAgent.init(this.networkHandler::sendVoteRequest, this.networkHandler.createRoomBridge());
        this.reload();
        this.logger.info("Concerto is running independently on the Velocity proxy; plugin directory: {}",
                this.dataDirectory.toAbsolutePath());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        MusicRoom.ROOMS.clear();
        if (ServerMusicAgent.INSTANCE != null) {
            ServerMusicAgent.INSTANCE.dispose();
            ServerMusicAgent.INSTANCE = null;
        }
        ConcertoRunner.shutdown();
        this.proxy.getChannelRegistrar().unregister(CHANNEL);
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        this.proxy.getScheduler().buildTask(this, () -> {
            if (player.getCurrentServer().isEmpty()) return;
            String invitation = ServerConfig.INSTANCE.options.serverMusicAgent
                    && ServerConfig.INSTANCE.options.agentInviteWhenJoin ? ":Invite" : "";
            this.send(player, VelocityPayload.Channel.HANDSHAKE,
                    VelocityPayload.HANDSHAKE_STRING + "CallJoin:" + player.getUsername() + invitation);
            this.networkHandler.sendPendingAuditions(player);
            this.networkHandler.sendPresetRadios(player);
        }).delay(3, TimeUnit.SECONDS).schedule();
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        MusicRoom.serverOnPlayerDisconnect(event.getPlayer().getUsername(), this.networkHandler.createRoomBridge());
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier()) || !(event.getSource() instanceof Player player)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        try {
            this.networkHandler.handle(VelocityPayload.decode(event.getData()), player);
        } catch (RuntimeException exception) {
            this.logger.warn("Rejected malformed Concerto plugin message from {}", player.getUsername(), exception);
            player.sendMessage(Component.translatable("concerto.share.error"));
        }
    }

    public void reload() {
        ServerConfig.INSTANCE.readOptions();
        PresetPlaylistsConfig.PRESET_RADIOS.read();
        NeteaseCloudApiClient.INSTANCE.readCookie();
        QQMusicApiClient.INSTANCE.readCookie();
        KuGouMusicApiClient.INSTANCE.readCookie();
    }

    public void reloadCookies() {
        NeteaseCloudApiClient.INSTANCE.readCookie();
        QQMusicApiClient.INSTANCE.readCookie();
        KuGouMusicApiClient.INSTANCE.readCookie();
    }

    public ProxyServer getProxy() {
        return this.proxy;
    }

    public Logger getLogger() {
        return this.logger;
    }

    public VelocityMusicNetworkHandler getNetworkHandler() {
        return this.networkHandler;
    }

    public void send(Player player, VelocityPayload.Channel channel, String body) {
        player.sendPluginMessage(CHANNEL, new VelocityPayload(channel, body).encode());
    }
}