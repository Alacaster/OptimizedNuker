package dev.firstmage.optimizednuker.modules;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tick profiler. Hot-path contract:
 * <ul>
 *   <li>When {@link #active} is false (module off / profiling setting off), every
 *       public method short-circuits on the first read of {@link #active}.</li>
 *   <li>When active, every per-classify hook ({@link #recordScan}) is one indexed
 *       primitive increment. Timing only at phase / scanner boundaries.</li>
 *   <li>No allocations during a tick. All buffers are reused.</li>
 * </ul>
 *
 * <p>Phase / Scanner taxonomy:
 * <ul>
 *   <li>Phases: OBSERVE, SCAN, MODIFY (top-level work units within a tick)</li>
 *   <li>Scanners: CRAWL, COMPLETION, FULL (work-producers inside SCAN)</li>
 * </ul>
 *
 * <p>Everything tracked per-tick is also aggregated in the window with avg/min/max
 * where it makes sense so the HUD can mirror them side by side.
 */
final class NukerProfiler {
    enum Phase {
        OBSERVE("observe"), SCAN("scan"), MODIFY("modify");
        final String label;
        Phase(String label) { this.label = label; }
    }

    enum Scanner {
        CRAWL("crawl"), COMPLETION("completion"), FULL("full");
        final String label;
        Scanner(String label) { this.label = label; }
    }

    private static final int PHASE_COUNT = Phase.values().length;
    private static final int SCANNER_COUNT = Scanner.values().length;

    /** Inter-tick gaps above this count as missed game-loop ticks. */
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

    // Lazy scoreboard cache.
    private final ArrayList<String> cachedScoreboardLines = new ArrayList<>(48);
    private boolean scoreboardDirty = true;
    private final StringBuilder lineBuilder = new StringBuilder(192);

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
        if (tickStartNs != 0L) lastTickStartNs = tickStartNs;
        tickStartNs = 0L;
    }

    void endTick(int successes, int queuedAtEnd, int frontierIndex, int crawlAnchorIndex,
                 boolean frontierIsRealAction, boolean crawlYielding,
                 boolean logOutput, boolean chatOutput, Consumer<String> chatSink) {
        if (!active) return;

        long now = System.nanoTime();
        current.totalNs = Math.max(0L, now - tickStartNs);
        current.actionSuccesses = Math.max(current.actionSuccesses, successes);
        current.queuedAtEnd = queuedAtEnd;
        current.frontierIndex = frontierIndex;
        current.crawlAnchorIndex = crawlAnchorIndex;
        current.frontierIsRealAction = frontierIsRealAction;
        current.crawlYielding = crawlYielding;

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

    /** Wipe state on module re-activation so the HUD starts fresh. */
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

    long beginPhase()   { return active ? System.nanoTime() : 0L; }
    long beginScanner() { return active ? System.nanoTime() : 0L; }

    void endPhase(Phase phase, long startNs) {
        if (!active || startNs == 0L) return;
        current.phaseNs[phase.ordinal()] += Math.max(0L, System.nanoTime() - startNs);
    }

    void endScanner(Scanner scanner, long startNs) {
        if (!active || startNs == 0L) return;
        current.scannerNs[scanner.ordinal()] += Math.max(0L, System.nanoTime() - startNs);
    }

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

    void recordActionAttempt()    { if (active) current.actionAttempts++; }
    void recordActionDelivered()  { if (active) current.actionSuccesses++; }
    void recordActionFailed()     { if (active) current.actionFailures++; }

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
        cachedScoreboardLines.add("Optimized Nuker");
        if (window.count > 0) appendWindowHeader();
        if (window.count > 0) appendRatesBlock();
        if (hasLastTick || window.count > 0) appendTimesTable();
        if (hasLastTick || window.count > 0) appendScannerTable();
        if (hasLastTick) appendStateBlock();
        if (hasBest || hasWorst) appendExtremes();
    }

    // ---- HUD sections --------------------------------------------------------

    private void appendWindowHeader() {
        double seconds = window.windowSpanSeconds();
        double clientHz = (window.count + window.missedTicks) / Math.max(1e-9, seconds);
        StringBuilder sb = lineBuilder; sb.setLength(0);
        sb.append("window  ")
          .append(window.count).append(" / ").append(configuredWindowTicks).append(" ticks  ·  ")
          .append(fmt3(seconds)).append(" s  ·  ")
          .append(fmt2(clientHz)).append(" Hz  ·  ")
          .append(window.missedTicks).append(" missed");
        cachedScoreboardLines.add(sb.toString());
    }

    private void appendRatesBlock() {
        double seconds = window.windowSpanSeconds();
        double actionsPerSec = window.successes / Math.max(1e-9, seconds);
        double perTick = window.count > 0 ? (double) window.successes / window.count : 0.0;
        double clientHz = (window.count + window.missedTicks) / Math.max(1e-9, seconds);
        double at20Hz = clientHz > 0 ? actionsPerSec * (20.0 / clientHz) : 0.0;
        double goalHitPct = window.actionGoals > 0
            ? 100.0 * window.successes / Math.max(1, window.actionGoals)
            : 0.0;

        cachedScoreboardLines.add(line("rates"));
        cachedScoreboardLines.add(kv("  actions/s",     fmt1(actionsPerSec)));
        cachedScoreboardLines.add(kv("  per-tick",      fmt2(perTick)));
        cachedScoreboardLines.add(kv("  @20Hz norm",    fmt1(at20Hz)));
        cachedScoreboardLines.add(kv("  goals",         window.successes + " / " + window.actionGoals
                                                         + " (" + fmt1(goalHitPct) + "%)"));
        cachedScoreboardLines.add(kv("  exec-fails",    Integer.toString(window.failures)));
        cachedScoreboardLines.add(kv("  idle ticks",    window.idleTicks + " / " + window.count));
        cachedScoreboardLines.add(kv("  yield ticks",   window.yieldTicks + " / " + window.count));
    }

    private void appendTimesTable() {
        cachedScoreboardLines.add(line("times                cur      avg      min      max   (ms)"));
        appendTimeRow("  tick",     lastTick.totalNs,
                                    avgNs(window.totalNs, window.count),
                                    window.minTotalNs, window.maxTotalNs);
        appendTimeRow("  inter",    lastTick.interTickNs,
                                    avgNs(window.interTickNsTotal, window.count),
                                    window.minInterTickNs, window.maxInterTickNs);
        for (Phase p : Phase.values()) {
            int i = p.ordinal();
            appendTimeRow("  " + p.label,
                lastTick.phaseNs[i],
                avgNs(window.phaseNs[i], window.count),
                window.minPhaseNs[i], window.maxPhaseNs[i]);
        }
    }

    private void appendTimeRow(String label, long curNs, long avgNs, long minNs, long maxNs) {
        StringBuilder sb = lineBuilder; sb.setLength(0);
        sb.append(padRight(label, 13))
          .append(padLeft(fmtMs(curNs),  8)).append(' ')
          .append(padLeft(fmtMs(avgNs),  8)).append(' ')
          .append(padLeft(fmtMs(minNs),  8)).append(' ')
          .append(padLeft(fmtMs(maxNs),  8));
        cachedScoreboardLines.add(sb.toString());
    }

    private void appendScannerTable() {
        double seconds = window.windowSpanSeconds();
        cachedScoreboardLines.add(line("scanner       scans  acc    ms   |   scans/s   acc/s   acc%   ms/tick"));
        for (Scanner s : Scanner.values()) {
            int i = s.ordinal();
            long curScans   = lastTick.scannerScans[i];
            long curAccepts = lastTick.scannerAccepts[i];
            long curNs      = lastTick.scannerNs[i];
            long winScans   = window.scannerScans[i];
            long winAccepts = window.scannerAccepts[i];
            long winNs      = window.scannerNs[i];

            double scansPerSec   = winScans  / Math.max(1e-9, seconds);
            double acceptsPerSec = winAccepts / Math.max(1e-9, seconds);
            double acceptPct     = winScans > 0 ? 100.0 * winAccepts / winScans : Double.NaN;
            double msPerTick     = window.count > 0 ? (winNs / 1_000_000.0) / window.count : 0.0;

            StringBuilder sb = lineBuilder; sb.setLength(0);
            sb.append(padRight("  " + s.label, 13))
              .append(padLeft(Long.toString(curScans),   6)).append(' ')
              .append(padLeft(Long.toString(curAccepts), 4)).append(' ')
              .append(padLeft(fmtMs(curNs),              7)).append("  | ")
              .append(padLeft(fmt1(scansPerSec),         9)).append(' ')
              .append(padLeft(fmt1(acceptsPerSec),       7)).append(' ')
              .append(padLeft(Double.isNaN(acceptPct) ? "-" : fmt1(acceptPct), 6)).append("  ")
              .append(padLeft(fmt3(msPerTick),           7));
            cachedScoreboardLines.add(sb.toString());
        }
    }

    private void appendStateBlock() {
        cachedScoreboardLines.add(line("state"));
        StringBuilder sb = lineBuilder;

        sb.setLength(0);
        sb.append("  queue       ")
          .append(lastTick.queuedAtStart).append(" -> ").append(lastTick.queuedAtEnd);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("  frontier    ").append(lastTick.frontierIndex)
          .append(lastTick.frontierIsRealAction ? "  (real)" : "  (inferred)");
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("  anchor      ").append(lastTick.crawlAnchorIndex);
        cachedScoreboardLines.add(sb.toString());

        sb.setLength(0);
        sb.append("  crawl       ").append(lastTick.crawlYielding ? "yielding" : "active");
        cachedScoreboardLines.add(sb.toString());
    }

    private void appendExtremes() {
        cachedScoreboardLines.add(line("extremes"));
        if (hasBest)  cachedScoreboardLines.add(describeExtreme("  best ", bestSnapshot));
        if (hasWorst) cachedScoreboardLines.add(describeExtreme("  worst", worstSnapshot));
    }

    private String describeExtreme(String label, TickStats t) {
        StringBuilder sb = lineBuilder; sb.setLength(0);
        sb.append(label).append("  tick ").append(fmtMs(t.totalNs)).append(" ms")
          .append("   actions ").append(t.actionSuccesses).append("/").append(t.actionGoal)
          .append("   crawl ").append(t.scannerScans[Scanner.CRAWL.ordinal()])
          .append("   full ").append(t.scannerScans[Scanner.FULL.ordinal()]);
        return sb.toString();
    }

    // ---- formatting helpers --------------------------------------------------

    private static long avgNs(long total, int count) {
        return count > 0 ? total / count : 0L;
    }

    private static String line(String text) {
        return text;
    }

    private static String kv(String label, String value) {
        return padRight(label, 16) + value;
    }

    private static String padLeft(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        sb.append(s);
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        sb.append(s);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.toString();
    }

    private static String fmtMs(long ns) {
        if (ns == Long.MAX_VALUE) return "-";
        return String.format(Locale.ROOT, "%.3f", ns / 1_000_000.0);
    }

    private static String fmt1(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "-";
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static String fmt2(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "-";
        return String.format(Locale.ROOT, "%.2f", d);
    }

    private static String fmt3(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) return "-";
        return String.format(Locale.ROOT, "%.3f", d);
    }

    // ---- value classes -------------------------------------------------------

    private static final class TickStats {
        final long[] phaseNs        = new long[PHASE_COUNT];
        final long[] scannerNs      = new long[SCANNER_COUNT];
        final int[]  scannerScans   = new int[SCANNER_COUNT];
        final int[]  scannerAccepts = new int[SCANNER_COUNT];

        long totalNs = -1L;
        long interTickNs;
        int  actionGoal;
        int  actionSuccesses;
        int  actionAttempts;
        int  actionFailures;
        int  queuedAtStart;
        int  queuedAtEnd;
        int  frontierIndex;
        int  crawlAnchorIndex;
        boolean frontierIsRealAction;
        boolean crawlYielding;

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
            frontierIsRealAction = false;
            crawlYielding = false;
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
            frontierIsRealAction = other.frontierIsRealAction;
            crawlYielding = other.crawlYielding;
        }
    }

    private static final class WindowStats {
        final long[] phaseNs        = new long[PHASE_COUNT];
        final long[] minPhaseNs     = new long[PHASE_COUNT];
        final long[] maxPhaseNs     = new long[PHASE_COUNT];
        final long[] scannerNs      = new long[SCANNER_COUNT];
        final long[] scannerScans   = new long[SCANNER_COUNT];
        final long[] scannerAccepts = new long[SCANNER_COUNT];

        int windowTicksTarget;
        int count;
        long totalNs;
        long minTotalNs;
        long maxTotalNs;
        long interTickNsTotal;
        long minInterTickNs;
        long maxInterTickNs;
        int successes;
        int actionGoals;
        int attempts;
        int failures;
        int idleTicks;
        int goalHitTicks;
        int yieldTicks;

        long firstTickStartNs;
        long lastTickEndNs;
        int  missedTicks;

        void reset(int target) {
            windowTicksTarget = target;
            count = 0;
            totalNs = 0L;
            minTotalNs = Long.MAX_VALUE;
            maxTotalNs = 0L;
            interTickNsTotal = 0L;
            minInterTickNs = Long.MAX_VALUE;
            maxInterTickNs = 0L;
            successes = 0;
            actionGoals = 0;
            attempts = 0;
            failures = 0;
            idleTicks = 0;
            goalHitTicks = 0;
            yieldTicks = 0;
            firstTickStartNs = 0L;
            lastTickEndNs = 0L;
            missedTicks = 0;
            java.util.Arrays.fill(phaseNs, 0L);
            java.util.Arrays.fill(minPhaseNs, Long.MAX_VALUE);
            java.util.Arrays.fill(maxPhaseNs, 0L);
            java.util.Arrays.fill(scannerNs, 0L);
            java.util.Arrays.fill(scannerScans, 0L);
            java.util.Arrays.fill(scannerAccepts, 0L);
        }

        void accept(TickStats tick, TickStats best, TickStats worst, NukerProfiler outer) {
            count++;
            totalNs += tick.totalNs;
            if (tick.totalNs < minTotalNs) minTotalNs = tick.totalNs;
            if (tick.totalNs > maxTotalNs) maxTotalNs = tick.totalNs;

            interTickNsTotal += tick.interTickNs;
            if (tick.interTickNs < minInterTickNs) minInterTickNs = tick.interTickNs;
            if (tick.interTickNs > maxInterTickNs) maxInterTickNs = tick.interTickNs;

            successes   += tick.actionSuccesses;
            actionGoals += tick.actionGoal;
            attempts    += tick.actionAttempts;
            failures    += tick.actionFailures;
            if (tick.actionSuccesses == 0) idleTicks++;
            if (tick.actionGoal > 0 && tick.actionSuccesses >= tick.actionGoal) goalHitTicks++;
            if (tick.crawlYielding) yieldTicks++;

            for (int i = 0; i < PHASE_COUNT; i++) {
                phaseNs[i] += tick.phaseNs[i];
                if (tick.phaseNs[i] < minPhaseNs[i]) minPhaseNs[i] = tick.phaseNs[i];
                if (tick.phaseNs[i] > maxPhaseNs[i]) maxPhaseNs[i] = tick.phaseNs[i];
            }
            for (int i = 0; i < SCANNER_COUNT; i++) {
                scannerNs[i]      += tick.scannerNs[i];
                scannerScans[i]   += tick.scannerScans[i];
                scannerAccepts[i] += tick.scannerAccepts[i];
            }

            long tickStart = outer.tickStartNs;
            if (firstTickStartNs == 0L) firstTickStartNs = tickStart;
            lastTickEndNs = tickStart + tick.totalNs;
            if (tick.interTickNs >= MISSED_TICK_THRESHOLD_NS) {
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

        double windowSpanSeconds() {
            if (firstTickStartNs == 0L) return 0.0;
            return Math.max(1L, lastTickEndNs - firstTickStartNs) / 1_000_000_000.0;
        }
    }
}
