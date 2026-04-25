package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

final class NukerMapCache {
    private static final int[] EMPTY_WINDOWS = new int[0];

    private SphereMapStore.MapPoint[] activeMap = new SphereMapStore.MapPoint[0];
    private int[] scanWindowStarts = EMPTY_WINDOWS;
    private int[] scanWindowCandidateStarts = EMPTY_WINDOWS;
    private int candidateCount;
    private OptimizedNuker.Shape cachedShape;
    private OptimizedNuker.SortMode cachedSortMode;
    private Direction cachedFacing;
    private double cachedRange;
    private final int[] cachedCubeExtents = new int[6];

    /**
     * Packed offset (dx, dy, dz) -> candidate index, for the coordinate-transform
     * rebase (system 4: moved-toward case). Built lazily on first access after a
     * map rebuild and cleared on every rebuild.
     */
    private HashMap<Long, Integer> offsetToCandidateIndex;

    /**
     * Per-shell candidate-index bounds. {@code shellStartCandidate[s]} is the first
     * candidate index whose floor-distance equals {@code s}; {@code shellEndCandidate[s]}
     * is the exclusive upper bound. Both arrays are sized {@code MAX_RADIUS + 1}.
     * Built lazily after a map rebuild for Closest/Furthest sphere modes.
     */
    private int[] shellStartCandidate;
    private int[] shellEndCandidate;

    boolean hasCandidates() {
        return candidateCount > 0;
    }

    int candidateCount() {
        return candidateCount;
    }

    boolean isCandidateIndex(int index) {
        return 0 <= index && index < candidateCount;
    }

    int clampToCandidateIndex(int index) {
        if (!hasCandidates()) return 0;
        return clamp(index, 0, candidateCount - 1);
    }

    SphereMapStore.MapPoint pointAt(int candidateIndex) {
        int rawIndex = rawIndexAt(candidateIndex);
        return rawIndex < 0 ? null : activeMap[rawIndex];
    }

    int rawIndexAt(int candidateIndex) {
        if (!isCandidateIndex(candidateIndex)) return -1;
        if (scanWindowStarts.length == 1) return scanWindowStarts[0] + candidateIndex;
        int window = windowForCandidateIndex(candidateIndex);
        return scanWindowStarts[window] + candidateIndex - scanWindowCandidateStarts[window];
    }

    /**
     * Reverse lookup: returns the candidate index for the offset (dx, dy, dz), or
     * -1 if no candidate has that exact offset. Used by the coordinate-transform
     * rebase to find the new map index of a known world block after the player
     * moved toward it.
     */
    int candidateIndexForOffset(int dx, int dy, int dz) {
        if (!hasCandidates()) return -1;
        ensureOffsetIndex();
        Integer hit = offsetToCandidateIndex.get(packOffset(dx, dy, dz));
        return hit == null ? -1 : hit;
    }

    /**
     * Returns the first candidate index whose floor-distance equals shell {@code s},
     * or {@code candidateCount} if no candidate exists in that shell. Sphere mode
     * with Closest sort only - other modes return -1.
     */
    int shellStartCandidate(int shell) {
        if (!ensureShellRanges()) return -1;
        if (shell < 0 || shell > SphereMapStore.MAX_RADIUS) return -1;
        return shellStartCandidate[shell];
    }

    /**
     * Returns the exclusive upper candidate index for shell {@code s}.
     * Sphere mode with Closest sort only - other modes return -1.
     */
    int shellEndCandidate(int shell) {
        if (!ensureShellRanges()) return -1;
        if (shell < 0 || shell > SphereMapStore.MAX_RADIUS) return -1;
        return shellEndCandidate[shell];
    }

    private static final String SHELL_LUT_RESOURCE = "nuker/sphere_shell_lut_closest_r64.bin";
    private static final int SHELL_COUNT = SphereMapStore.MAX_RADIUS + 1; // 65

    /** Load shell start/end arrays from the pre-generated LUT resource. */
    private static int[] loadedShellStart;
    private static int[] loadedShellEnd;

    private static synchronized void ensureShellLutLoaded() {
        if (loadedShellStart != null) return;
        try (java.io.InputStream in = NukerMapCache.class.getClassLoader().getResourceAsStream(SHELL_LUT_RESOURCE)) {
            if (in == null) throw new java.io.IOException("Missing shell LUT resource: " + SHELL_LUT_RESOURCE);
            byte[] bytes = in.readAllBytes();
            if (bytes.length != SHELL_COUNT * 2 * 4)
                throw new java.io.IOException("Shell LUT size mismatch: " + bytes.length);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            int[] starts = new int[SHELL_COUNT];
            int[] ends   = new int[SHELL_COUNT];
            for (int i = 0; i < SHELL_COUNT; i++) starts[i] = buf.getInt();
            for (int i = 0; i < SHELL_COUNT; i++) ends[i]   = buf.getInt();
            loadedShellStart = starts;
            loadedShellEnd   = ends;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to load sphere shell LUT", e);
        }
    }

