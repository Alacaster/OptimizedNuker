package dev.firstmage.optimizednuker.modules;

import net.minecraft.util.math.BlockPos;

/**
 * Pure scanner logic. Each function reads {@link CandidatePolicy.Inputs} and the
 * candidate map, mutates cursors on {@link NukerRuntime}, and uses {@code module}
 * only for profiler hooks.
 *
 * Tick order at the call site (OptimizedNuker.onTick):
 *   1. observe        builds context if needed, optionally calls runRebase
 *   2. influencing scanners (these can move the frontier and re-arm crawl):
 *        a. runCompletionProbeIfPending
 *        b. runFullScan
 *   3. action loop:
 *        crawl  ->  modify  (repeat until goal met / crawl yields / no work)
 *
 * Crawl is the ONLY action producer. Everything else exists to keep crawl
 * pointed at the right work; those scanners run before crawl in the tick so
 * their effects are settled when crawl walks.
 *
 * Roles:
 *
 *   CRAWL       Bidirectional walk from the anchor index. Each cursor disables
 *               itself when it leaves the useful range; once both are disabled,
 *               crawl yields and stays yielded until a frontier-update operation
 *               re-arms it.
 *   COMPLETION  Factor-stride modular search over the full candidate range.
 *               Runs when scheduled (activation, map rebuild, meta change).
 *               Sets the frontier to the lowest accepting index found.
 *   FULL        Constant-hum modular sweep over the close-side region crawl has
 *               already covered ({@code [0, crawlLowCursorExclusive)}). Tracks
 *               the lowest accepting index seen since its last reset; advances
 *               the frontier whenever it finds a closer hit than its current
 *               memory.
 *   REBASE      Movement response, dispatched from observe() on block-border
 *               crossing. Coordinate-transform when the player moved toward the
 *               last-action target; shell-window scan when away or when no real
 *               last action is available.
 */
final class NukerScanners {
    private NukerScanners() {}

    // ---- rebase --------------------------------------------------------------

    /**
     * Move the frontier when the player crosses a block border. {@code distanceMoved}
     * is the precise (un-ceiled) distance from the previous context position to
     * the current; the integer rebase radius is computed inside.
     *
     * Branch logic:
     *   - last-action world position valid AND new distance to it < previous distance:
     *       coordinate transform - find the new map index of the same world block.
     *   - otherwise:
     *       shell-window scan over [K - R, K + R] where K is the last-action shell
     *       and R = ceil(distanceMoved). First accepting index becomes the frontier.
     */
    static void runRebase(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        NukerRuntime runtime,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos,
        double distanceMoved,
        OptimizedNuker.Shape shape,
        OptimizedNuker.SortMode sortMode
    ) {
        if (!mapCache.hasCandidates()) return;

        boolean canShellRebase = shape == OptimizedNuker.Shape.Sphere
            && sortMode == OptimizedNuker.SortMode.Closest;
        if (!canShellRebase) {
            // Other shape/sort combos don't have distance-primary ordering; fall
            // back to a plain crawl re-anchor at the existing frontier.
            runtime.anchorCrawlAt(mapCache.clampToCandidateIndex(runtime.frontierIndex));
            return;
        }

        // Coordinate transform: only when the frontier was set by a real action AND
        // the new distance to that world block is smaller than at last context build.
        if (runtime.frontierIsRealAction && !Double.isNaN(runtime.lastActionDistanceAtContextBuild)) {
            double dx = runtime.lastActionWorldX + 0.5 - inputs.eyeX;
            double dy = runtime.lastActionWorldY + 0.5 - inputs.eyeY;
            double dz = runtime.lastActionWorldZ + 0.5 - inputs.eyeZ;
            double newDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (newDistance < runtime.lastActionDistanceAtContextBuild) {
                int odx = runtime.lastActionWorldX - inputs.playerBlockX;
                int ody = runtime.lastActionWorldY - inputs.playerBlockY;
                int odz = runtime.lastActionWorldZ - inputs.playerBlockZ;
                int newIndex = mapCache.candidateIndexForOffset(odx, ody, odz);
                if (newIndex >= 0) {
                    runtime.frontierIndex = newIndex;
                    runtime.frontierIsRealAction = true;
                    runtime.anchorCrawlAt(newIndex);
                    return;
                }
            }
        }

        // Shell scan path: moved away, or no real action to coord-transform onto.
        int anchor = mapCache.clampToCandidateIndex(runtime.frontierIndex);
        SphereMapStore.MapPoint anchorPoint = mapCache.pointAt(anchor);
        if (anchorPoint == null) {
            runtime.anchorCrawlAt(anchor);
            return;
        }

        int radius = Math.max(1, (int) Math.ceil(distanceMoved));
        int anchorShell = clamp((int) Math.floor(anchorPoint.distance), 0, SphereMapStore.MAX_RADIUS);
        int shellLow = Math.max(0, anchorShell - radius);
        int shellHigh = Math.min(SphereMapStore.MAX_RADIUS, anchorShell + radius);

        int rangeStart = mapCache.shellStartCandidate(shellLow);
        int rangeEnd = mapCache.shellEndCandidate(shellHigh);
        if (rangeStart < 0 || rangeEnd < 0 || rangeStart >= rangeEnd) {
            runtime.anchorCrawlAt(anchor);
            return;
        }

        long start = module.beginScannerTimer();
        int firstAccepting = -1;
        try {
            for (int idx = rangeStart; idx < rangeEnd; idx++) {
                byte type = classifyAt(module, inputs, mapCache, scanPos, metaNeighborPos,
                    idx, NukerProfiler.Scanner.CRAWL);
                if (type != CandidatePolicy.NONE) {
                    firstAccepting = idx;
                    break;
                }
            }
        } finally {
            module.endScannerTimer(NukerProfiler.Scanner.CRAWL, start);
        }

        if (firstAccepting >= 0) {
            runtime.setFrontierFromScanner(firstAccepting);
        } else {
            // No accepting candidate in the rebase window. Anchor at the existing
            // frontier so crawl can continue from it; full scan and completion
            // remain the path to discover work elsewhere.
            runtime.anchorCrawlAt(anchor);
        }
    }

