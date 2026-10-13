package top.gregtao.concerto.velocity.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public record VelocityPayload(Channel channel, String string) {
    public static final String VERSION = "2";
    public static final String HANDSHAKE_STRING = "CONCERTO:" + VERSION + ":";

    public static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & 0xFFFFFF80) != 0) {
            output.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    public byte[] encode() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] body = (this.channel.id + this.string).getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, body.length);
        output.writeBytes(body);
        return output.toByteArray();
    }

    public static VelocityPayload decode(byte[] bytes) {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        int length = readVarInt(input);
        if (length < 1 || length != input.available()) {
            throw new IllegalArgumentException("Invalid Concerto payload length");
        }
        byte[] body = new byte[length];
        int bytesRead = input.read(body, 0, length);
        if (bytesRead != length) throw new IllegalArgumentException("Truncated Concerto payload");
        String value = new String(body, StandardCharsets.UTF_8);
        Channel channel = Channel.getById(value.charAt(0));
        if (channel == null) throw new IllegalArgumentException("Unknown Concerto payload channel");
        return new VelocityPayload(channel, value.substring(1));
    }

    private static int readVarInt(ByteArrayInputStream input) {
        int value = 0;
        int position = 0;
        while (position < 35) {
            int current = input.read();
            if (current < 0) throw new IllegalArgumentException("Truncated Concerto payload length");
            value |= (current & 0x7F) << position;
            if ((current & 0x80) == 0) return value;
            position += 7;
        }
        throw new IllegalArgumentException("Concerto payload length VarInt is too large");
    }

    public enum Channel {
        MUSIC_DATA('0'),
        HANDSHAKE('1'),
        AUDITION_SYNC('2'),
        MUSIC_ROOM('3'),
        PRESET_RADIOS('4'),
        MUSIC_AGENT('5');

        private final char id;

        Channel(char id) {
            this.id = id;
        }

        private static Channel getById(char id) {
            for (Channel channel : values()) {
                if (channel.id == id) return channel;
            }
            return null;
        }
    }
}