    private boolean ensureShellRanges() {
        if (cachedShape != OptimizedNuker.Shape.Sphere
            || cachedSortMode != OptimizedNuker.SortMode.Closest) return false;
        if (shellStartCandidate != null) return true;
        ensureShellLutLoaded();
        // The LUT indices are in terms of the full raw map; for the candidate window
        // (which starts at raw index 0 for Closest sort), they are directly usable.
        shellStartCandidate = loadedShellStart;
        shellEndCandidate   = loadedShellEnd;
        return true;
    }

    private void ensureOffsetIndex() {
        if (offsetToCandidateIndex != null) return;
        offsetToCandidateIndex = new HashMap<>(candidateCount * 2);
        for (int idx = 0; idx < candidateCount; idx++) {
            SphereMapStore.MapPoint p = pointAt(idx);
            if (p == null) continue;
            offsetToCandidateIndex.put(packOffset(p.dx, p.dy, p.dz), idx);
        }
    }

    private static long packOffset(int dx, int dy, int dz) {
        // Each offset component fits in 8 bits signed (range -64..64 inclusive).
        // Pack as three signed bytes into the lower 24 bits of a long.
        return ((dx & 0xFFL) << 16) | ((dy & 0xFFL) << 8) | (dz & 0xFFL);
    }

    boolean rebuildIfNeeded(boolean force, OptimizedNuker.Shape shape, OptimizedNuker.SortMode sortMode, double range, int upE, int downE, int leftE, int rightE, int forwardE, int backE, Direction facing) {
        SphereMapStore.ensureLoaded();

        boolean extentsChanged = cachedCubeExtents[0] != upE
            || cachedCubeExtents[1] != downE
            || cachedCubeExtents[2] != leftE
            || cachedCubeExtents[3] != rightE
            || cachedCubeExtents[4] != forwardE
            || cachedCubeExtents[5] != backE;

        boolean changed = force
            || cachedShape != shape
            || cachedSortMode != sortMode
            || cachedFacing != facing
            || Double.compare(cachedRange, range) != 0
            || extentsChanged;

        if (!changed) return false;

        // Invalidate derived caches; they rebuild lazily on next access.
        offsetToCandidateIndex = null;
        shellStartCandidate = null;
        shellEndCandidate = null;

        cachedShape = shape;
        cachedSortMode = sortMode;
        cachedFacing = facing;
        cachedRange = range;
        cachedCubeExtents[0] = upE;
        cachedCubeExtents[1] = downE;
        cachedCubeExtents[2] = leftE;
        cachedCubeExtents[3] = rightE;
        cachedCubeExtents[4] = forwardE;
        cachedCubeExtents[5] = backE;

        switch (shape) {
            case Sphere -> activeMap = switch (sortMode) {
                case Closest -> SphereMapStore.closest();
                case Furthest -> SphereMapStore.furthest();
                case TopDown -> SphereMapStore.topDown();
            };
            case UniformCube -> activeMap = generateUniformCubeMap((int) Math.round(range), sortMode);
            case Cube -> activeMap = generateDirectionalCubeMap(sortMode, facing, upE, downE, leftE, rightE, forwardE, backE);
        }

        rebuildScanWindows(shape, sortMode, range);
        return true;
    }

    private void rebuildScanWindows(OptimizedNuker.Shape shape, OptimizedNuker.SortMode sortMode, double range) {
        if (activeMap.length == 0) {
            setEmptyScanWindows();
            return;
        }

        if (shape != OptimizedNuker.Shape.Sphere) {
            setSingleScanWindow(0, activeMap.length);
            return;
        }

        float maxDistance = (float) Math.min(Math.max(range, 0), SphereMapStore.MAX_RADIUS);
        switch (sortMode) {
            case Closest -> setDistanceOrderedScanWindow(maxDistance, true);
            case Furthest -> setDistanceOrderedScanWindow(maxDistance, false);
            case TopDown -> setContiguousRangeScanWindows(maxDistance);
        }
    }

    private void setDistanceOrderedScanWindow(float maxDistance, boolean nearestFirst) {
        int split = findDistanceSplit(activeMap, maxDistance, nearestFirst);
        if (nearestFirst) setSingleScanWindow(0, split);
        else setSingleScanWindow(split, activeMap.length);
    }