    // ---- crawl ---------------------------------------------------------------

    /**
     * Bidirectional walk producing actions into the work set. Pure action producer:
     * does NOT dispatch other scanners. The caller runs the completion probe and
     * full scan ahead of crawl so any frontier movement is settled before crawl walks.
     *
     * Per-cursor disable: the high cursor disables itself when its candidate
     * has distance > usable range (no point looking further out). The low
     * cursor disables itself when it would decrement below 0. While disabled,
     * a cursor is skipped; if both are disabled, crawl yields and waits for
     * a frontier-update operation to re-arm via {@link NukerRuntime#anchorCrawlAt}.
     *
     * @return true if crawl is yielding (both cursors disabled or no candidates).
     */
    static boolean runCrawl(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        NukerRuntime runtime,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos,
        int maxCrawlScans
    ) {
        if (!mapCache.hasCandidates()) {
            runtime.invalidateCrawl();
            return true;
        }

        if (runtime.crawlYielding()) return true;
        if (runtime.workSet.queuesFull() || maxCrawlScans <= 0) return false;

        // Anchor at current frontier if not already anchored. Both cursors equal,
        // disable flags cleared. The walk below interprets cursors thus:
        //   low  inspects --crawlLowCursorExclusive  (so first inspection = anchor - 1)
        //   high inspects crawlHighCursor++          (so first inspection = anchor)
        if (runtime.crawlAnchorIndex < 0
            || runtime.crawlAnchorIndex != mapCache.clampToCandidateIndex(runtime.frontierIndex)) {
            runtime.anchorCrawlAt(mapCache.clampToCandidateIndex(runtime.frontierIndex));
        }

        int candidateCount = mapCache.candidateCount();
        double rangeMax = inputs.range;
        int scans = 0;

        while (!runtime.workSet.queuesFull()
            && scans < maxCrawlScans
            && !runtime.crawlYielding()) {

            boolean useLow;
            if (runtime.crawlScanLowSideNext && !runtime.crawlLowDisabled) {
                useLow = true;
            } else if (!runtime.crawlHighDisabled) {
                useLow = false;
            } else if (!runtime.crawlLowDisabled) {
                useLow = true;
            } else {
                break; // both disabled
            }

            boolean accepted;
            if (useLow) {
                int idx = runtime.crawlLowCursorExclusive - 1;
                if (idx < 0) {
                    runtime.crawlLowDisabled = true;
                    continue;
                }
                runtime.crawlLowCursorExclusive = idx;
                accepted = enqueueCandidate(module, inputs, mapCache, runtime.workSet.crawlLow,
                    scanPos, metaNeighborPos, idx);
            } else {
                int idx = runtime.crawlHighCursor;
                if (idx >= candidateCount) {
                    runtime.crawlHighDisabled = true;
                    continue;
                }
                SphereMapStore.MapPoint p = mapCache.pointAt(idx);
                if (p != null && p.distance > rangeMax) {
                    // The high cursor walked off the usable range; nothing past
                    // this point can ever be in reach. Disable it for the rest
                    // of this anchor cycle.
                    runtime.crawlHighDisabled = true;
                    continue;
                }
                runtime.crawlHighCursor = idx + 1;
                accepted = enqueueCandidate(module, inputs, mapCache, runtime.workSet.crawlHigh,
                    scanPos, metaNeighborPos, idx);
            }

            scans++;
            // Bias: stay on low after a low-accept (the "extra check" rule);
            // otherwise alternate, starting on low after every high.
            runtime.crawlScanLowSideNext = useLow ? accepted : true;
        }

        return runtime.crawlYielding();
    }

