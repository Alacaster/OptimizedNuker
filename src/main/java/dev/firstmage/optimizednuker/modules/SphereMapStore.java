package dev.firstmage.optimizednuker.modules;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class SphereMapStore {
    static final String CLOSEST_MAP_RESOURCE = "nuker/sphere_map_closest_r64_float.bin";
    static final String FURTHEST_MAP_RESOURCE = "nuker/sphere_map_furthest_r64_float.bin";
    static final String TOPDOWN_MAP_RESOURCE = "nuker/sphere_map_topdown_r64_float.bin";
    static final int MAX_RADIUS = 64;
    static final int ENTRY_SIZE = 7;

    private static volatile boolean loaded;
    private static MapPoint[] closest;
    private static MapPoint[] furthest;
    private static MapPoint[] topDown;
    private static int[] distancePrefixCounts;

    private SphereMapStore() {}

    static synchronized void ensureLoaded() {
        if (loaded) return;

        try {
            closest = load(CLOSEST_MAP_RESOURCE);
            furthest = load(FURTHEST_MAP_RESOURCE);
            topDown = load(TOPDOWN_MAP_RESOURCE);
            distancePrefixCounts = buildDistancePrefixCounts(closest);
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load pregenerated sphere maps from addon resources.", e);
        }
    }

    static MapPoint[] closest() { ensureLoaded(); return closest; }
    static MapPoint[] furthest() { ensureLoaded(); return furthest; }
    static MapPoint[] topDown() { ensureLoaded(); return topDown; }

    static int voxelsBetweenIntegerDistances(int d1, int d2) {
        ensureLoaded();
        int a = clamp(Math.min(d1, d2), 0, MAX_RADIUS);
        int b = clamp(Math.max(d1, d2), 0, MAX_RADIUS);
        if (a == 0) return distancePrefixCounts[b];
        return distancePrefixCounts[b] - distancePrefixCounts[a - 1];
    }

    private static MapPoint[] load(String resourcePath) throws IOException {
        byte[] bytes;
        try (InputStream in = SphereMapStore.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) throw new IOException("Missing map resource: " + resourcePath);
            bytes = in.readAllBytes();
        }
        if (bytes.length % ENTRY_SIZE != 0) {
            throw new IOException("Invalid map resource size for " + resourcePath + ": " + bytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        MapPoint[] out = new MapPoint[bytes.length / ENTRY_SIZE];
        for (int i = 0; i < out.length; i++) {
            out[i] = new MapPoint(buffer.get(), buffer.get(), buffer.get(), buffer.getFloat());
        }
        return out;
    }

    private static int[] buildDistancePrefixCounts(MapPoint[] map) {
        int[] counts = new int[MAX_RADIUS + 1];
        for (MapPoint point : map) {
            counts[clamp((int) Math.floor(point.distance), 0, MAX_RADIUS)]++;
        }
        for (int i = 1; i < counts.length; i++) counts[i] += counts[i - 1];
        return counts;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class MapPoint {
        final byte dx;
        final byte dy;
        final byte dz;
        final float distance;

        MapPoint(byte dx, byte dy, byte dz, float distance) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.distance = distance;
        }

        static MapPoint of(int dx, int dy, int dz) {
            int sq = dx * dx + dy * dy + dz * dz;
            return new MapPoint((byte) dx, (byte) dy, (byte) dz, (float) Math.sqrt(sq));
        }

        int distanceSq() {
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
