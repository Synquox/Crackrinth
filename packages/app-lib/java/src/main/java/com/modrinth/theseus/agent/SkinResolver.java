package com.modrinth.theseus.agent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resolves Minecraft skins for players in offline mode by looking up their
 * skin textures from the Mojang API. Results are cached in memory and
 * lookups happen asynchronously to avoid any frame rate impact.
 *
 * <p>Resolution flow for a given player name:
 * <ol>
 *   <li>Check the in-memory cache — if a result (hit or miss) exists and is fresh, return it</li>
 *   <li>Otherwise, kick off an async lookup:
 *       GET {@code api.mojang.com/users/profiles/minecraft/{name}} → UUID,
 *       then GET {@code sessionserver.mojang.com/session/minecraft/profile/{uuid}} → textures property</li>
 *   <li>Store the result in the cache (positive or negative)</li>
 * </ol>
 *
 * <p>The first call for any player returns {@code null} (cache miss), triggering a background fetch.
 * Subsequent calls for the same player return the cached result once the fetch completes.
 * This ensures zero frame rate impact — the game thread never blocks on HTTP I/O.
 */
@SuppressWarnings("CallToPrintStackTrace")
public final class SkinResolver {
    private static volatile SkinResolver instance;

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long NEGATIVE_CACHE_TTL_MS = 60 * 1000L;
    private static final int HTTP_TIMEOUT_MS = 3000;

    private final ConcurrentHashMap<String, CachedSkin> cache = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Crackrinth-SkinResolver");
        t.setDaemon(true);
        return t;
    });

    private static class CachedSkin {
        final String texturesValue;
        final long fetchedAt;

        CachedSkin(String texturesValue) {
            this.texturesValue = texturesValue;
            this.fetchedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            long ttl = texturesValue != null ? CACHE_TTL_MS : NEGATIVE_CACHE_TTL_MS;
            return System.currentTimeMillis() - fetchedAt > ttl;
        }
    }

    private static final CachedSkin PENDING_SENTINEL = new CachedSkin(null);

    private SkinResolver() {}

    public static SkinResolver getInstance() {
        return instance;
    }

    public static void initialize() {
        instance = new SkinResolver();
        System.out.println("[Crackrinth] Skin resolver initialized for Mojang API fallback");
    }

    /**
     * Attempts to resolve the skin textures property for a player by their in-game name.
     * Returns the base64-encoded textures property value, or {@code null} if not yet resolved
     * or the player has no skin. This method never blocks — if the skin is not cached,
     * an async fetch is triggered and {@code null} is returned immediately.
     *
     * @param playerName the in-game name of the player
     * @return the base64 textures property value, or null
     */
    public String resolveByName(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }

        String key = playerName.toLowerCase();
        CachedSkin cached = cache.get(key);

        if (cached != null) {
            if (cached == PENDING_SENTINEL) {
                return null;
            }
            if (!cached.isExpired()) {
                return cached.texturesValue;
            }
        }

        if (cache.putIfAbsent(key, PENDING_SENTINEL) == null || (cached != null && cached.isExpired())) {
            cache.put(key, PENDING_SENTINEL);
            executor.submit(() -> fetchAndCache(playerName, key));
        }

        return null;
    }

    private void fetchAndCache(String playerName, String cacheKey) {
        try {
            String uuid = lookupUuid(playerName);
            if (uuid == null) {
                cache.put(cacheKey, new CachedSkin(null));
                return;
            }

            String texturesValue = lookupTextures(uuid);
            cache.put(cacheKey, new CachedSkin(texturesValue));

            if (texturesValue != null) {
                System.out.println("[Crackrinth] Resolved skin for " + playerName + " from Mojang API");
            }
        } catch (Exception e) {
            cache.put(cacheKey, new CachedSkin(null));
            System.err.println("[Crackrinth] Failed to resolve skin for " + playerName + ": " + e.getMessage());
        }
    }

    /**
     * Looks up a player's UUID from their username using the Mojang API.
     */
    private static String lookupUuid(String playerName) throws Exception {
        String urlStr = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
        String response = httpGet(urlStr);
        if (response == null) {
            return null;
        }

        JsonElement element = JsonParser.parseString(response);
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject obj = element.getAsJsonObject();
        JsonElement idElement = obj.get("id");
        if (idElement == null || !idElement.isJsonPrimitive()) {
            return null;
        }

        return idElement.getAsString();
    }

    /**
     * Looks up a player's textures property from their UUID using the Mojang session server.
     */
    private static String lookupTextures(String uuid) throws Exception {
        String urlStr = "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid;
        String response = httpGet(urlStr);
        if (response == null) {
            return null;
        }

        JsonElement element = JsonParser.parseString(response);
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject obj = element.getAsJsonObject();
        JsonElement propertiesElement = obj.get("properties");
        if (propertiesElement == null || !propertiesElement.isJsonArray()) {
            return null;
        }

        for (JsonElement propElement : propertiesElement.getAsJsonArray()) {
            if (!propElement.isJsonObject()) {
                continue;
            }
            JsonObject prop = propElement.getAsJsonObject();
            JsonElement nameElement = prop.get("name");
            if (nameElement != null && "textures".equals(nameElement.getAsString())) {
                JsonElement valueElement = prop.get("value");
                if (valueElement != null) {
                    return valueElement.getAsString();
                }
            }
        }

        return null;
    }

    @SuppressWarnings("deprecation")
    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Crackrinth/1.0");

            int status = conn.getResponseCode();
            if (status == 204 || status == 404) {
                return null;
            }
            if (status != 200) {
                return null;
            }

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