    // ---- full scan -----------------------------------------------------------

    /**
     * Constant-hum modular sweep over {@code [0, crawlLowCursorExclusive)} - the
     * close-side region that crawl has already classified at least once. Tracks
     * the lowest accepting index seen since its last reset. Advances the frontier
     * whenever it finds an index lower than its memory, and at sweep completion
     * if anything was found.
     *
     * Cursor regression: on movement, the caller sets the cursor to the start
     * of shell {@code (lastActionShell - movementBlocks)} and clears the
     * "lowest seen" memory so the next valid hit always wins.
     */
    static void runFullScan(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        NukerRuntime runtime,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos,
        int budget
    ) {
        if (!mapCache.hasCandidates() || budget <= 0) return;

        // Wrap range: from 0 up to where crawl walked down to. If crawl never
        // walked low (low cursor still equals anchor), use the anchor index.
        int wrapExclusive = Math.max(0, runtime.crawlLowCursorExclusive);
        if (wrapExclusive <= 0) {
            // Crawl exhausted the close side or never walked it. Fall back to
            // sweeping the whole candidate range below the anchor.
            wrapExclusive = Math.max(0, runtime.crawlAnchorIndex);
        }
        if (wrapExclusive <= 0) return;

        int cursor = Math.max(0, Math.min(runtime.globalFrontierProbeCursor, wrapExclusive - 1));
        int checkLimit = Math.min(budget, wrapExclusive);

        long start = module.beginScannerTimer();
        try {
            for (int scanned = 0; scanned < checkLimit; scanned++) {
                byte type = classifyAt(module, inputs, mapCache, scanPos, metaNeighborPos,
                    cursor, NukerProfiler.Scanner.FULL);
                if (type != CandidatePolicy.NONE) {
                    if (runtime.fullScanLowestSeen < 0 || cursor < runtime.fullScanLowestSeen) {
                        runtime.fullScanLowestSeen = cursor;
                        runtime.setFrontierFromScanner(cursor);
                    }
                }

                int next = cursor + 1;
                if (next >= wrapExclusive) {
                    // Sweep complete. If we found anything it's already promoted
                    // to frontier; clear the memory so the next sweep starts fresh.
                    runtime.fullScanLowestSeen = -1;
                    cursor = 0;
                } else {
                    cursor = next;
                }
            }
        } finally {
            module.endScannerTimer(NukerProfiler.Scanner.FULL, start);
        }

        runtime.globalFrontierProbeCursor = cursor;
    }

    /**
     * Regress the full-scan cursor to the start of the shell that is
     * {@code movementBlocks} below the current last-action shell, and clear
     * the "lowest seen" memory. Called by the rebase path on movement so the
     * full scan immediately re-considers the close-side region.
     */
    static void regressFullScanForMovement(NukerMapCache mapCache, NukerRuntime runtime, int movementBlocks) {
        SphereMapStore.MapPoint anchorPoint = mapCache.pointAt(mapCache.clampToCandidateIndex(runtime.frontierIndex));
        if (anchorPoint == null) {
            runtime.globalFrontierProbeCursor = 0;
            runtime.fullScanLowestSeen = -1;
            return;
        }
        int anchorShell = clamp((int) Math.floor(anchorPoint.distance), 0, SphereMapStore.MAX_RADIUS);
        int targetShell = Math.max(0, anchorShell - Math.max(1, movementBlocks));
        int shellStart = mapCache.shellStartCandidate(targetShell);
        runtime.globalFrontierProbeCursor = shellStart < 0 ? 0 : shellStart;
        runtime.fullScanLowestSeen = -1;
    }

