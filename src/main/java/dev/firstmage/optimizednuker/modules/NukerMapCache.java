package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class NukerMapCache {
    private SphereMapStore.MapPoint[] activeMap = new SphereMapStore.MapPoint[0];
    private int goalIndexExclusive;
    private OptimizedNuker.Shape cachedShape;
    private OptimizedNuker.SortMode cachedSortMode;
    private Direction cachedFacing;
    private double cachedRange;
    private int[] cachedCubeExtents = new int[6];

    SphereMapStore.MapPoint[] activeMap() {
        return activeMap;
    }

    int goalIndexExclusive() {
        return goalIndexExclusive;
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

        goalIndexExclusive = computeGoalIndexExclusive(shape, sortMode, range);
        return true;
    }

    private int computeGoalIndexExclusive(OptimizedNuker.Shape shape, OptimizedNuker.SortMode sortMode, double range) {
        if (activeMap.length == 0) return 0;
        if (shape == OptimizedNuker.Shape.Sphere && sortMode == OptimizedNuker.SortMode.Closest) {
            return SphereMapStore.upperBoundByDistance(SphereMapStore.closest(), (float) Math.min(range, SphereMapStore.MAX_RADIUS));
        }
        return activeMap.length;
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
}
