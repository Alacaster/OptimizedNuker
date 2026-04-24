package dev.firstmage.optimizednuker.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MiniHudShapeCatalog {
    private static final long CACHE_TTL_NANOS = 250_000_000L;

    private static volatile String lastError;
    private static volatile RawShapeCache rawShapeCache;

    private MiniHudShapeCatalog() {}

    static synchronized void invalidateCache() {
        rawShapeCache = null;
    }

    static String getLastError() {
        return lastError;
    }

    static synchronized List<RawShape> getRawShapes() {
        return refreshRawShapeCacheIfNeeded().shapes;
    }

    static synchronized RawShapeCache refreshRawShapeCacheIfNeeded() {
        long now = System.nanoTime();
        RawShapeCache cache = rawShapeCache;
        if (cache != null && now - cache.loadedAtNanos <= CACHE_TTL_NANOS) return cache;

        List<RawShape> shapes = loadShapesNow();
        int signature = computeSignature(shapes);
        RawShapeCache refreshed = new RawShapeCache(shapes, signature, now);
        rawShapeCache = refreshed;
        return refreshed;
    }

    private static int computeSignature(List<RawShape> shapes) {
        int hash = 1;
        for (RawShape shape : shapes) {
            hash = 31 * hash + shape.selectionKey.hashCode();
            hash = 31 * hash + shape.displayName.hashCode();
            hash = 31 * hash + shape.typeId.hashCode();
            hash = 31 * hash + Boolean.hashCode(shape.enabled);
            hash = 31 * hash + shape.json.toString().hashCode();
        }
        return hash;
    }

    private static List<RawShape> loadShapesNow() {
        try {
            if (!FabricLoader.getInstance().isModLoaded("minihud")) {
                lastError = "MiniHUD not loaded";
                return Collections.emptyList();
            }

            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) loader = MiniHudShapeCatalog.class.getClassLoader();

            Class<?> managerClass = tryLoad(loader,
                "minihud.renderer.shapes.ShapeManager",
                "fi.dy.masa.minihud.renderer.shapes.ShapeManager");
            if (managerClass == null) {
                lastError = "MiniHUD ShapeManager class not found";
                return Collections.emptyList();
            }

            Field instanceField = managerClass.getField("INSTANCE");
            Object instance = instanceField.get(null);
            Method getAllShapes = managerClass.getMethod("getAllShapes");
            @SuppressWarnings("unchecked")
            List<Object> shapes = (List<Object>) getAllShapes.invoke(instance);
            if (shapes == null || shapes.isEmpty()) {
                lastError = null;
                return Collections.emptyList();
            }

            Class<?> shapeBaseClass = tryLoad(loader,
                "minihud.renderer.shapes.ShapeBase",
                "fi.dy.masa.minihud.renderer.shapes.ShapeBase");
            if (shapeBaseClass == null) {
                lastError = "MiniHUD ShapeBase class not found";
                return Collections.emptyList();
            }

            Method getDisplayName = shapeBaseClass.getMethod("getDisplayName");
            Method isEnabled = findNoArgMethod(shapeBaseClass, "isEnabled", "isShapeEnabled");
            if (isEnabled == null) {
                lastError = "MiniHUD enabled-state method not found";
                return Collections.emptyList();
            }

            Method toJson = shapeBaseClass.getMethod("toJson");

            List<RawShape> out = new ArrayList<>(shapes.size());
            Map<String, Integer> duplicateCounts = new HashMap<>();
            lastError = null;

            for (Object shape : shapes) {
                String displayName = String.valueOf(getDisplayName.invoke(shape));
                boolean enabled = (boolean) isEnabled.invoke(shape);

                JsonObject json = (JsonObject) toJson.invoke(shape);
                if (json == null) json = new JsonObject();

                String typeId = getString(json, "type", "");
                String fingerprintInput = typeId + "\n" + displayName + "\n" + canonicalJsonString(json);
                String fingerprint = sha1Hex(fingerprintInput);
                int occurrence = duplicateCounts.merge(fingerprint, 1, Integer::sum);
                String selectionKey = "mh:" + fingerprint + ":" + occurrence;

                boolean supported = supportsType(typeId);
                String supportReason = supported ? null : "Unsupported MiniHUD shape type: " + typeId;

                out.add(new RawShape(selectionKey, displayName, typeId, enabled, json, supported, supportReason));
            }

            return out;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return Collections.emptyList();
        }
    }

    private static boolean supportsType(String typeId) {
        return switch (typeId.toLowerCase(Locale.ROOT)) {
            case "circle",
                 "sphere_blocky",
                 "can_spawn_sphere",
                 "can_despawn_sphere",
                 "despawn_sphere",
                 "adjustable_spawn_sphere",
                 "clipped_spawn_sphere_y",
                 "ellipsoid_spawn",
                 "box",
                 "centered_box" -> true;
            default -> false;
        };
    }

    private static Method findNoArgMethod(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                return owner.getMethod(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Class<?> tryLoad(ClassLoader loader, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String getString(JsonObject json, String key, String fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }
    private static String escapeJsonString(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
    private static String canonicalJsonString(JsonElement element) {
        if (element == null || element.isJsonNull()) return "null";

        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().toString();
        }

        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonicalJsonString(array.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }

        JsonObject object = element.getAsJsonObject();
        List<String> keys = new ArrayList<>(object.keySet());
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJsonString(key)).append('"').append(':');
            sb.append(canonicalJsonString(object.get(key)));
        }
        sb.append('}');
        return sb.toString();
    }

    private static String sha1Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(value.hashCode());
        }
    }

    static final class RawShape {
        final String selectionKey;
        final String displayName;
        final String typeId;
        final boolean enabled;
        final JsonObject json;
        final boolean supported;
        final String supportReason;

        RawShape(String selectionKey, String displayName, String typeId, boolean enabled, JsonObject json, boolean supported, String supportReason) {
            this.selectionKey = selectionKey;
            this.displayName = displayName;
            this.typeId = typeId;
            this.enabled = enabled;
            this.json = json;
            this.supported = supported;
            this.supportReason = supportReason;
        }

        boolean matchesAny(java.util.Set<String> selectedTokens) {
            return selectedTokens.contains(selectionKey) || selectedTokens.contains(displayName);
        }
    }

    static final class RawShapeCache {
        final List<RawShape> shapes;
        final int signature;
        final long loadedAtNanos;

        RawShapeCache(List<RawShape> shapes, int signature, long loadedAtNanos) {
            this.shapes = shapes;
            this.signature = signature;
            this.loadedAtNanos = loadedAtNanos;
        }
    }
}