    // ---- completion probe ----------------------------------------------------

    /**
     * Run the completion probe if it's pending. Called once per tick before crawl,
     * so any frontier movement settles before crawl walks. No-op if not pending.
     *
     * Algorithm: factor-stride modular search. Picks one factor f of (n-1) such
     * that f > 0.1*n (smallest such), then visits indices in stride-f residue
     * classes. On finding a valid action, the upper bound shrinks to the smallest
     * multiple of f greater than the hit index, narrowing the search toward the
     * closest hits. Sweep ends when the cursor returns to 0; the lowest hit
     * becomes the frontier.
     */
    static void runCompletionProbeIfPending(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        NukerRuntime runtime,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos
    ) {
        if (!runtime.completionProbePending) return;
        runtime.completionProbePending = false;
        if (!mapCache.hasCandidates()) {
            runtime.completionProbeUpperExclusive = 0;
            return;
        }

        int n = Math.max(0, Math.min(runtime.completionProbeUpperExclusive, mapCache.candidateCount()));
        runtime.completionProbeUpperExclusive = 0;
        if (n <= 1) return;

        int factor = pickFactor(n);
        int upper = n; // current modulus, shrinks on hits
        int lowestHit = -1;

        long start = module.beginScannerTimer();
        try {
            for (int residue = 0; residue < factor; residue++) {
                int idx = residue;
                while (idx < upper) {
                    byte type = classifyAt(module, inputs, mapCache, scanPos, metaNeighborPos,
                        idx, NukerProfiler.Scanner.COMPLETION);
                    if (type != CandidatePolicy.NONE) {
                        if (lowestHit < 0 || idx < lowestHit) lowestHit = idx;
                        // Shrink upper bound to smallest multiple of factor > idx.
                        int shrunk = ((idx / factor) + 1) * factor;
                        if (shrunk < upper) upper = shrunk;
                    }
                    idx += factor;
                }
                // Skip remaining residue classes that would only land above the new upper.
                if (residue + 1 >= upper) break;
            }
        } finally {
            module.endScannerTimer(NukerProfiler.Scanner.COMPLETION, start);
        }

        if (lowestHit >= 0) runtime.setFrontierFromScanner(lowestHit);
        // No hit: leave frontier alone. Crawl will be yielding (no anchor); full
        // scan and the future block-update mechanism remain the discovery path.
    }

    /** Smallest factor of (n-1) strictly greater than 0.1*n, or 1 if none. */
    private static int pickFactor(int n) {
        if (n <= 1) return 1;
        int m = n - 1;
        double threshold = 0.1 * n;
        for (int f = 2; f <= m; f++) {
            if (m % f == 0 && f > threshold) return f;
        }
        return 1;
    }

    // ---- shared helpers ------------------------------------------------------

    private static boolean enqueueCandidate(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        NukerActionQueue queue,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos,
        int mapIndex
    ) {
        if (queue.isFull()) return false;
        SphereMapStore.MapPoint point = mapCache.pointAt(mapIndex);
        if (point == null) return false;

        scanPos.set(inputs.playerBlockX + point.dx, inputs.playerBlockY + point.dy, inputs.playerBlockZ + point.dz);
        byte type = CandidatePolicy.classify(scanPos, point, inputs, metaNeighborPos);
        module.recordScan(NukerProfiler.Scanner.CRAWL, type);
        if (type == CandidatePolicy.NONE) return false;

        queue.append(scanPos.asLong(), mapIndex, point.distance, type);
        return true;
    }

    private static byte classifyAt(
        OptimizedNuker module,
        CandidatePolicy.Inputs inputs,
        NukerMapCache mapCache,
        BlockPos.Mutable scanPos,
        BlockPos.Mutable metaNeighborPos,
        int mapIndex,
        NukerProfiler.Scanner scanner
    ) {
        if (!mapCache.isCandidateIndex(mapIndex)) return CandidatePolicy.NONE;
        SphereMapStore.MapPoint point = mapCache.pointAt(mapIndex);
        if (point == null) return CandidatePolicy.NONE;
        scanPos.set(inputs.playerBlockX + point.dx, inputs.playerBlockY + point.dy, inputs.playerBlockZ + point.dz);
        byte type = CandidatePolicy.classify(scanPos, point, inputs, metaNeighborPos);
        module.recordScan(scanner, type);
        return type;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
