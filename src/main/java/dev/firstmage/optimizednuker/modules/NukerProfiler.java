package dev.firstmage.optimizednuker.modules;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tick profiler for the Optimized Nuker.
 *
 * Performance contract:
 * - When {@link #active} is false (module off or profiling setting off), every
 *   public method short-circuits on the first read of {@link #active}. The hot
 *   path is one branch predicted at the call site.
 * - When active, every per-classify hook ({@link #recordScan}) is one indexed
 *   primitive increment. No timing per classify - only per phase boundary and
 *   per scanner boundary.
 * - No allocations in any per-tick path. {@link TickStats} and {@link WindowStats}
 *   are reused; the only scoreboard line list is built lazily and only when the
 *   HUD or scoreboard is actually requested.
 *
 * Phase / Scanner taxonomy:
 *   Phases  : OBSERVE, SCAN, MODIFY  (top-level work units within a tick)
 *   Scanners: CRAWL, COMPLETION, FULL  (work-producers inside SCAN)
 *
 * Counters per scanner: scans (candidates classified), accepts (classify != NONE),
 * timeNs (wall-clock spent in the scanner's call). Time is recorded once per
 * scanner-call, not once per scan, so the per-classify cost is a single integer
 * increment.
 */
final class NukerProfiler {
    enum Phase {
        OBSERVE("observe"),
        SCAN("scan"),
        MODIFY("modify");

        final String label;
        Phase(String label) { this.label = label; }
    }

    enum Scanner {
        CRAWL("crawl"),
        COMPLETION("completion"),
        FULL("full");

        final String label;
        Scanner(String label) { this.label = label; }
    }

    private static final int PHASE_COUNT = Phase.values().length;
    private static final int SCANNER_COUNT = Scanner.values().length;

    /** Inter-tick gaps above this threshold count as missed ticks for client-Hz estimation. */
    private static final long MISSED_TICK_THRESHOLD_NS = 75_000_000L;

    private final Logger log;

    private final TickStats current = new TickStats();
    private final TickStats lastTick = new TickStats();
    private final TickStats bestSnapshot = new TickStats();
    private final TickStats worstSnapshot = new TickStats();
    private final WindowStats window = new WindowStats();

    private boolean active;
    private boolean hasLastTick;
    private boolean hasBest;
    private boolean hasWorst;
    private int configuredWindowTicks = 100;
    private int windowTickCounter;
    private long tickStartNs;
    private long lastTickStartNs;

    // Scoreboard cache. Rebuilt only on tick boundaries; reads are O(1).
    private final ArrayList<String> cachedScoreboardLines = new ArrayList<>(16);
    private boolean scoreboardDirty = true;

    // Reused for line formatting; avoids per-line StringBuilder allocations.
    private final StringBuilder lineBuilder = new StringBuilder(160);

    NukerProfiler(Logger log) {
        this.log = log;
    }

    // ---- lifecycle ------------------------------------------------------------

    void beginTick(int windowTicks, int queuedAtStart, int frontierIndex, int crawlAnchorIndex) {
        long now = System.nanoTime();
        active = true;
        configuredWindowTicks = Math.max(1, windowTicks);
        if (window.windowTicksTarget != configuredWindowTicks) resetWindow();

        current.reset();
        current.queuedAtStart = queuedAtStart;
        current.frontierIndex = frontierIndex;
        current.crawlAnchorIndex = crawlAnchorIndex;
        current.interTickNs = lastTickStartNs == 0L ? 0L : Math.max(0L, now - lastTickStartNs);
        tickStartNs = now;
    }

    void cancelTick() {
        active = false;
        // Still record this tick's start so the next tick's inter-tick measurement
        // reflects actual game-loop spacing, not the gap since the last live tick.
        if (tickStartNs != 0L) lastTickStartNs = tickStartNs;
        tickStartNs = 0L;
    }

    void endTick(int successes, int queuedAtEnd, int frontierIndex, int crawlAnchorIndex,
                 boolean logOutput, boolean chatOutput, Consumer<String> chatSink) {
        if (!active) return;

        long now = System.nanoTime();
        current.totalNs = Math.max(0L, now - tickStartNs);
        current.actionSuccesses = Math.max(current.actionSuccesses, successes);
        current.queuedAtEnd = queuedAtEnd;
        current.frontierIndex = frontierIndex;
        current.crawlAnchorIndex = crawlAnchorIndex;

        lastTick.copyFrom(current);
        hasLastTick = true;

        window.accept(current, bestSnapshot, worstSnapshot, this);
        windowTickCounter++;
        active = false;
        lastTickStartNs = tickStartNs;
        tickStartNs = 0L;
        scoreboardDirty = true;

        if (windowTickCounter >= configuredWindowTicks) {
            emitScoreboard(logOutput, chatOutput, chatSink);
            resetWindow();
        }
    }

    /**
     * Wipes per-tick and window state. Called when the module re-activates so
     * the HUD doesn't show pre-deactivation numbers mixed with new ones.
     */
    void onModuleReactivate() {
        hasLastTick = false;
        hasBest = false;
        hasWorst = false;
        windowTickCounter = 0;
        window.reset(configuredWindowTicks);
        cachedScoreboardLines.clear();
        scoreboardDirty = true;
    }

    // ---- per-tick hooks (hot path) -------------------------------------------

    /** Returns the start timestamp for a phase, or 0 if profiling is off. */
    long beginPhase() {
        return active ? System.nanoTime() : 0L;
    }

    void endPhase(Phase phase, long startNs) {
        if (!active || startNs == 0L) return;
        current.phaseNs[phase.ordinal()] += Math.max(0L, System.nanoTime() - startNs);
    }

    /** Returns the start timestamp for a scanner call, or 0 if profiling is off. */
    long beginScanner() {
        return active ? System.nanoTime() : 0L;
    }

    void endScanner(Scanner scanner, long startNs) {
        if (!active || startNs == 0L) return;
        current.scannerNs[scanner.ordinal()] += Math.max(0L, System.nanoTime() - startNs);
    }

    /** Per-classify hook. One indexed increment per call when active. */
    void recordScan(Scanner scanner, boolean accepted) {
        if (!active) return;
        int i = scanner.ordinal();
        current.scannerScans[i]++;
        if (accepted) current.scannerAccepts[i]++;
    }

    void recordActionGoal(int actionGoal) {
        if (!active) return;
        if (actionGoal > current.actionGoal) current.actionGoal = actionGoal;
    }

    void recordActionAttempt() {
        if (!active) return;
        current.actionAttempts++;
    }

    void recordActionDelivered() {
        if (!active) return;
        current.actionSuccesses++;
    }

    void recordActionFailed() {
        if (!active) return;
        current.actionFailures++;
    }

    // ---- HUD output -----------------------------------------------------------

    List<String> liveScoreboardLines() {
        if (!hasLastTick && window.count <= 0) return Collections.emptyList();
        if (scoreboardDirty) {
            rebuildScoreboardLines();
            scoreboardDirty = false;
        }
        return cachedScoreboardLines;
    }

    // ---- internals ------------------------------------------------------------

    private void resetWindow() {
        window.reset(configuredWindowTicks);
        windowTickCounter = 0;
        hasBest = false;
        hasWorst = false;
        scoreboardDirty = true;
    }

    private void emitScoreboard(boolean logOutput, boolean chatOutput, Consumer<String> chatSink) {
        if (window.count <= 0) return;
        rebuildScoreboardLines();
        scoreboardDirty = false;

        for (int i = 0; i < cachedScoreboardLines.size(); i++) {
            String line = cachedScoreboardLines.get(i);
            if (logOutput) log.info("{}", line);
            if (chatOutput && chatSink != null) chatSink.accept(line);
        }
    }

    private void rebuildScoreboardLines() {
        cachedScoreboardLines.clear();
        cachedScoreboardLines.add("Optimized Nuker profiler");
        if (hasLastTick) appendLastTickLines();
        if (window.count > 0) appendWindowLines();
    }

    private void appendLastTickLines() {
        StringBuilder sb = lineBuilder;

        sb.setLength(0);
        sb.append("tick ").append(fmtMs(lastTick.totalNs)).append("ms")
          .append(" inter ").append(fmtMs(lastTick.interTickNs)).append("ms")
          .append(" actions ").append(lastTick.actionSuccesses).append("/").append(lastTick.actionGoal)
          .append(" failed ").append(lastTick.actionFailures);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("phases obs=").append(fmtMs(lastTick.phaseNs[Phase.OBSERVE.ordinal()]))
          .append(" scan=").append(fmtMs(lastTick.phaseNs[Phase.SCAN.ordinal()]))
          .append(" mod=").append(fmtMs(lastTick.phaseNs[Phase.MODIFY.ordinal()]));
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("scans crawl=").append(lastTick.scannerScans[Scanner.CRAWL.ordinal()])
          .append("/").append(lastTick.scannerAccepts[Scanner.CRAWL.ordinal()])
          .append(" comp=").append(lastTick.scannerScans[Scanner.COMPLETION.ordinal()])
          .append("/").append(lastTick.scannerAccepts[Scanner.COMPLETION.ordinal()])
          .append(" full=").append(lastTick.scannerScans[Scanner.FULL.ordinal()])
          .append("/").append(lastTick.scannerAccepts[Scanner.FULL.ordinal()]);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("scan time c=").append(fmtMs(lastTick.scannerNs[Scanner.CRAWL.ordinal()]))
          .append(" comp=").append(fmtMs(lastTick.scannerNs[Scanner.COMPLETION.ordinal()]))
          .append(" full=").append(fmtMs(lastTick.scannerNs[Scanner.FULL.ordinal()]));
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("queue ").append(lastTick.queuedAtStart).append("->").append(lastTick.queuedAtEnd)
          .append(" frontier=").append(lastTick.frontierIndex)
          .append(" anchor=").append(lastTick.crawlAnchorIndex);
        cachedScoreboardLines.add(sb.toString());
    }

    private void appendWindowLines() {
        StringBuilder sb = lineBuilder;
        long windowSpanNs = Math.max(1L, window.windowSpanNs());
        double windowSeconds = windowSpanNs / 1_000_000_000.0;
        double clientHz = (window.count + window.missedTicks) / windowSeconds;
        double actionsPerSec = window.successes / windowSeconds;
        double actionsPerTickAvg = actionsPerSec / 20.0;
        double actionsAt20Hz = clientHz > 0.0 ? actionsPerSec * (20.0 / clientHz) : 0.0;
        int remaining = Math.max(0, configuredWindowTicks - windowTickCounter);

        sb.setLength(0);
        sb.append("window ").append(window.count).append("/").append(configuredWindowTicks)
          .append(" ").append(fmt3(windowSeconds)).append("s")
          .append(" client=").append(fmt2(clientHz)).append("Hz")
          .append(" rem=").append(remaining);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("rate ").append(fmt2(actionsPerSec)).append("/s")
          .append(" perTick ").append(fmt2(actionsPerTickAvg))
          .append(" @20Hz ").append(fmt2(actionsAt20Hz)).append("/s");
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("totals success=").append(window.successes)
          .append("/").append(window.actionGoals)
          .append(" failed=").append(window.failures)
          .append(" attempts=").append(window.attempts);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        long avgTotal = window.totalNs / window.count;
        sb.append("avg tick ").append(fmtMs(avgTotal)).append("ms")
          .append(" obs=").append(fmtMs(window.phaseNs[Phase.OBSERVE.ordinal()] / window.count))
          .append(" scan=").append(fmtMs(window.phaseNs[Phase.SCAN.ordinal()] / window.count))
          .append(" mod=").append(fmtMs(window.phaseNs[Phase.MODIFY.ordinal()] / window.count));
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("scan totals crawl=").append(window.scannerScans[Scanner.CRAWL.ordinal()])
          .append("/").append(window.scannerAccepts[Scanner.CRAWL.ordinal()])
          .append(" comp=").append(window.scannerScans[Scanner.COMPLETION.ordinal()])
          .append("/").append(window.scannerAccepts[Scanner.COMPLETION.ordinal()])
          .append(" full=").append(window.scannerScans[Scanner.FULL.ordinal()])
          .append("/").append(window.scannerAccepts[Scanner.FULL.ordinal()]);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("scan time crawl=").append(fmtMs(window.scannerNs[Scanner.CRAWL.ordinal()]))
          .append(" comp=").append(fmtMs(window.scannerNs[Scanner.COMPLETION.ordinal()]))
          .append(" full=").append(fmtMs(window.scannerNs[Scanner.FULL.ordinal()]));
        cachedScoreboardLines.add(sb.toString());

        if (hasBest) {
            sb.setLength(0);
            sb.append("best ");
            appendTickDescription(sb, bestSnapshot);
            cachedScoreboardLines.add(sb.toString());
        }
        if (hasWorst) {
            sb.setLength(0);
            sb.append("worst ");
            appendTickDescription(sb, worstSnapshot);
            cachedScoreboardLines.add(sb.toString());
        }
    }

    private static void appendTickDescription(StringBuilder sb, TickStats t) {
        sb.append(fmtMs(t.totalNs)).append("ms")
          .append(" act=").append(t.actionSuccesses).append("/").append(t.actionGoal)
          .append(" fail=").append(t.actionFailures)
          .append(" scans c=").append(t.scannerScans[Scanner.CRAWL.ordinal()])
          .append(" comp=").append(t.scannerScans[Scanner.COMPLETION.ordinal()])
          .append(" full=").append(t.scannerScans[Scanner.FULL.ordinal()]);
    }

    private static String fmtMs(long ns) {
        return String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0);
    }

    private static String fmt2(double d) {
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String fmt3(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    // ---- value classes --------------------------------------------------------

    /** Per-tick stats. All fields are primitives or primitive arrays; no allocations during a tick. */
    private static final class TickStats {
        final long[] phaseNs = new long[PHASE_COUNT];
        final long[] scannerNs = new long[SCANNER_COUNT];
        final int[] scannerScans = new int[SCANNER_COUNT];
        final int[] scannerAccepts = new int[SCANNER_COUNT];

        long totalNs = -1L;
        long interTickNs;

        int actionGoal;
        int actionSuccesses;
        int actionAttempts;
        int actionFailures;

        int queuedAtStart;
        int queuedAtEnd;
        int frontierIndex;
        int crawlAnchorIndex;

        void reset() {
            java.util.Arrays.fill(phaseNs, 0L);
            java.util.Arrays.fill(scannerNs, 0L);
            java.util.Arrays.fill(scannerScans, 0);
            java.util.Arrays.fill(scannerAccepts, 0);
            totalNs = -1L;
            interTickNs = 0L;
            actionGoal = 0;
            actionSuccesses = 0;
            actionAttempts = 0;
            actionFailures = 0;
            queuedAtStart = 0;
            queuedAtEnd = 0;
            frontierIndex = 0;
            crawlAnchorIndex = 0;
        }

        void copyFrom(TickStats other) {
            System.arraycopy(other.phaseNs, 0, phaseNs, 0, PHASE_COUNT);
            System.arraycopy(other.scannerNs, 0, scannerNs, 0, SCANNER_COUNT);
            System.arraycopy(other.scannerScans, 0, scannerScans, 0, SCANNER_COUNT);
            System.arraycopy(other.scannerAccepts, 0, scannerAccepts, 0, SCANNER_COUNT);
            totalNs = other.totalNs;
            interTickNs = other.interTickNs;
            actionGoal = other.actionGoal;
            actionSuccesses = other.actionSuccesses;
            actionAttempts = other.actionAttempts;
            actionFailures = other.actionFailures;
            queuedAtStart = other.queuedAtStart;
            queuedAtEnd = other.queuedAtEnd;
            frontierIndex = other.frontierIndex;
            crawlAnchorIndex = other.crawlAnchorIndex;
        }
    }

    /** Per-window aggregate. Running sums + wall-clock span for actions/sec. */
    private static final class WindowStats {
        final long[] phaseNs = new long[PHASE_COUNT];
        final long[] scannerNs = new long[SCANNER_COUNT];
        final long[] scannerScans = new long[SCANNER_COUNT];
        final long[] scannerAccepts = new long[SCANNER_COUNT];

        int windowTicksTarget;
        int count;
        long totalNs;
        int successes;
        int actionGoals;
        int attempts;
        int failures;

        long firstTickStartNs;
        long lastTickEndNs;
        int missedTicks;

        void reset(int target) {
            windowTicksTarget = target;
            count = 0;
            totalNs = 0L;
            successes = 0;
            actionGoals = 0;
            attempts = 0;
            failures = 0;
            firstTickStartNs = 0L;
            lastTickEndNs = 0L;
            missedTicks = 0;
            java.util.Arrays.fill(phaseNs, 0L);
            java.util.Arrays.fill(scannerNs, 0L);
            java.util.Arrays.fill(scannerScans, 0L);
            java.util.Arrays.fill(scannerAccepts, 0L);
        }

        void accept(TickStats tick, TickStats best, TickStats worst, NukerProfiler outer) {
            count++;
            totalNs += tick.totalNs;
            successes += tick.actionSuccesses;
            actionGoals += tick.actionGoal;
            attempts += tick.actionAttempts;
            failures += tick.actionFailures;
            for (int i = 0; i < PHASE_COUNT; i++) phaseNs[i] += tick.phaseNs[i];
            for (int i = 0; i < SCANNER_COUNT; i++) {
                scannerNs[i] += tick.scannerNs[i];
                scannerScans[i] += tick.scannerScans[i];
                scannerAccepts[i] += tick.scannerAccepts[i];
            }

            long tickStart = outer.tickStartNs;
            if (firstTickStartNs == 0L) firstTickStartNs = tickStart;
            lastTickEndNs = tickStart + tick.totalNs;
            if (tick.interTickNs >= MISSED_TICK_THRESHOLD_NS) {
                // Approximate count of dropped 50 ms ticks within this gap.
                missedTicks += (int) ((tick.interTickNs - 1L) / 50_000_000L);
            }

            if (!outer.hasBest || tick.totalNs < best.totalNs) {
                best.copyFrom(tick);
                outer.hasBest = true;
            }
            if (!outer.hasWorst || tick.totalNs > worst.totalNs) {
                worst.copyFrom(tick);
                outer.hasWorst = true;
            }
        }

        long windowSpanNs() {
            if (firstTickStartNs == 0L) return 0L;
            return Math.max(1L, lastTickEndNs - firstTickStartNs);
        }
    }
}
