package top.gregtao.concerto.velocity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import top.gregtao.concerto.core.bridge.CoreBridge;
import top.gregtao.concerto.core.network.SyncRecord;
import top.gregtao.concerto.core.player.MusicPlayerState;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class VelocityCoreBridge implements CoreBridge {
    private final Logger logger;
    private final Map<String, String> englishTranslations;

    public VelocityCoreBridge(Logger logger) {
        this.logger = logger;
        try (InputStream input = VelocityCoreBridge.class.getClassLoader()
                .getResourceAsStream("assets/concerto/lang/en_us.json")) {
            if (input == null) throw new IOException("English translation bundle is missing");
            Type translationsType = new TypeToken<Map<String, String>>() { }.getType();
            this.englishTranslations = new Gson().fromJson(
                    new InputStreamReader(input, StandardCharsets.UTF_8), translationsType);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load Concerto translations for Velocity", exception);
        }
    }

    @Override
    public String getTranslatable(String key, Object... args) {
        String pattern = this.englishTranslations.getOrDefault(key, key);
        if (args.length == 0) return pattern;
        try {
            return String.format(java.util.Locale.ROOT, pattern, args);
        } catch (java.util.IllegalFormatException ignored) {
            return pattern + " " + java.util.Arrays.toString(args);
        }
    }

    @Override
    public void setClientClipboard(String text) {
        throw new UnsupportedOperationException("Velocity has no local Minecraft client");
    }

    @Override
    public String getClientPlayerName() {
        throw new UnsupportedOperationException("Velocity has no local Minecraft client");
    }

    @Override
    public void sendMessageToClientPlayer(String message, boolean overlay) {
        this.logger.info("[player message] {}", message);
    }

    @Override
    public SyncRecord<MusicPlayerState> getCurrentPlayerState() {
        throw new UnsupportedOperationException("Velocity has no local Minecraft client");
    }
}
