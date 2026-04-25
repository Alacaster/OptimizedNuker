package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.Vec3d;

/**
 * Per-module mutable state. State is partitioned by lifetime:
 *
 *   context-scoped (rebuilt on block-border crossing or activation):
 *     contextPosition, contextBlockX/Y/Z, lastActionDistanceAtContextBuild
 *
 *   tick-scoped (rebuilt every tick):
 *     - none here; Inputs and ScanContext live on the module and follow context
 *
 *   persistent (carried across ticks until something specific changes):
 *     frontierIndex, lastActionWorldX/Y/Z, frontierIsRealAction,
 *     crawlAnchorIndex, crawlHighCursor/Disabled, crawlLowCursorExclusive/Disabled,
 *     crawlScanLowSideNext, completionProbe state, globalFrontierProbeCursor,
 *     fullScanLowestSeen, timer
 */
final class NukerRuntime {
    final NukerWorkSet workSet = new NukerWorkSet();

    // ---- frontier / last action ----------------------------------------------

    int frontierIndex;

    /**
     * World coordinates of the last successful action that set the frontier.
     * Valid only when {@link #frontierIsRealAction} is true. When the frontier
     * was set by a scanner (completion / full / rebase), this position is
     * stale and the boolean is false.
     */
    int lastActionWorldX;
    int lastActionWorldY;
    int lastActionWorldZ;
    boolean frontierIsRealAction;

    // ---- crawl walk state ----------------------------------------------------

    int crawlAnchorIndex;
    int crawlHighCursor;
    int crawlLowCursorExclusive;
    boolean crawlScanLowSideNext;
    boolean crawlHighDisabled;
    boolean crawlLowDisabled;

    // ---- completion probe ----------------------------------------------------

    int completionProbeUpperExclusive;
    boolean completionProbePending;

    // ---- full scan -----------------------------------------------------------

    int globalFrontierProbeCursor;
    /** Lowest accepting index seen since the last full-scan reset. -1 = nothing yet. */
    int fullScanLowestSeen = -1;

    // ---- context -------------------------------------------------------------

    Vec3d contextPosition = Vec3d.ZERO;
    int contextBlockX;
    int contextBlockY;
    int contextBlockZ;
    /**
     * Distance from {@link #contextPosition} to the last-action world position,
     * captured at context-build time. Used at the next context build to decide
     * whether the player moved toward or away from the work target.
     * NaN = no real action since last context build (use shell scan unconditionally).
     */
    double lastActionDistanceAtContextBuild = Double.NaN;

    // ---- timing --------------------------------------------------------------

    int timer;

    // -------------------------------------------------------------------------

    void reset() {
        workSet.clearQueues();
        frontierIndex = 0;
        lastActionWorldX = 0;
        lastActionWorldY = 0;
        lastActionWorldZ = 0;
        frontierIsRealAction = false;
        invalidateCrawl();
        completionProbeUpperExclusive = 0;
        completionProbePending = false;
        globalFrontierProbeCursor = 0;
        fullScanLowestSeen = -1;
        contextPosition = Vec3d.ZERO;
        contextBlockX = 0;
        contextBlockY = 0;
        contextBlockZ = 0;
        lastActionDistanceAtContextBuild = Double.NaN;
        timer = 0;
    }

    /** Schedule the completion probe to run on the next crawl call. */
    void requestCompletionProbe(int upperExclusive) {
        completionProbeUpperExclusive = Math.max(0, upperExclusive);
        completionProbePending = completionProbeUpperExclusive > 0;
    }

    /** Discard crawl walk state (cursors and disable flags). */
    void invalidateCrawl() {
        crawlAnchorIndex = -1;
        crawlHighCursor = 0;
        crawlLowCursorExclusive = 0;
        crawlScanLowSideNext = true;
        crawlHighDisabled = false;
        crawlLowDisabled = false;
    }

    /**
     * Set both crawl cursors equal to the given index (per the universal
     * "external set -> both cursors equal" rule). Re-arms crawl: clears the
     * disable flags and biases the next walk to the low side.
     */
    void anchorCrawlAt(int anchor) {
        int clamped = Math.max(0, anchor);
        crawlAnchorIndex = clamped;
        crawlHighCursor = clamped;
        crawlLowCursorExclusive = clamped;
        crawlScanLowSideNext = true;
        crawlHighDisabled = false;
        crawlLowDisabled = false;
        workSet.clearQueues();
    }

    /** True if both crawl cursors have disabled themselves and crawl is yielding. */
    boolean crawlYielding() {
        return crawlHighDisabled && crawlLowDisabled;
    }

    /**
     * Frontier set by any scanner (completion / full / rebase). World position
     * is unknown so we mark the frontier as not-a-real-action; the next moved-
     * toward decision will fall back to the shell scan.
     */
    void setFrontierFromScanner(int index) {
        frontierIndex = Math.max(0, index);
        frontierIsRealAction = false;
        anchorCrawlAt(frontierIndex);
    }

    /**
     * Frontier set by a successful modify action. Captures the world position
     * so the next context build can do a coordinate-transform rebase.
     */
    void onActionSuccess(int mapIndex, int worldX, int worldY, int worldZ) {
        frontierIndex = Math.max(0, mapIndex);
        lastActionWorldX = worldX;
        lastActionWorldY = worldY;
        lastActionWorldZ = worldZ;
        frontierIsRealAction = true;
    }

    /** Capture context state at the start of a context build. */
    void captureContext(Vec3d position, int blockX, int blockY, int blockZ, double lastActionDistance) {
        contextPosition = position;
        contextBlockX = blockX;
        contextBlockY = blockY;
        contextBlockZ = blockZ;
        lastActionDistanceAtContextBuild = lastActionDistance;
    }

    /** True if the player's integer block coordinate differs from the last context build. */
    boolean hasCrossedBlockBorder(int blockX, int blockY, int blockZ) {
        return blockX != contextBlockX
            || blockY != contextBlockY
            || blockZ != contextBlockZ;
    }
}
