package com.modrinth.theseus.agent;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Handles injecting custom skin textures into Minecraft GameProfiles for offline mode.
 * Reads skin configuration from system properties set by the Crackrinth launcher,
 * starts a local HTTP server to serve the skin texture, and provides the base64-encoded
 * textures property that Minecraft expects.
 */
@SuppressWarnings("CallToPrintStackTrace")
public final class SkinInjector {
    private static volatile SkinInjector instance;

    private final String variant;
    private final String playerUuid;
    private final String playerName;
    private final int serverPort;
    private final String texturesPropertyValue;

    private SkinInjector(String variant, String playerUuid, String playerName, int serverPort) {
        this.variant = variant;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.serverPort = serverPort;
        this.texturesPropertyValue = buildTexturesProperty();
    }

    public static SkinInjector getInstance() {
        return instance;
    }

    /**
     * Initializes the skin injector from system properties. Called once during agent premain.
     * Returns true if skin injection is configured and ready.
     */
    public static boolean initialize() {
        String skinPath = System.getProperty("crackrinth.skin.path");
        if (skinPath == null || skinPath.isEmpty()) {
            return false;
        }

        String variant = System.getProperty("crackrinth.skin.variant", "classic");
        String playerUuid = System.getProperty("crackrinth.skin.playerUuid", "");
        String playerName = System.getProperty("crackrinth.skin.playerName", "Steve");

        try {
            Path path = Paths.get(skinPath);
            if (!Files.exists(path)) {
                System.err.println("[Crackrinth] Skin file not found: " + skinPath);
                return false;
            }

            byte[] skinBytes = Files.readAllBytes(path);
            int port = startSkinServer(skinBytes);

            instance = new SkinInjector(variant, playerUuid, playerName, port);
            System.out.println("[Crackrinth] Skin injector initialized - serving on port " + port
                    + " (variant=" + variant + ", player=" + playerName + ")");
            return true;
        } catch (Exception e) {
            System.err.println("[Crackrinth] Failed to initialize skin injector");
            e.printStackTrace();
            return false;
        }
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
        String metadataBlock = skinModel.isEmpty()
                ? ""
                : ",\"metadata\":{\"model\":\"slim\"}";

        // Build the textures JSON that Minecraft expects
        // The profileId and profileName fields are required but their exact values
        // don't matter for texture resolution
        String json = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + playerUuid.replace("-", "") + "\""
                + ",\"profileName\":\"" + playerName + "\""
                + ",\"textures\":{\"SKIN\":{\"url\":\"http://127.0.0.1:" + serverPort + "/skin.png\""
                + metadataBlock + "}}}";

        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the base64-encoded textures property value to inject into the GameProfile.
     */
    public String getTexturesPropertyValue() {
        return texturesPropertyValue;
    }

    /**
     * Returns the player UUID this skin is for, without hyphens.
     */
    public String getPlayerUuidSimple() {
        return playerUuid.replace("-", "");
    }

    public String getPlayerName() {
        return playerName;
    }

    /**
     * Called via ASM-injected bytecode from SkinTransformer. Receives the GameProfile
     * as Object to avoid compile-time authlib dependency. Uses reflection to:
     * 1. Check if the profile's UUID matches our player
     * 2. Get or create a "textures" Property with our skin data
     * 3. Add it to the profile's PropertyMap
     */
    public static void injectSkinIntoProfile(Object gameProfile) {
        SkinInjector self = instance;
        if (self == null || gameProfile == null) {
            return;
        }

        try {
            // GameProfile.getId() -> UUID
            Method getIdMethod = gameProfile.getClass().getMethod("getId");
            Object profileUuid = getIdMethod.invoke(gameProfile);

            if (profileUuid == null) {
                return;
            }

            // Check if this is the local player's profile
            String profileUuidStr = profileUuid.toString().replace("-", "");
            if (!profileUuidStr.equalsIgnoreCase(self.getPlayerUuidSimple())) {
                return;
            }

            // GameProfile.getProperties() -> PropertyMap (extends ForwardingMultimap)
            Method getPropsMethod = gameProfile.getClass().getMethod("getProperties");
            Object propertyMap = getPropsMethod.invoke(gameProfile);

            // Remove existing "textures" entries
            Method removeAllMethod = propertyMap.getClass().getMethod("removeAll", Object.class);
            removeAllMethod.invoke(propertyMap, "textures");

            // Create new Property("textures", value)
            // authlib Property is com.mojang.authlib.properties.Property
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Constructor<?> propConstructor = propertyClass.getConstructor(String.class, String.class);
            Object texturesProp = propConstructor.newInstance("textures", self.getTexturesPropertyValue());

            // PropertyMap.put(key, value)
            Method putMethod = propertyMap.getClass().getMethod("put", Object.class, Object.class);
            putMethod.invoke(propertyMap, "textures", texturesProp);

            System.out.println("[Crackrinth] Skin injected for player " + self.getPlayerName());
        } catch (Exception e) {
            System.err.println("[Crackrinth] Failed to inject skin into profile");
            e.printStackTrace();
        }
    }
}