    private static int findDistanceSplit(SphereMapStore.MapPoint[] map, float maxDistance, boolean nearestFirst) {
        int lo = 0;
        int hi = map.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            boolean beforeCandidateWindow = nearestFirst
                ? map[mid].distance <= maxDistance
                : map[mid].distance > maxDistance;
            if (beforeCandidateWindow) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void setContiguousRangeScanWindows(float maxDistance) {
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        int start = -1;

        for (int i = 0; i < activeMap.length; i++) {
            boolean inRange = activeMap[i].distance <= maxDistance;
            if (inRange && start < 0) start = i;
            else if (!inRange && start >= 0) {
                starts.add(start);
                ends.add(i);
                start = -1;
            }
        }

        if (start >= 0) {
            starts.add(start);
            ends.add(activeMap.length);
        }

        setScanWindows(starts, ends);
    }

    private void setSingleScanWindow(int start, int end) {
        int clampedStart = clamp(start, 0, activeMap.length);
        int clampedEnd = clamp(end, clampedStart, activeMap.length);
        if (clampedStart >= clampedEnd) {
            setEmptyScanWindows();
            return;
        }

        scanWindowStarts = new int[]{clampedStart};
        scanWindowCandidateStarts = new int[]{0};
        candidateCount = clampedEnd - clampedStart;
    }

    private void setScanWindows(List<Integer> starts, List<Integer> ends) {
        if (starts.isEmpty()) {
            setEmptyScanWindows();
            return;
        }

        scanWindowStarts = new int[starts.size()];
        scanWindowCandidateStarts = new int[starts.size()];
        candidateCount = 0;
        for (int i = 0; i < starts.size(); i++) {
            scanWindowStarts[i] = starts.get(i);
            scanWindowCandidateStarts[i] = candidateCount;
            candidateCount += ends.get(i) - starts.get(i);
        }
    }

    private void setEmptyScanWindows() {
        scanWindowStarts = EMPTY_WINDOWS;
        scanWindowCandidateStarts = EMPTY_WINDOWS;
        candidateCount = 0;
    }

    private int windowForCandidateIndex(int candidateIndex) {
        int lo = 0;
        int hi = scanWindowCandidateStarts.length - 1;
        int best = 0;

        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (scanWindowCandidateStarts[mid] <= candidateIndex) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        return best;
    }

    private SphereMapStore.MapPoint[] generateUniformCubeMap(int r, OptimizedNuker.SortMode mode) {
        List<SphereMapStore.MapPoint> points = new ArrayList<>((2 * r + 1) * (2 * r + 1) * (2 * r + 1));
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    int cheb = Math.max(Math.max(Math.abs(x), Math.abs(y)), Math.abs(z));
                    if (cheb > r) continue;
                    points.add(SphereMapStore.MapPoint.of(x, y, z));
                }
            }
        }
        points.sort(getPointComparator(mode));
        return points.toArray(new SphereMapStore.MapPoint[0]);
    }

    private SphereMapStore.MapPoint[] generateDirectionalCubeMap(OptimizedNuker.SortMode mode, Direction facing, int up, int down, int left, int right, int forwardRange, int back) {
        List<SphereMapStore.MapPoint> points = new ArrayList<>();
        for (int y = -down; y <= up; y++) {
            for (int lateral = -right; lateral <= left; lateral++) {
                for (int forward = -back; forward <= forwardRange; forward++) {
                    int x;
                    int z;
                    switch (facing) {
                        case SOUTH -> { x = -lateral; z = forward; }
                        case WEST -> { x = -forward; z = lateral; }
                        case NORTH -> { x = lateral; z = -forward; }
                        case EAST -> { x = forward; z = -lateral; }
                        default -> { x = lateral; z = forward; }
                    }
                    points.add(SphereMapStore.MapPoint.of(x, y, z));
                }
            }
        }
        points.sort(getPointComparator(mode));
        return points.toArray(new SphereMapStore.MapPoint[0]);
    }

    private Comparator<SphereMapStore.MapPoint> getPointComparator(OptimizedNuker.SortMode mode) {
        Comparator<SphereMapStore.MapPoint> closest = Comparator
            .comparingInt(SphereMapStore.MapPoint::distanceSq)
            .thenComparingInt((SphereMapStore.MapPoint p) -> -(p.dx + p.dy + p.dz))
            .thenComparingInt((SphereMapStore.MapPoint p) -> -p.dy)
            .thenComparingInt((SphereMapStore.MapPoint p) -> -p.dx)
            .thenComparingInt((SphereMapStore.MapPoint p) -> -p.dz);

        return switch (mode) {
            case Closest -> closest;
            case Furthest -> closest.reversed();
            case TopDown -> Comparator
                .comparingInt((SphereMapStore.MapPoint p) -> -p.dy)
                .thenComparingInt(SphereMapStore.MapPoint::distanceSq)
                .thenComparingInt((SphereMapStore.MapPoint p) -> -(p.dx + p.dy + p.dz))
                .thenComparingInt((SphereMapStore.MapPoint p) -> -p.dx)
                .thenComparingInt((SphereMapStore.MapPoint p) -> -p.dz);
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
