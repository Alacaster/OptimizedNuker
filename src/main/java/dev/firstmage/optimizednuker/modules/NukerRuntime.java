package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

final class NukerRuntime {
    final NukerWorkSet workSet = new NukerWorkSet();

    int fullScanCursor;
    boolean fullScanJustReset;
    int lastLocalCursorExclusive;
    int lastCrawlCursorExclusive;
    BlockPos lastTickBestActionWorld = BlockPos.ORIGIN;
    int lastTickBestActionMapIndex;
    float lastTickBestActionDistance;
    Vec3d positionAtLastMovement = Vec3d.ZERO;
    int timer;

    void restartFullScan(int goalIndexExclusive, int cursorStart) {
        workSet.full.clear();
        int goalMaxIndex = Math.max(goalIndexExclusive - 1, 0);
        fullScanCursor = clamp(cursorStart, 0, goalMaxIndex);
        fullScanJustReset = true;
    }

    int computeMovementBlocks(Vec3d now) {
        double distance = now.distanceTo(positionAtLastMovement);
        if (distance <= 0.1) return 0;
        return Math.max(1, (int) Math.ceil(distance));
    }

    void clampAnchor(int goalIndexExclusive) {
        lastTickBestActionMapIndex = clamp(lastTickBestActionMapIndex, 0, Math.max(goalIndexExclusive - 1, 0));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
