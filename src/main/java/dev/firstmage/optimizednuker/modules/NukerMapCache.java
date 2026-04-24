package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class NukerMapCache {
    private static final int[] EMPTY_WINDOWS = new int[0];

    private SphereMapStore.MapPoint[] activeMap = new SphereMapStore.MapPoint[0];
    private int[] scanWindowStarts = EMPTY_WINDOWS;
    private int[] scanWindowEnds = EMPTY_WINDOWS;
    private int[] scanWindowCandidateStarts = EMPTY_WINDOWS;
    private int candidateCount;
    private OptimizedNuker.Shape cachedShape;
    private OptimizedNuker.SortMode cachedSortMode;
    private Direction cachedFacing;
    private double cachedRange;
    private int[] cachedCubeExtents = new int[6];

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
        int window = windowForCandidateIndex(candidateIndex);
        return scanWindowStarts[window] + candidateIndex - scanWindowCandidateStarts[window];
    }

    boolean rebuildIfNeeded(boolean force, OptimizedNuker.Shape shape, OptimizedNuker.SortMode sortMode, double range, int[] cubeExtents, Direction facing) {
        SphereMapStore.ensureLoaded();

        boolean changed = force
            || cachedShape != shape
            || cachedSortMode != sortMode
            || cachedFacing != facing
            || Double.compare(cachedRange, range) != 0
            || !Arrays.equals(cachedCubeExtents, cubeExtents);

        if (!changed) return false;

        cachedShape = shape;
        cachedSortMode = sortMode;
        cachedFacing = facing;
        cachedRange = range;
        cachedCubeExtents = Arrays.copyOf(cubeExtents, cubeExtents.length);

        switch (shape) {
            case Sphere -> activeMap = switch (sortMode) {
                case Closest -> SphereMapStore.closest();
                case Furthest -> SphereMapStore.furthest();
                case TopDown -> SphereMapStore.topDown();
            };
            case UniformCube -> activeMap = generateUniformCubeMap((int) Math.round(range), sortMode);
            case Cube -> activeMap = generateDirectionalCubeMap(sortMode, facing, cubeExtents);
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
        scanWindowEnds = new int[]{clampedEnd};
        scanWindowCandidateStarts = new int[]{0};
        candidateCount = clampedEnd - clampedStart;
    }

    private void setScanWindows(List<Integer> starts, List<Integer> ends) {
        if (starts.isEmpty()) {
            setEmptyScanWindows();
            return;
        }

        scanWindowStarts = new int[starts.size()];
        scanWindowEnds = new int[ends.size()];
        scanWindowCandidateStarts = new int[starts.size()];
        candidateCount = 0;
        for (int i = 0; i < starts.size(); i++) {
            scanWindowStarts[i] = starts.get(i);
            scanWindowEnds[i] = ends.get(i);
            scanWindowCandidateStarts[i] = candidateCount;
            candidateCount += scanWindowEnds[i] - scanWindowStarts[i];
        }
    }

    private void setEmptyScanWindows() {
        scanWindowStarts = EMPTY_WINDOWS;
        scanWindowEnds = EMPTY_WINDOWS;
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

    private SphereMapStore.MapPoint[] generateDirectionalCubeMap(OptimizedNuker.SortMode mode, Direction facing, int[] extents) {
        int up = extents[0];
        int down = extents[1];
        int left = extents[2];
        int right = extents[3];
        int forwardRange = extents[4];
        int back = extents[5];

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
