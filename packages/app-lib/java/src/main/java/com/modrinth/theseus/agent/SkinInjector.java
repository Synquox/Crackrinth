package com.modrinth.theseus.agent;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * Handles injecting custom skin textures into Minecraft GameProfiles for offline mode.
 * Reads skin configuration from system properties set by the Crackrinth launcher,
 * starts a local HTTP server to serve the skin texture, and provides the base64-encoded
 * textures property that Minecraft expects.
 *
 * <p>For the local player, injects the Crackrinth-configured skin. For other players,
 * delegates to {@link SkinResolver} which asynchronously looks up skins from the
 * Mojang API — ensuring zero frame rate impact.
 */
@SuppressWarnings("CallToPrintStackTrace")
public final class SkinInjector {
    private static volatile SkinInjector instance;

    private final String variant;
    private final String playerUuid;
    private final String playerName;
    private final int serverPort;
    private final String texturesPropertyValue;
    private final boolean hasCustomSkin;

    private SkinInjector(String variant, String playerUuid, String playerName, int serverPort, boolean hasCustomSkin) {
        this.variant = variant;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.serverPort = serverPort;
        this.hasCustomSkin = hasCustomSkin;
        this.texturesPropertyValue = hasCustomSkin ? buildTexturesProperty() : null;
    }

    public static SkinInjector getInstance() {
        return instance;
    }

    /**
     * Initializes the skin injector from system properties. Called once during agent premain.
     * Returns true if skin injection is configured and ready.
     *
     * <p>In offline mode, the injector is always created (even without a custom skin)
     * so that Mojang API fallback works for other players. A custom local skin
     * is only served if {@code crackrinth.skin.path} is set.
     */
    public static boolean initialize() {
        String playerUuid = System.getProperty("crackrinth.skin.playerUuid", "");
        String playerName = System.getProperty("crackrinth.skin.playerName", "");
        boolean isOffline = Boolean.getBoolean("crackrinth.offline");

        if (!isOffline && playerUuid.isEmpty()) {
            return false;
        }

        String skinPath = System.getProperty("crackrinth.skin.path");
        String variant = System.getProperty("crackrinth.skin.variant", "classic");
        boolean hasCustomSkin = false;
        int port = -1;

        if (skinPath != null && !skinPath.isEmpty()) {
            try {
                Path path = Paths.get(skinPath);
                if (Files.exists(path)) {
                    byte[] skinBytes = Files.readAllBytes(path);
                    port = startSkinServer(skinBytes);
                    hasCustomSkin = true;
                } else {
                    System.err.println("[Crackrinth] Skin file not found: " + skinPath);
                }
            } catch (Exception e) {
                System.err.println("[Crackrinth] Failed to start skin server");
                e.printStackTrace();
            }
        }

        instance = new SkinInjector(variant, playerUuid, playerName, port, hasCustomSkin);

        if (hasCustomSkin) {
            System.out.println("[Crackrinth] Skin injector initialized - serving on port " + port + " (variant="
                    + variant + ", player=" + playerName + ")");
        } else {
            System.out.println("[Crackrinth] Skin injector initialized in offline mode"
                    + " (no custom skin, Mojang API fallback active for other players)");
        }

        return true;
    }

    private static int startSkinServer(final byte[] skinBytes) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/skin.png", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, skinBytes.length);
            OutputStream os = exchange.getResponseBody();
            os.write(skinBytes);
            os.close();
        });
        server.setExecutor(null);
        server.start();
        return server.getAddress().getPort();
    }

    private String buildTexturesProperty() {
        String skinModel = "slim".equalsIgnoreCase(variant) ? "slim" : "";
        String metadataBlock = skinModel.isEmpty() ? "" : ",\"metadata\":{\"model\":\"slim\"}";

        String json = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + playerUuid.replace("-", "") + "\""
                + ",\"profileName\":\"" + playerName + "\""
                + ",\"textures\":{\"SKIN\":{\"url\":\"http://127.0.0.1:" + serverPort + "/skin.png\""
                + metadataBlock + "}}}";

        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public String getTexturesPropertyValue() {
        return texturesPropertyValue;
    }

    public String getPlayerUuidSimple() {
        return playerUuid.replace("-", "");
    }

    public String getPlayerName() {
        return playerName;
    }

    /**
     * Called via ASM-injected bytecode from SkinTransformer. Receives the GameProfile
     * as Object to avoid compile-time authlib dependency. Uses reflection to:
     * <ol>
     *   <li>For the local player: inject the Crackrinth custom skin (if set)</li>
     *   <li>For other players: look up their skin from the Mojang API via {@link SkinResolver}
     *       (async, never blocks the game thread)</li>
     * </ol>
     */
    public static void injectSkinIntoProfile(Object gameProfile) {
        SkinInjector self = instance;
        if (gameProfile == null) {
            return;
        }

        try {
            Method getIdMethod = gameProfile.getClass().getMethod("getId");
            Object profileUuid = getIdMethod.invoke(gameProfile);

            Method getNameMethod = gameProfile.getClass().getMethod("getName");
            Object profileNameObj = getNameMethod.invoke(gameProfile);
            String profileName = profileNameObj != null ? profileNameObj.toString() : null;

            if (profileUuid == null && profileName == null) {
                return;
            }

            String profileUuidStr = profileUuid != null ? profileUuid.toString().replace("-", "") : "";

            String texturesValue = null;

            if (self != null
                    && self.hasCustomSkin
                    && !profileUuidStr.isEmpty()
                    && profileUuidStr.equalsIgnoreCase(self.getPlayerUuidSimple())) {
                texturesValue = self.getTexturesPropertyValue();
            } else {
                SkinResolver resolver = SkinResolver.getInstance();
                if (resolver != null && profileName != null && !profileName.isEmpty()) {
                    texturesValue = resolver.resolveByName(profileName);
                }
            }

            if (texturesValue == null) {
                return;
            }

            Method getPropsMethod = gameProfile.getClass().getMethod("getProperties");
            Object propertyMap = getPropsMethod.invoke(gameProfile);

            Method removeAllMethod = propertyMap.getClass().getMethod("removeAll", Object.class);
            removeAllMethod.invoke(propertyMap, "textures");

            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Constructor<?> propConstructor = propertyClass.getConstructor(String.class, String.class);
            Object texturesProp = propConstructor.newInstance("textures", texturesValue);

            Method putMethod = propertyMap.getClass().getMethod("put", Object.class, Object.class);
            putMethod.invoke(propertyMap, "textures", texturesProp);
        } catch (Exception e) {
            // Silently ignore to avoid spamming logs on every profile check
        }
    }
}
