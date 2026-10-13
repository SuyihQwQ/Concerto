package top.gregtao.concerto.core.http.bilibili;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import top.gregtao.concerto.core.enums.Sources;
import top.gregtao.concerto.core.http.HttpApiClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

public class BilibiliApiClient extends HttpApiClient {
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43,
            5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16,
            24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59,
            6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };
    private static final long WBI_KEY_LIFETIME_MILLIS = 12 * 60 * 60 * 1000L;

    public static final Map<String, String> REQUEST_HEADERS = Map.of(
            "Referer", "https://www.bilibili.com/",
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36"
    );
    public static BilibiliApiClient INSTANCE = new BilibiliApiClient();
    private volatile WbiKey wbiKey;

    public BilibiliApiClient() {
        super(Sources.BILIBILI.asString(), REQUEST_HEADERS, Map.of());
    }

    public JsonObject getVideoData(String bvid) {
        JsonObject response = parseJson(this.open()
                .url("https://api.bilibili.com/x/web-interface/view?bvid=" + encode(bvid)).get());
        return getData(response, "video metadata");
    }

    public JsonObject getAudioUrl(String aid, String cid) {
        Map<String, String> params = Map.of(
                "avid", aid,
                "cid", cid,
                "fnval", "16",
                "fnver", "0",
                "fourk", "1"
        );
        JsonObject response = parseJson(this.open().url(
                "https://api.bilibili.com/x/player/wbi/playurl?" + this.encodeWbiQuery(params)).get());
        return getData(response, "DASH audio");
    }

    public String getDirectAudioUrl(String aid, String cid) {
        JsonObject dash = this.getAudioUrl(aid, cid).getAsJsonObject("dash");
        JsonArray streams = dash == null ? null : dash.getAsJsonArray("audio");
        if (streams == null || streams.isEmpty()) {
            throw new IllegalStateException("Bilibili DASH response contains no audio streams");
        }

        JsonObject selected = null;
        int selectedBandwidth = -1;
        for (int index = 0; index < streams.size(); index++) {
            JsonObject stream = streams.get(index).getAsJsonObject();
            String codecs = stream.has("codecs") ? stream.get("codecs").getAsString() : "";
            int bandwidth = stream.has("bandwidth") ? stream.get("bandwidth").getAsInt() : 0;
            // Prefer AAC-LC: the bundled decoder does not reliably decode HE-AAC.
            if ("mp4a.40.2".equals(codecs) && bandwidth > selectedBandwidth) {
                selected = stream;
                selectedBandwidth = bandwidth;
            }
        }
        if (selected == null) selected = streams.get(0).getAsJsonObject();

        String url = selected.has("baseUrl") ? selected.get("baseUrl").getAsString()
                : selected.has("base_url") ? selected.get("base_url").getAsString() : null;
        if (url == null || url.isBlank()) {
            JsonArray backups = selected.getAsJsonArray("backupUrl");
            if (backups == null) backups = selected.getAsJsonArray("backup_url");
            if (backups != null && !backups.isEmpty()) url = backups.get(0).getAsString();
        }
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Bilibili DASH audio stream contains no media URL");
        }
        return url;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private synchronized String encodeWbiQuery(Map<String, String> requestParams) {
        WbiKey key = this.getWbiKey();
        TreeMap<String, String> params = new TreeMap<>(requestParams);
        params.put("wts", Long.toString(System.currentTimeMillis() / 1000));
        String canonicalQuery = encodeQuery(params);
        params.put("w_rid", md5(canonicalQuery + key.mixinKey()));
        return encodeQuery(params);
    }

    private WbiKey getWbiKey() {
        WbiKey current = this.wbiKey;
        long now = System.currentTimeMillis();
        if (current != null && now - current.retrievedAt() < WBI_KEY_LIFETIME_MILLIS) return current;

        JsonObject response = parseJson(this.open()
                .url("https://api.bilibili.com/x/web-interface/nav").get());
        JsonObject data = response == null ? null : response.getAsJsonObject("data");
        JsonObject wbiImage = data == null ? null : data.getAsJsonObject("wbi_img");
        if (wbiImage == null || !wbiImage.has("img_url") || !wbiImage.has("sub_url")) {
            throw new IllegalStateException("Bilibili response contains no WBI signing keys");
        }
        String imageKey = keyFromUrl(wbiImage.get("img_url").getAsString());
        String subKey = keyFromUrl(wbiImage.get("sub_url").getAsString());
        String rawKey = imageKey + subKey;
        if (rawKey.length() < MIXIN_KEY_ENC_TAB.length) {
            throw new IllegalStateException("Bilibili returned invalid WBI signing keys");
        }
        StringBuilder mixinKey = new StringBuilder(MIXIN_KEY_ENC_TAB.length);
        for (int index : MIXIN_KEY_ENC_TAB) mixinKey.append(rawKey.charAt(index));
        current = new WbiKey(mixinKey.substring(0, 32), now);
        this.wbiKey = current;
        return current;
    }

    private static String keyFromUrl(String url) {
        String path = URI.create(url).getPath();
        int filenameStart = path.lastIndexOf('/') + 1;
        int extension = path.lastIndexOf('.');
        if (filenameStart >= path.length() || extension <= filenameStart) {
            throw new IllegalStateException("Bilibili returned an invalid WBI key URL");
        }
        return path.substring(filenameStart, extension);
    }

    private static String encodeQuery(TreeMap<String, String> params) {
        StringBuilder query = new StringBuilder();
        params.forEach((name, value) -> {
            if (query.length() > 0) query.append('&');
            query.append(encodeQueryComponent(name)).append('=').append(encodeQueryComponent(
                    value.replaceAll("[!'()*]", "")));
        });
        return query.toString();
    }

    private static String encodeQueryComponent(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int character = current & 0xFF;
            if ((character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9') || "-_.~".indexOf(character) >= 0) {
                encoded.append((char) character);
            } else {
                encoded.append('%').append("0123456789ABCDEF".charAt(character >>> 4))
                        .append("0123456789ABCDEF".charAt(character & 0xF));
            }
        }
        return encoded.toString();
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            for (int index = 0; index < digest.length; index++) {
                int current = digest[index] & 0xFF;
                hex[index * 2] = HEX_DIGITS[current >>> 4];
                hex[index * 2 + 1] = HEX_DIGITS[current & 0x0F];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is not available", exception);
        }
    }

    private static JsonObject getData(JsonObject response, String resource) {
        if (response == null) throw new IllegalStateException("Bilibili returned an empty response for " + resource);
        if (response.has("code") && response.get("code").getAsInt() != 0) {
            String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
            throw new IllegalStateException("Bilibili rejected " + resource + ": " + message);
        }
        JsonObject data = response.getAsJsonObject("data");
        if (data == null) throw new IllegalStateException("Bilibili response contains no data for " + resource);
        return data;
    }

    private record WbiKey(String mixinKey, long retrievedAt) {
    }
}
