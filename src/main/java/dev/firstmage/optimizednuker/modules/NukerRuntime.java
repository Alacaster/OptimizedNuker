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

    void reset() {
        workSet.clearAll();
        fullScanCursor = 0;
        fullScanJustReset = true;
        lastLocalCursorExclusive = 0;
        lastCrawlCursorExclusive = 0;
        lastTickBestActionWorld = BlockPos.ORIGIN;
        lastTickBestActionMapIndex = 0;
        lastTickBestActionDistance = 0F;
        positionAtLastMovement = Vec3d.ZERO;
        timer = 0;
    }

    void restartFullScan(int cursorStart) {
        workSet.full.clear();
        fullScanCursor = Math.max(0, cursorStart);
        fullScanJustReset = true;
    }

    int computeMovementBlocks(Vec3d now) {
        double distance = now.distanceTo(positionAtLastMovement);
        if (distance <= 0.1) return 0;
        return Math.max(1, (int) Math.ceil(distance));
    }

}
