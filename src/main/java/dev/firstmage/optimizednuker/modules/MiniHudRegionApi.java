package dev.firstmage.optimizednuker.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MiniHudRegionApi {
    private static final Map<String, SnapshotCacheEntry> snapshotCache = new HashMap<>();

    private MiniHudRegionApi() {}

    static synchronized void invalidateCache() {
        MiniHudShapeCatalog.invalidateCache();
        snapshotCache.clear();
    }

    static synchronized List<ShapeHandle> listShapes() {
        List<MiniHudShapeCatalog.RawShape> rawShapes = MiniHudShapeCatalog.getRawShapes();
        if (rawShapes.isEmpty()) return Collections.emptyList();

        List<ShapeHandle> out = new ArrayList<>(rawShapes.size());
        for (MiniHudShapeCatalog.RawShape shape : rawShapes) {
            out.add(new ShapeHandle(
                shape.selectionKey,
                shape.displayName,
                shape.typeId,
                shape.enabled,
                shape.supported,
                shape.supportReason
            ));
        }
        return out;
    }

    /** Returns the underlying raw cache signature, or 0 if unavailable. Used by callers to skip stale work. */
    static synchronized int rawShapeSignature() {
        return MiniHudShapeCatalog.refreshRawShapeCacheIfNeeded().signature;
    }

    static synchronized Snapshot snapshot(Set<String> selectedTokens) {
        if (selectedTokens.isEmpty()) return Snapshot.EMPTY;

        MiniHudShapeCatalog.RawShapeCache rawCache = MiniHudShapeCatalog.refreshRawShapeCacheIfNeeded();
        if (rawCache.shapes.isEmpty()) return Snapshot.EMPTY;

        String selectedKey = normalizeSelectedTokens(selectedTokens);
        SnapshotCacheEntry cached = snapshotCache.get(selectedKey);
        if (cached != null && cached.rawSignature == rawCache.signature) {
            return cached.snapshot;
        }

        List<RegionEntry> regions = new ArrayList<>();
        for (MiniHudShapeCatalog.RawShape raw : rawCache.shapes) {
            if (!raw.matchesAny(selectedTokens)) continue;
            if (!raw.enabled) continue;
            if (!raw.supported) continue;

            RegionDef region = buildRegion(raw.typeId, raw.json);
            if (region == null) continue;

            RegionEntry entry = new RegionEntry(raw.selectionKey, raw.displayName, raw.typeId, region);
            if (entry.bounds.isEmpty()) continue;
            regions.add(entry);
        }

        Snapshot snapshot = regions.isEmpty() ? Snapshot.EMPTY : new Snapshot(regions);
        snapshotCache.put(selectedKey, new SnapshotCacheEntry(rawCache.signature, snapshot));
        return snapshot;
    }

    static String getLastError() {
        return MiniHudShapeCatalog.getLastError();
    }

    private static String normalizeSelectedTokens(Set<String> selectedTokens) {
        List<String> tokens = new ArrayList<>(selectedTokens);
        tokens.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join("\u001f", tokens);
    }

    private static RegionDef buildRegion(String typeId, JsonObject json) {
        String normalized = typeId.toLowerCase(Locale.ROOT);
        RegionDef base = switch (normalized) {
            case "sphere_blocky" -> BlockySphereRegion.fromJson(json);
            case "can_spawn_sphere", "can_despawn_sphere", "despawn_sphere", "adjustable_spawn_sphere" -> SpawnSphereRegion.fromJson(json);
            case "clipped_spawn_sphere_y" -> ClippedSpawnSphereRegion.fromJson(json);
            case "ellipsoid_spawn" -> EllipsoidSpawnRegion.fromJson(json);
            case "circle" -> CircleRegion.fromJson(json);
            case "box", "centered_box" -> BoxRegion.fromJson(json);
            default -> null;
        };
        if (base == null) return null;

        LayerFilter layerFilter = LayerFilter.fromJson(getObject(json, "layers"));
        return layerFilter == null ? base : new LayeredRegion(base, layerFilter);
    }

    private static Vec3 parseVec3(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;

        if (el.isJsonObject()) {
            JsonObject nested = el.getAsJsonObject();
            if (!nested.has("x") || !nested.has("y") || !nested.has("z")) return null;
            return new Vec3(nested.get("x").getAsDouble(), nested.get("y").getAsDouble(), nested.get("z").getAsDouble());
        }

        if (el.isJsonArray()) {
            JsonArray array = el.getAsJsonArray();
            if (array.size() < 3) return null;
            JsonElement x = array.get(0);
            JsonElement y = array.get(1);
            JsonElement z = array.get(2);
            if (!x.isJsonPrimitive() || !y.isJsonPrimitive() || !z.isJsonPrimitive()) return null;
            return new Vec3(x.getAsDouble(), y.getAsDouble(), z.getAsDouble());
        }

        return null;
    }

    private static String getString(JsonObject json, String key, String fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static int getInt(JsonObject json, String key, int fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsInt() : fallback;
    }

    private static double getDouble(JsonObject json, String key, double fallback) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsDouble() : fallback;
    }

    private static JsonObject getObject(JsonObject json, String key) {
        JsonElement el = json.get(key);
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String normalizeLayerMode(String raw) {
        return raw == null ? "ALL" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeAxis(String raw) {
        return raw == null ? "Y" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static Vec3 applySnap(Vec3 center, String snapName) {
        String normalized = snapName.toLowerCase(Locale.ROOT);
        if ("center".equals(normalized)) {
            return new Vec3(Math.floor(center.x) + 0.5, Math.floor(center.y), Math.floor(center.z) + 0.5);
        }
        if ("corner".equals(normalized)) {
            return new Vec3(Math.floor(center.x), Math.floor(center.y), Math.floor(center.z));
        }
        return center;
    }

    private static int toMinBlock(double minValue) {
        return (int) Math.ceil(minValue - 0.5);
    }

    private static int toMaxBlock(double maxValue) {
        return (int) Math.floor(maxValue - 0.5);
    }

    static final class ShapeHandle {
        final String selectionKey;
        final String displayName;
        final String typeId;
        final boolean enabled;
        final boolean supported;
        final String supportReason;

        ShapeHandle(String selectionKey, String displayName, String typeId, boolean enabled, boolean supported, String supportReason) {
            this.selectionKey = selectionKey;
            this.displayName = displayName;
            this.typeId = typeId;
            this.enabled = enabled;
            this.supported = supported;
            this.supportReason = supportReason;
        }
    }

    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(List.of());
        private static final int MAX_VOXEL_VOLUME = 4_000_000;
        private static final Direction[] DIRECTIONS = Direction.values();

        private final List<RegionEntry> regions;
        private final IntBounds unionBounds;
        private final BitSet voxelMask;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;

        private Snapshot(List<RegionEntry> regions) {
            this.regions = regions;
            this.unionBounds = computeUnionBounds(regions);
            if (regions.isEmpty() || this.unionBounds.isEmpty()) {
                this.sizeX = 0;
                this.sizeY = 0;
                this.sizeZ = 0;
                this.voxelMask = null;
            } else {
                this.sizeX = this.unionBounds.maxX - this.unionBounds.minX + 1;
                this.sizeY = this.unionBounds.maxY - this.unionBounds.minY + 1;
                this.sizeZ = this.unionBounds.maxZ - this.unionBounds.minZ + 1;
                long volume = (long) sizeX * (long) sizeY * (long) sizeZ;
                this.voxelMask = volume > 0 && volume <= MAX_VOXEL_VOLUME ? buildVoxelMask() : null;
            }
        }

        boolean hasRegions() {
            return !regions.isEmpty();
        }

        boolean contains(BlockPos pos) {
            return contains(pos.getX(), pos.getY(), pos.getZ());
        }

        boolean contains(int x, int y, int z) {
            if (regions.isEmpty() || !unionBounds.contains(x, y, z)) return false;
            if (voxelMask != null) {
                return voxelMask.get(toVoxelIndex(x, y, z));
            }
            return containsAnalytic(x, y, z);
        }

        boolean isBoundary(BlockPos pos, BlockPos.MutableBlockPos reusableNeighbor) {
            return isBoundary(pos.getX(), pos.getY(), pos.getZ(), reusableNeighbor);
        }

        boolean isBoundary(int x, int y, int z, BlockPos.MutableBlockPos reusableNeighbor) {
            if (!contains(x, y, z)) return false;
            for (Direction direction : DIRECTIONS) {
                reusableNeighbor.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                if (!contains(reusableNeighbor.getX(), reusableNeighbor.getY(), reusableNeighbor.getZ())) return true;
            }
            return false;
        }

        boolean isExteriorBoundary(BlockPos pos, BlockPos.MutableBlockPos reusableNeighbor) {
            return isExteriorBoundary(pos.getX(), pos.getY(), pos.getZ(), reusableNeighbor);
        }

        boolean isExteriorBoundary(int x, int y, int z, BlockPos.MutableBlockPos reusableNeighbor) {
            if (contains(x, y, z)) return false;
            for (Direction direction : DIRECTIONS) {
                reusableNeighbor.set(x + direction.getStepX(), y + direction.getStepY(), z + direction.getStepZ());
                if (contains(reusableNeighbor.getX(), reusableNeighbor.getY(), reusableNeighbor.getZ())) return true;
            }
            return false;
        }

        String debugSummary() {
            if (regions.isEmpty()) return "EMPTY";
            return "regions=" + regions.size()
                + " bounds=[" + unionBounds.minX + "," + unionBounds.minY + "," + unionBounds.minZ + " -> " + unionBounds.maxX + "," + unionBounds.maxY + "," + unionBounds.maxZ + "]"
                + " voxelized=" + (voxelMask != null)
                + " volume=" + ((long) sizeX * (long) sizeY * (long) sizeZ);
        }

        private BitSet buildVoxelMask() {
            int volume = sizeX * sizeY * sizeZ;
            BitSet mask = new BitSet(volume);
            for (int x = unionBounds.minX; x <= unionBounds.maxX; x++) {
                for (int y = unionBounds.minY; y <= unionBounds.maxY; y++) {
                    for (int z = unionBounds.minZ; z <= unionBounds.maxZ; z++) {
                        if (containsAnalytic(x, y, z)) mask.set(toVoxelIndex(x, y, z));
                    }
                }
            }
            return mask;
        }

        private boolean containsAnalytic(int x, int y, int z) {
            for (RegionEntry region : regions) {
                if (region.bounds.contains(x, y, z) && region.region.contains(x, y, z)) return true;
            }
            return false;
        }

        private int toVoxelIndex(int x, int y, int z) {
            int dx = x - unionBounds.minX;
            int dy = y - unionBounds.minY;
            int dz = z - unionBounds.minZ;
            return (dx * sizeY + dy) * sizeZ + dz;
        }

        private static IntBounds computeUnionBounds(List<RegionEntry> regions) {
            if (regions.isEmpty()) return IntBounds.EMPTY;

            IntBounds bounds = regions.get(0).bounds;
            int minX = bounds.minX;
            int minY = bounds.minY;
            int minZ = bounds.minZ;
            int maxX = bounds.maxX;
            int maxY = bounds.maxY;
            int maxZ = bounds.maxZ;
            for (int i = 1; i < regions.size(); i++) {
                IntBounds b = regions.get(i).bounds;
                minX = Math.min(minX, b.minX);
                minY = Math.min(minY, b.minY);
                minZ = Math.min(minZ, b.minZ);
                maxX = Math.max(maxX, b.maxX);
                maxY = Math.max(maxY, b.maxY);
                maxZ = Math.max(maxZ, b.maxZ);
            }
            return new IntBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    static final class RegionEntry {
        final String selectionKey;
        final String displayName;
        final String typeId;
        final RegionDef region;
        final IntBounds bounds;

        RegionEntry(String selectionKey, String displayName, String typeId, RegionDef region) {
            this.selectionKey = selectionKey;
            this.displayName = displayName;
            this.typeId = typeId;
            this.region = region;
            this.bounds = region.bounds();
        }
    }

    private interface RegionDef {
        boolean contains(int x, int y, int z);
        IntBounds bounds();
    }

    private static final class SnapshotCacheEntry {
        final int rawSignature;
        final Snapshot snapshot;

        SnapshotCacheEntry(int rawSignature, Snapshot snapshot) {
            this.rawSignature = rawSignature;
            this.snapshot = snapshot;
        }
    }

    private static final class LayeredRegion implements RegionDef {
        private final RegionDef delegate;
        private final LayerFilter filter;
        private final IntBounds bounds;

        private LayeredRegion(RegionDef delegate, LayerFilter filter) {
            this.delegate = delegate;
            this.filter = filter;
            this.bounds = delegate.bounds().intersect(filter.bounds());
        }

        @Override
        public boolean contains(int x, int y, int z) {
            return bounds.contains(x, y, z) && filter.contains(x, y, z) && delegate.contains(x, y, z);
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class LayerFilter {
        private final String mode;
        private final String axis;
        private final int single;
        private final int above;
        private final int below;
        private final int rangeMin;
        private final int rangeMax;
        private final IntBounds bounds;

        private LayerFilter(String mode, String axis, int single, int above, int below, int rangeMin, int rangeMax) {
            this.mode = mode;
            this.axis = axis;
            this.single = single;
            this.above = above;
            this.below = below;
            this.rangeMin = rangeMin;
            this.rangeMax = rangeMax;
            this.bounds = buildBounds();
        }

        static LayerFilter fromJson(JsonObject json) {
            if (json == null) return null;

            String mode = normalizeLayerMode(getString(json, "mode", "ALL"));
            if ("ALL".equals(mode)) return null;

            String axis = normalizeAxis(getString(json, "axis", "y"));
            return new LayerFilter(
                mode,
                axis,
                getInt(json, "layer_single", 0),
                getInt(json, "layer_above", 0),
                getInt(json, "layer_below", 0),
                getInt(json, "layer_range_min", 0),
                getInt(json, "layer_range_max", 0)
            );
        }

        boolean contains(int x, int y, int z) {
            int value = axisValue(x, y, z);
            return switch (mode) {
                case "SINGLE_LAYER" -> value == single;
                case "ALL_ABOVE" -> value >= above;
                case "ALL_BELOW" -> value <= below;
                case "LAYER_RANGE" -> value >= rangeMin && value <= rangeMax;
                default -> true;
            };
        }

        IntBounds bounds() {
            return bounds;
        }

        private int axisValue(int x, int y, int z) {
            return switch (axis) {
                case "X" -> x;
                case "Z" -> z;
                default -> y;
            };
        }

        private IntBounds buildBounds() {
            return switch (axis) {
                case "X" -> switch (mode) {
                    case "SINGLE_LAYER" -> new IntBounds(single, Integer.MIN_VALUE, Integer.MIN_VALUE, single, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    case "ALL_ABOVE" -> new IntBounds(above, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    case "ALL_BELOW" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, below, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    case "LAYER_RANGE" -> new IntBounds(rangeMin, Integer.MIN_VALUE, Integer.MIN_VALUE, rangeMax, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    default -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                };
                case "Z" -> switch (mode) {
                    case "SINGLE_LAYER" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, single, Integer.MAX_VALUE, Integer.MAX_VALUE, single);
                    case "ALL_ABOVE" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, above, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    case "ALL_BELOW" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, below);
                    case "LAYER_RANGE" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, rangeMin, Integer.MAX_VALUE, Integer.MAX_VALUE, rangeMax);
                    default -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                };
                default -> switch (mode) {
                    case "SINGLE_LAYER" -> new IntBounds(Integer.MIN_VALUE, single, Integer.MIN_VALUE, Integer.MAX_VALUE, single, Integer.MAX_VALUE);
                    case "ALL_ABOVE" -> new IntBounds(Integer.MIN_VALUE, above, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                    case "ALL_BELOW" -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, below, Integer.MAX_VALUE);
                    case "LAYER_RANGE" -> new IntBounds(Integer.MIN_VALUE, rangeMin, Integer.MIN_VALUE, Integer.MAX_VALUE, rangeMax, Integer.MAX_VALUE);
                    default -> new IntBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
                };
            };
        }
    }

    private static final class IntBounds {
        static final IntBounds EMPTY = new IntBounds(1, 1, 1, 0, 0, 0);

        final int minX;
        final int minY;
        final int minZ;
        final int maxX;
        final int maxY;
        final int maxZ;

        IntBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        boolean isEmpty() {
            return minX > maxX || minY > maxY || minZ > maxZ;
        }

        IntBounds intersect(IntBounds other) {
            int minX = Math.max(this.minX, other.minX);
            int minY = Math.max(this.minY, other.minY);
            int minZ = Math.max(this.minZ, other.minZ);
            int maxX = Math.min(this.maxX, other.maxX);
            int maxY = Math.min(this.maxY, other.maxY);
            int maxZ = Math.min(this.maxZ, other.maxZ);
            if (minX > maxX || minY > maxY || minZ > maxZ) return EMPTY;
            return new IntBounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static final class BlockySphereRegion implements RegionDef {
        private final Vec3 center;
        private final double radiusSq;
        private final IntBounds bounds;

        private BlockySphereRegion(Vec3 center, double radius) {
            this.center = center;
            this.radiusSq = radius * radius;
            int pad = (int) Math.ceil(radius) + 2;
            int cx = (int) Math.floor(center.x);
            int cy = (int) Math.floor(center.y);
            int cz = (int) Math.floor(center.z);
            this.bounds = new IntBounds(cx - pad, cy - pad, cz - pad, cx + pad, cy + pad, cz + pad);
        }

        static BlockySphereRegion fromJson(JsonObject json) {
            Vec3 rawCenter = parseVec3(json, "center");
            if (rawCenter == null) return null;
            String snap = getString(json, "snap", "center");
            double radius = getDouble(json, "radius", -1.0);
            if (radius < 0.0) return null;
            return new BlockySphereRegion(applySnap(rawCenter, snap), radius);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            double dx = x + 0.5 - center.x;
            double dy = y + 0.5 - center.y;
            double dz = z + 0.5 - center.z;
            return dx * dx + dy * dy + dz * dz <= radiusSq;
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class SpawnSphereRegion implements RegionDef {
        private final Vec3 center;
        private final double radiusSq;
        private final double margin;
        private final IntBounds bounds;

        private SpawnSphereRegion(Vec3 center, double radius, double margin) {
            this.center = center;
            this.radiusSq = radius * radius;
            this.margin = margin;
            int padXZ = (int) Math.ceil(radius + margin) + 2;
            int padY = (int) Math.ceil(radius) + 2;
            int cx = (int) Math.floor(center.x);
            int cy = (int) Math.floor(center.y);
            int cz = (int) Math.floor(center.z);
            this.bounds = new IntBounds(cx - padXZ, cy - padY, cz - padXZ, cx + padXZ, cy + padY, cz + padXZ);
        }

        static SpawnSphereRegion fromJson(JsonObject json) {
            Vec3 rawCenter = parseVec3(json, "center");
            if (rawCenter == null) return null;
            String snap = getString(json, "snap", "center");
            double radius = getDouble(json, "radius", -1.0);
            if (radius < 0.0) return null;
            double margin = getDouble(json, "margin", 1.5);
            return new SpawnSphereRegion(applySnap(rawCenter, snap), radius, margin);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            double sx = x + 0.5;
            double sy = y + 1.0;
            double sz = z + 0.5;

            if (distanceSq(sx, sy, sz, center.x, center.y, center.z) <= radiusSq) return true;

            double qx = center.x + (sx < center.x ? -margin : margin);
            double qz = center.z + (sz < center.z ? -margin : margin);
            return distanceSq(sx, sy, sz, qx, center.y, qz) <= radiusSq;
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class ClippedSpawnSphereRegion implements RegionDef {
        private final SpawnSphereRegion delegate;
        private final Vec3 center;
        private final double radius;
        private final double topTrim;
        private final double bottomTrim;
        private final IntBounds bounds;

        private ClippedSpawnSphereRegion(SpawnSphereRegion delegate, Vec3 center, double radius, double topTrim, double bottomTrim) {
            this.delegate = delegate;
            this.center = center;
            this.radius = radius;
            this.topTrim = Math.max(0.0, topTrim);
            this.bottomTrim = Math.max(0.0, bottomTrim);

            double minY = center.y - (radius - this.bottomTrim);
            double maxY = center.y + (radius - this.topTrim);
            IntBounds clipBounds = new IntBounds(
                Integer.MIN_VALUE,
                toMinBlock(minY),
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                toMaxBlock(maxY),
                Integer.MAX_VALUE
            );
            this.bounds = delegate.bounds().intersect(clipBounds);
        }

        static ClippedSpawnSphereRegion fromJson(JsonObject json) {
            Vec3 rawCenter = parseVec3(json, "center");
            if (rawCenter == null) return null;
            String snap = getString(json, "snap", "center");
            double radius = getDouble(json, "radius", -1.0);
            if (radius < 0.0) return null;
            double margin = getDouble(json, "margin", 1.5);
            double topTrim = getDouble(json, "top_trim", 0.0);
            double bottomTrim = getDouble(json, "bottom_trim", 0.0);

            Vec3 center = applySnap(rawCenter, snap);
            SpawnSphereRegion delegate = new SpawnSphereRegion(center, radius, margin);
            return new ClippedSpawnSphereRegion(delegate, center, radius, topTrim, bottomTrim);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            double py = y + 0.5;
            double minY = center.y - (radius - bottomTrim);
            double maxY = center.y + (radius - topTrim);
            return py >= minY && py <= maxY && delegate.contains(x, y, z);
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class EllipsoidSpawnRegion implements RegionDef {
        private final Vec3 center;
        private final double radiusX;
        private final double radiusY;
        private final double radiusZ;
        private final IntBounds bounds;

        private EllipsoidSpawnRegion(Vec3 center, double radiusX, double radiusY, double radiusZ) {
            this.center = center;
            this.radiusX = radiusX;
            this.radiusY = radiusY;
            this.radiusZ = radiusZ;
            this.bounds = new IntBounds(
                toMinBlock(center.x - radiusX),
                toMinBlock(center.y - radiusY),
                toMinBlock(center.z - radiusZ),
                toMaxBlock(center.x + radiusX),
                toMaxBlock(center.y + radiusY),
                toMaxBlock(center.z + radiusZ)
            );
        }

        static EllipsoidSpawnRegion fromJson(JsonObject json) {
            Vec3 rawCenter = parseVec3(json, "center");
            if (rawCenter == null) return null;
            String snap = getString(json, "snap", "center");
            double radiusX = getDouble(json, "radius", -1.0);
            if (radiusX < 0.0) return null;
            double radiusY = getDouble(json, "radius_y", radiusX);
            double radiusZ = getDouble(json, "radius_z", radiusX);
            return new EllipsoidSpawnRegion(applySnap(rawCenter, snap), radiusX, radiusY, radiusZ);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (radiusX <= 0.0 || radiusY <= 0.0 || radiusZ <= 0.0) return false;

            double dx = (x + 0.5) - center.x;
            double dy = (y + 0.5) - center.y;
            double dz = (z + 0.5) - center.z;
            double normalizedDist = (dx * dx) / (radiusX * radiusX) + (dy * dy) / (radiusY * radiusY) + (dz * dz) / (radiusZ * radiusZ);
            return normalizedDist <= 1.0;
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class BoxRegion implements RegionDef {
        private final double minX;
        private final double minY;
        private final double minZ;
        private final double maxX;
        private final double maxY;
        private final double maxZ;
        private final IntBounds bounds;

        private BoxRegion(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
            this.bounds = new IntBounds(
                toMinBlock(this.minX),
                toMinBlock(this.minY),
                toMinBlock(this.minZ),
                toMaxBlock(this.maxX),
                toMaxBlock(this.maxY),
                toMaxBlock(this.maxZ)
            );
        }

        static BoxRegion fromJson(JsonObject json) {
            Vec3 corner1 = parseVec3(json, "corner1");
            Vec3 corner2 = parseVec3(json, "corner2");

            if (corner1 != null && corner2 != null) {
                return new BoxRegion(corner1.x, corner1.y, corner1.z, corner2.x, corner2.y, corner2.z);
            }

            Vec3 center = parseVec3(json, "center");
            double width = getDouble(json, "width", -1.0);
            double height = getDouble(json, "height", -1.0);
            double depth = getDouble(json, "depth", -1.0);
            if (center != null && width >= 0.0 && height >= 0.0 && depth >= 0.0) {
                return new BoxRegion(
                    center.x - (width / 2.0),
                    center.y - (height / 2.0),
                    center.z - (depth / 2.0),
                    center.x + (width / 2.0),
                    center.y + (height / 2.0),
                    center.z + (depth / 2.0)
                );
            }

            if (json.has("minX") && json.has("minY") && json.has("minZ") && json.has("maxX") && json.has("maxY") && json.has("maxZ")) {
                return new BoxRegion(
                    getDouble(json, "minX", 0.0),
                    getDouble(json, "minY", 0.0),
                    getDouble(json, "minZ", 0.0),
                    getDouble(json, "maxX", 0.0),
                    getDouble(json, "maxY", 0.0),
                    getDouble(json, "maxZ", 0.0)
                );
            }

            return null;
        }

        @Override
        public boolean contains(int x, int y, int z) {
            double sx = x + 0.5;
            double sy = y + 0.5;
            double sz = z + 0.5;
            return sx >= minX && sx <= maxX
                && sy >= minY && sy <= maxY
                && sz >= minZ && sz <= maxZ;
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }
    }

    private static final class CircleRegion implements RegionDef {
        private final Vec3 center;
        private final String mainAxis;
        private final int height;
        private final double radiusSq;
        private final int centerBlockX;
        private final int centerBlockY;
        private final int centerBlockZ;
        private final IntBounds bounds;

        private CircleRegion(Vec3 center, String mainAxis, int height, double radius) {
            this.center = center;
            this.mainAxis = mainAxis;
            this.height = Math.max(1, height);
            this.radiusSq = radius * radius;
            this.centerBlockX = (int) Math.floor(center.x);
            this.centerBlockY = (int) Math.floor(center.y);
            this.centerBlockZ = (int) Math.floor(center.z);
            this.bounds = buildBounds(radius);
        }

        static CircleRegion fromJson(JsonObject json) {
            Vec3 rawCenter = parseVec3(json, "center");
            if (rawCenter == null) return null;
            String snap = getString(json, "snap", "center");
            String mainAxis = getString(json, "main_axis", "UP").toUpperCase(Locale.ROOT);
            double radius = getDouble(json, "radius", -1.0);
            if (radius < 0.0) return null;
            int height = getInt(json, "height", 1);
            return new CircleRegion(applySnap(rawCenter, snap), mainAxis, height, radius);
        }

        @Override
        public boolean contains(int x, int y, int z) {
            if (!isInsideHeightBand(x, y, z)) return false;

            double sx = usesAxisX() ? center.x : x + 0.5;
            double sy = usesAxisY() ? center.y : y + 0.5;
            double sz = usesAxisZ() ? center.z : z + 0.5;
            return distanceSq(sx, sy, sz, center.x, center.y, center.z) <= radiusSq;
        }

        @Override
        public IntBounds bounds() {
            return bounds;
        }

        private IntBounds buildBounds(double radius) {
            int pad = (int) Math.ceil(radius) + 2;
            return switch (mainAxis) {
                case "DOWN" -> new IntBounds(centerBlockX - pad, centerBlockY - (height - 1), centerBlockZ - pad, centerBlockX + pad, centerBlockY, centerBlockZ + pad);
                case "NORTH" -> new IntBounds(centerBlockX - pad, centerBlockY - pad, centerBlockZ - (height - 1), centerBlockX + pad, centerBlockY + pad, centerBlockZ);
                case "SOUTH" -> new IntBounds(centerBlockX - pad, centerBlockY - pad, centerBlockZ, centerBlockX + pad, centerBlockY + pad, centerBlockZ + (height - 1));
                case "WEST" -> new IntBounds(centerBlockX - (height - 1), centerBlockY - pad, centerBlockZ - pad, centerBlockX, centerBlockY + pad, centerBlockZ + pad);
                case "EAST" -> new IntBounds(centerBlockX, centerBlockY - pad, centerBlockZ - pad, centerBlockX + (height - 1), centerBlockY + pad, centerBlockZ + pad);
                case "UP" -> new IntBounds(centerBlockX - pad, centerBlockY, centerBlockZ - pad, centerBlockX + pad, centerBlockY + (height - 1), centerBlockZ + pad);
                default -> new IntBounds(centerBlockX - pad, centerBlockY, centerBlockZ - pad, centerBlockX + pad, centerBlockY + (height - 1), centerBlockZ + pad);
            };
        }

        private boolean isInsideHeightBand(int x, int y, int z) {
            return switch (mainAxis) {
                case "DOWN" -> centerBlockY - y >= 0 && centerBlockY - y < height;
                case "NORTH" -> centerBlockZ - z >= 0 && centerBlockZ - z < height;
                case "SOUTH" -> z - centerBlockZ >= 0 && z - centerBlockZ < height;
                case "WEST" -> centerBlockX - x >= 0 && centerBlockX - x < height;
                case "EAST" -> x - centerBlockX >= 0 && x - centerBlockX < height;
                case "UP" -> y - centerBlockY >= 0 && y - centerBlockY < height;
                default -> y - centerBlockY >= 0 && y - centerBlockY < height;
            };
        }

        private boolean usesAxisX() {
            return "EAST".equals(mainAxis) || "WEST".equals(mainAxis);
        }

        private boolean usesAxisY() {
            return "UP".equals(mainAxis) || "DOWN".equals(mainAxis);
        }

        private boolean usesAxisZ() {
            return "NORTH".equals(mainAxis) || "SOUTH".equals(mainAxis);
        }
    }

    private static double distanceSq(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double dz = z1 - z2;
        return dx * dx + dy * dy + dz * dz;
    }

    private record Vec3(double x, double y, double z) {}
}
