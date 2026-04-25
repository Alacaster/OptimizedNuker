package dev.firstmage.optimizednuker.modules;

import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Map-driven nuker module.
 *
 * Each tick has three phases with disjoint state ownership:
 *
 *   observe   refreshes the map cache, populates {@link CandidatePolicy.Inputs} and
 *             {@link ScanContext}, and rebases the frontier on player movement.
 *   scan/modify loop  alternates {@link NukerScanners#runCrawl} (which produces
 *             actions and dispatches the completion probe internally) with
 *             {@link NukerScanners#runFullScan} (which relocates the frontier when
 *             crawl produces nothing). Each pass through the loop that produces
 *             actions runs {@link #runModify} to consume them.
 *   finalize  resets the cooldown timer when actions were performed, clears the
 *             work set, and emits a profiler tick boundary.
 *
 * Single-classify: candidates are classified once at produce time. {@link #runModify}
 * trusts the queued type and relies on {@code BlockUtils.canBreak}/{@code place}
 * gates to handle any stale targets. The frontier advances only on a successful
 * action, so failures don't cause the next tick to skip past unfinished work.
 */
public class OptimizedNuker extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("OptimizedNuker");
    private static final int META_DEBUG_BUDGET_PER_TICK = 32;

    /**
     * Per-call hard cap on crawl classifies. Bounded high enough that crawl can
     * fill its queues from a fresh anchor in one call under normal conditions,
     * but low enough that a pathological all-rejecting sweep can't lock the tick.
     */
    private static final int CRAWL_MAX_SCANS_PER_CALL = 4096;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgMeta = settings.createGroup("Meta");
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgPlacement = settings.createGroup("Placement");
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Shape> shape = sgGeneral.add(new EnumSetting.Builder<Shape>()
        .name("shape")
        .description("The shape of the main nuker area.")
        .defaultValue(Shape.Sphere)
        .build()
    );

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("How blocks are filtered before actions are queued.")
        .defaultValue(Mode.Flatten)
        .build()
    );

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Radius for sphere and uniform cube modes. Sphere mode is capped by the loaded map radius.")
        .defaultValue(4)
        .min(0)
        .build()
    );

    private final Setting<Integer> rangeUp = sgGeneral.add(new IntSetting.Builder().name("up").description("Cube range upward.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());
    private final Setting<Integer> rangeDown = sgGeneral.add(new IntSetting.Builder().name("down").description("Cube range downward.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());
    private final Setting<Integer> rangeLeft = sgGeneral.add(new IntSetting.Builder().name("left").description("Cube range left.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());
    private final Setting<Integer> rangeRight = sgGeneral.add(new IntSetting.Builder().name("right").description("Cube range right.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());
    private final Setting<Integer> rangeForward = sgGeneral.add(new IntSetting.Builder().name("forward").description("Cube range forward.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());
    private final Setting<Integer> rangeBack = sgGeneral.add(new IntSetting.Builder().name("back").description("Cube range backward.").defaultValue(1).min(0).visible(() -> shape.get() == Shape.Cube).build());

    private final Setting<Double> wallsRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("walls-range")
        .description("Range in which actions may happen through walls if raytracing is enabled.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder().name("delay").description("Delay in ticks between successful action batches.").defaultValue(0).min(0).build());
    private final Setting<Integer> maxActionsPerTick = sgGeneral.add(new IntSetting.Builder().name("max-actions-per-tick").description("Maximum successful actions to perform per tick.").defaultValue(1).min(1).build());
    private final Setting<Integer> probeGlobalFrontierScansPerTick = sgGeneral.add(new IntSetting.Builder().name("full-scan-scans-per-tick").description("Base full-scan budget per tick.").defaultValue(64).min(1).build());
    private final Setting<Integer> maxGlobalFrontierProbeScansPerTick = sgGeneral.add(new IntSetting.Builder().name("max-full-scan-scans-per-tick").description("Maximum full-scan scans per tick (base + bonus).").defaultValue(512).min(1).build());
    private final Setting<SortMode> sortMode = sgGeneral.add(new EnumSetting.Builder<SortMode>()
        .name("sort-mode")
        .description("How the action queues are prioritized.")
        .defaultValue(SortMode.Closest)
        .build()
    );

    private final Setting<Boolean> packetMine = sgGeneral.add(new BoolSetting.Builder().name("packet-mine").description("Attempt to packet mine break actions.").defaultValue(false).build());
    private final Setting<Boolean> suitableTools = sgGeneral.add(new BoolSetting.Builder().name("only-suitable-tools").description("Only queue break actions when the held tool is suitable.").defaultValue(false).build());
    private final Setting<Boolean> interact = sgGeneral.add(new BoolSetting.Builder().name("interact").description("Interact with break targets instead of mining them.").defaultValue(false).build());
    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder().name("rotate").description("Rotate server side before actions.").defaultValue(true).build());
    private final Setting<Boolean> enableRaytracing = sgGeneral.add(new BoolSetting.Builder().name("enable-raytracing").description("Use the stock raytrace gate before actions are queued.").defaultValue(false).build());

    private final Setting<Boolean> limitToMetaRegion = sgMeta.add(new BoolSetting.Builder().name("limit-to-meta-region").description("Restrict actions to the selected MiniHUD meta regions.").defaultValue(false).build());
    private final Setting<Boolean> invertMetaRegion = sgMeta.add(new BoolSetting.Builder().name("invert-meta-region").description("Treat the selected MiniHUD meta region union as outside-in: invalidate actions inside it and use the exterior shell for line placement.").defaultValue(false).build());
    private final Setting<Boolean> debug = sgMeta.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log MiniHUD meta-region decisions and play a ding when max successful actions are reached.")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> debugProfiling = sgMeta.add(new BoolSetting.Builder()
        .name("debug-profiling")
        .description("Collect and print tick performance scoreboards while debug is enabled.")
        .defaultValue(false)
        .visible(debug::get)
        .build()
    );
    private final Setting<Boolean> debugProfileLogOutput = sgMeta.add(new BoolSetting.Builder()
        .name("debug-profile-log-output")
        .description("Print profiling scoreboards to the log.")
        .defaultValue(true)
        .visible(() -> debug.get() && debugProfiling.get())
        .build()
    );
    private final Setting<Boolean> debugProfileChatOutput = sgMeta.add(new BoolSetting.Builder()
        .name("debug-profile-chat-output")
        .description("Print profiling scoreboards in chat.")
        .defaultValue(true)
        .visible(() -> debug.get() && debugProfiling.get())
        .build()
    );
    private final Setting<Boolean> debugProfileHudOutput = sgMeta.add(new BoolSetting.Builder()
        .name("debug-profile-hud-output")
        .description("Render the live profiling scoreboard on screen.")
        .defaultValue(true)
        .visible(() -> debug.get() && debugProfiling.get())
        .build()
    );
    private final Setting<Integer> debugProfileHudX = sgMeta.add(new IntSetting.Builder()
        .name("debug-profile-hud-x")
        .description("X position for the live profiling scoreboard.")
        .defaultValue(8)
        .min(0)
        .sliderRange(0, 600)
        .visible(() -> debug.get() && debugProfiling.get() && debugProfileHudOutput.get())
        .build()
    );
    private final Setting<Integer> debugProfileHudY = sgMeta.add(new IntSetting.Builder()
        .name("debug-profile-hud-y")
        .description("Y position for the live profiling scoreboard.")
        .defaultValue(8)
        .min(0)
        .sliderRange(0, 400)
        .visible(() -> debug.get() && debugProfiling.get() && debugProfileHudOutput.get())
        .build()
    );
    private final Setting<Integer> debugTickWindow = sgMeta.add(new IntSetting.Builder()
        .name("debug-tick-window")
        .description("Number of active ticks to aggregate before printing a debug performance scoreboard.")
        .defaultValue(100)
        .min(1)
        .sliderRange(1, 400)
        .visible(() -> debug.get() && debugProfiling.get())
        .build()
    );
    private final Setting<String> selectedMetaShapes = sgMeta.add(new StringSetting.Builder().name("selected-meta-shapes-internal").description("Internal persisted list of selected MiniHUD meta shapes.").defaultValue("").visible(() -> false).build());
    private final Setting<Integer> minHeight = sgMeta.add(new IntSetting.Builder().name("min-height").description("Minimum Y coordinate allowed for queued actions.").defaultValue(-64).sliderRange(-128, 384).build());
    private final Setting<Integer> maxHeight = sgMeta.add(new IntSetting.Builder().name("max-height").description("Maximum Y coordinate allowed for queued actions.").defaultValue(320).sliderRange(-128, 384).build());

    private final Setting<ListMode> listMode = sgWhitelist.add(new EnumSetting.Builder<ListMode>().name("list-mode").description("How to interpret the block list.").defaultValue(ListMode.Blacklist).build());
    private final Setting<List<Block>> blacklist = sgWhitelist.add(new BlockListSetting.Builder().name("blacklist").description("Blocks that should never be mined.").visible(() -> listMode.get() == ListMode.Blacklist).build());
    private final Setting<List<Block>> whitelist = sgWhitelist.add(new BlockListSetting.Builder().name("whitelist").description("Blocks that are allowed to be mined.").visible(() -> listMode.get() == ListMode.Whitelist).build());
    private final Setting<Keybind> selectBlockBind = sgWhitelist.add(new KeybindSetting.Builder().name("select-block-bind").description("Adds targeted block to the active list mode.").defaultValue(Keybind.none()).build());

    private final Setting<Boolean> lineWithBlocks = sgPlacement.add(new BoolSetting.Builder().name("line-with-blocks").description("Fill the orthogonal outer shell of the selected MiniHUD meta region with selected blocks.").defaultValue(false).build());
    private final Setting<List<Block>> lineBlockList = sgPlacement.add(new BlockListSetting.Builder().name("line-blocks").description("Blocks that may be used for shell placement.").visible(lineWithBlocks::get).build());
    private final Setting<Integer> lineMinHeight = sgPlacement.add(new IntSetting.Builder().name("line-min-height").description("Minimum Y coordinate allowed for shell placement actions.").defaultValue(-64).sliderRange(-128, 384).visible(lineWithBlocks::get).build());
    private final Setting<Integer> lineMaxHeight = sgPlacement.add(new IntSetting.Builder().name("line-max-height").description("Maximum Y coordinate allowed for shell placement actions.").defaultValue(320).sliderRange(-128, 384).visible(lineWithBlocks::get).build());
    private final Setting<Boolean> liquidFiller = sgPlacement.add(new BoolSetting.Builder().name("liquid-filler").description("Replace liquids with selected blocks.").defaultValue(false).build());
    private final Setting<List<Block>> liquidFillBlocks = sgPlacement.add(new BlockListSetting.Builder().name("liquid-fill-blocks").description("Blocks that may be used for liquid filling.").visible(liquidFiller::get).build());
    private final Setting<Double> placeRange = sgPlacement.add(new DoubleSetting.Builder().name("place-range").description("Maximum range for shell or liquid fill placement.").defaultValue(4).min(0).sliderMax(8).visible(() -> lineWithBlocks.get() || liquidFiller.get()).build());
    private final Setting<Boolean> airPlaceShell = sgPlacement.add(new BoolSetting.Builder().name("air-place-shell").description("Allow shell placement without a supporting face.").defaultValue(true).visible(lineWithBlocks::get).build());

    private final Setting<Boolean> swing = sgRender.add(new BoolSetting.Builder().name("swing").description("Render a swing client-side.").defaultValue(true).build());
    private final Setting<Boolean> renderQueued = sgRender.add(new BoolSetting.Builder().name("render-queued").description("Render the next queued crawl action.").defaultValue(true).build());
    private final Setting<ShapeMode> renderShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How queued action boxes are rendered.").defaultValue(ShapeMode.Both).visible(renderQueued::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").description("Side color for queued action rendering.").defaultValue(new SettingColor(255, 0, 0, 60)).visible(renderQueued::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("Line color for queued action rendering.").defaultValue(new SettingColor(255, 0, 0, 255)).visible(renderQueued::get).build());

    private final NukerMapCache mapCache = new NukerMapCache();
    private final NukerRuntime runtime = new NukerRuntime();
    private final NukerProfiler profiler = new NukerProfiler(LOG);

    // All allocated once, reused every tick.
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private final BlockPos.Mutable metaNeighborPos = new BlockPos.Mutable();
    private final BlockPos.Mutable executePos = new BlockPos.Mutable();
    private final CandidatePolicy.Inputs inputs = new CandidatePolicy.Inputs();
    private final ScanContext scanContext = new ScanContext();

    private final MiniHudSelectionState selectionState = new MiniHudSelectionState();
    private int metaDebugBudget;

    // Component-wise cache for the meta-debug summary so we only format on actual change.
    private int lastMetaSelected = -1;
    private boolean lastMetaHasSelected;
    private boolean lastMetaUseLimit;
    private boolean lastMetaInvert;
    private int lastMetaRegionsSignature = -1;

    private static final List<Block> QUICK_BLACKLIST = Arrays.asList(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.SHULKER_BOX,
        Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX, Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
        Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX, Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
        Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX, Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
        Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX, Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.CRAFTER, Blocks.BREWING_STAND, Blocks.BEACON, Blocks.SPAWNER, Blocks.BUDDING_AMETHYST,
        Blocks.REINFORCED_DEEPSLATE, Blocks.END_PORTAL_FRAME, Blocks.DRAGON_EGG,
        Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK, Blocks.NETHERITE_BLOCK,
        Blocks.ANCIENT_DEBRIS, Blocks.RAW_IRON_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.RAW_COPPER_BLOCK
    );

    public OptimizedNuker() {
        super(Categories.World, "optimized-nuker", "Map-driven nuker with crawl, completion, and full-scan scanners.");
    }

    @Override
    public void onActivate() {
        runtime.workSet.ensureQueueCapacities(maxActionsPerTick.get());
        resetRuntimeState();
        // HUD persisted whatever it was showing while the module was off; clear it now
        // so we don't mix new ticks with stale historical numbers.
        profiler.onModuleReactivate();
        metaDebugBudget = META_DEBUG_BUDGET_PER_TICK;

        if (mc.player == null || mc.world == null) return;

        reloadMetaShapeDraftFromSetting();
        refreshMap(true);
        // Initial context build: no rebase, just capture the position and request
        // the completion probe to find the initial frontier.
        buildContext(/*forceRebase*/ false);
        runtime.requestCompletionProbe(mapCache.candidateCount());
    }

    @Override
    public void onDeactivate() {
        resetRuntimeState();
        // HUD intentionally left alone: it shows the last captured state until re-activate.
    }

    private void resetRuntimeState() {
        runtime.reset();
        scanContext.clear();
        invalidateMetaSummary();
    }

    private void invalidateMetaSummary() {
        lastMetaSelected = -1;
        lastMetaRegionsSignature = -1;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (!Utils.canUpdate()) return theme.label("You need to be in a world.");

        WTable table = theme.table();
        initWidget(theme, table);
        return table;
    }

    private void initWidget(GuiTheme theme, WTable table) {
        table.clear();

        table.add(theme.label("MiniHUD meta shapes")).expandX();
        table.row();

        List<MiniHudRegionApi.ShapeHandle> shapes = loadSortedMetaShapes();
        selectionState.ensureDraftLoaded(selectedMetaShapes.get(), shapes);
        selectionState.normalizeDraft(shapes);

        WButton reloadShapes = table.add(theme.button("Reload Shapes From MiniHUD")).widget();
        reloadShapes.action = () -> {
            MiniHudRegionApi.invalidateCache();
            initWidget(theme, table);
        };

        WButton applySelection = table.add(theme.button("Use Draft Selection")).widget();
        applySelection.action = () -> applyMetaShapeDraft(theme, table);

        boolean allSelected = selectionState.hasAllSelectable(shapes);
        WButton toggleAll = table.add(theme.button(allSelected ? "Deselect All" : "Select All Supported")).widget();
        toggleAll.action = () -> {
            if (selectionState.hasAllSelectable(shapes)) selectionState.clearVisible(shapes);
            else selectionState.setAllSelectable(shapes, true);
            initWidget(theme, table);
        };
        table.row();

        if (shapes.isEmpty()) {
            String error = MiniHudRegionApi.getLastError();
            table.add(theme.label(error == null ? "No MiniHUD shapes were found." : "MiniHUD region API error: " + error)).expandX();
            table.row();
        } else {
            for (MiniHudRegionApi.ShapeHandle snapshot : shapes) {
                WCheckbox checkbox = table.add(theme.checkbox(selectionState.isSelected(snapshot))).widget();
                checkbox.action = () -> selectionState.setSelected(snapshot, checkbox.checked);

                String suffix = snapshot.supported ? (snapshot.enabled ? "" : " (disabled)") : " (unsupported: " + snapshot.typeId + ")";
                table.add(theme.label(snapshot.displayName + suffix)).expandX();
                table.row();
            }
        }

        table.add(theme.label("Block list utilities")).expandX();
        table.row();

        WButton quickBlacklist = table.add(theme.button("Add Quick Blacklist Defaults")).widget();
        quickBlacklist.action = this::applyQuickBlacklist;
        table.row();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        boolean profileTick = debug.get() && debugProfiling.get();
        if (profileTick) profiler.beginTick(debugTickWindow.get(), runtime.workSet.queuedActionCount(), runtime.frontierIndex, runtime.crawlAnchorIndex);

        if (runtime.timer > 0) {
            runtime.timer--;
            if (profileTick) profiler.cancelTick();
            return;
        }

        runtime.workSet.ensureQueueCapacities(maxActionsPerTick.get());
        metaDebugBudget = META_DEBUG_BUDGET_PER_TICK;

        long obsStart = profiler.beginPhase();
        observe();
        profiler.endPhase(NukerProfiler.Phase.OBSERVE, obsStart);

        // Influencing scanners run once at the top of the tick, BEFORE crawl,
        // because they can move the frontier and re-arm crawl. Crawl is the
        // only action producer; everything else exists to keep crawl on target.
        long scanStart = profiler.beginPhase();
        runInfluencingScanners();
        profiler.endPhase(NukerProfiler.Phase.SCAN, scanStart);

        int actionGoal = Math.max(1, maxActionsPerTick.get());
        profiler.recordActionGoal(actionGoal);
        int totalSuccesses = runActionLoop(actionGoal);

        finishActionTick(totalSuccesses, actionGoal);
        runtime.workSet.clearQueues();
        if (profileTick) endProfileTick(totalSuccesses);
    }

    /**
     * Run the scanners that can affect crawl's frontier. Completion probe and full
     * scan are mutually exclusive: completion runs when scheduled (activation, map
     * rebuild, meta change) and resets full scan state so full starts fresh after.
     * Full scan runs every other tick as a constant hum.
     */
    private void runInfluencingScanners() {
        if (runtime.completionProbePending) {
            long t = profiler.beginScanner();
            try {
                NukerScanners.runCompletionProbeIfPending(this, inputs, mapCache, runtime, scanPos, metaNeighborPos);
            } finally {
                profiler.endScanner(NukerProfiler.Scanner.COMPLETION, t);
            }
            // Completion probe resets full scan state so full starts fresh next tick.
            runtime.globalFrontierProbeCursor = 0;
            runtime.fullScanLowestSeen = -1;
            return;
        }

        int fullScanBudget = Math.max(0, probeGlobalFrontierScansPerTick.get())
            + Math.max(0, maxGlobalFrontierProbeScansPerTick.get() - probeGlobalFrontierScansPerTick.get());
        if (fullScanBudget > 0) {
            long t = profiler.beginScanner();
            try {
                NukerScanners.runFullScan(this, inputs, mapCache, runtime, scanPos, metaNeighborPos, fullScanBudget);
            } finally {
                profiler.endScanner(NukerProfiler.Scanner.FULL, t);
            }
        }
    }

    /**
     * Crawl + modify loop. Crawl produces actions, modify consumes them. Exits
     * when the action goal is met, when crawl yields (both cursors disabled),
     * or when crawl produces no actions in a pass.
     */
    private int runActionLoop(int actionGoal) {
        int totalSuccesses = 0;
        int maxIterations = actionGoal + 4;

        for (int iter = 0; iter < maxIterations && totalSuccesses < actionGoal; iter++) {
            long crawlStart = profiler.beginScanner();
            NukerScanners.runCrawl(this, inputs, mapCache, runtime, scanPos, metaNeighborPos,
                CRAWL_MAX_SCANS_PER_CALL);
            profiler.endScanner(NukerProfiler.Scanner.CRAWL, crawlStart);

            if (!runtime.workSet.hasQueuedActions()) break;

            long modStart = profiler.beginPhase();
            totalSuccesses += runModify(actionGoal - totalSuccesses);
            profiler.endPhase(NukerProfiler.Phase.MODIFY, modStart);
        }

        return totalSuccesses;
    }

    /**
     * Observe phase. Detects context invalidation (block-border crossing or
     * forced rebuild from map cache change) and dispatches the rebase. Within
     * the same context, this method is a near-no-op.
     */
    private void observe() {
        // Map rebuild is a context invalidator independent of player movement.
        if (refreshMap(false)) {
            buildContext(/*forceRebase*/ false);
            return;
        }

        int blockX = mc.player.getBlockX();
        int blockY = mc.player.getBlockY();
        int blockZ = mc.player.getBlockZ();
        if (runtime.hasCrossedBlockBorder(blockX, blockY, blockZ)) {
            buildContext(/*forceRebase*/ true);
        }
    }

    /**
     * Build (or rebuild) the context: refresh scan context, repopulate {@link Inputs},
     * capture context position, optionally run the rebase. Called on activation, on
     * map cache change, and on integer-block-coord change.
     *
     * @param forceRebase true when the trigger was player movement; runs the rebase
     *                    via {@link NukerScanners#runRebase}.
     */
    private void buildContext(boolean forceRebase) {
        refreshScanContext();
        populateInputs();
        logMetaContextIfChanged();

        Vec3d position = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        int blockX = mc.player.getBlockX();
        int blockY = mc.player.getBlockY();
        int blockZ = mc.player.getBlockZ();

        if (forceRebase && mapCache.hasCandidates()) {
            double distanceMoved = position.distanceTo(runtime.contextPosition);
            int movementBlocks = Math.max(1, (int) Math.ceil(distanceMoved));

            NukerScanners.runRebase(this, inputs, mapCache, runtime, scanPos, metaNeighborPos,
                distanceMoved, shape.get(), sortMode.get());

            // Full scan also responds to movement: regress its cursor and clear
            // its memory so it freshly considers the close-side region.
            NukerScanners.regressFullScanForMovement(mapCache, runtime, movementBlocks);
        }

        // Capture the new context. lastActionDistanceAtContextBuild measures the
        // distance from THIS context's position to the current last-action world
        // block, used at the NEXT context build to detect moved-toward vs away.
        double lastActionDistance = runtime.frontierIsRealAction
            ? position.distanceTo(new Vec3d(
                runtime.lastActionWorldX + 0.5,
                runtime.lastActionWorldY + 0.5,
                runtime.lastActionWorldZ + 0.5))
            : Double.NaN;

        runtime.captureContext(position, blockX, blockY, blockZ, lastActionDistance);
        runtime.workSet.clearQueues();
    }

    /**
     * Drain the queue, executing one action per pop. On success, capture the
     * world position so the next context build can do a coordinate-transform
     * rebase if the player moves toward the same block.
     */
    private int runModify(int remainingGoal) {
        int successes = 0;
        int lastIndex = -1;
        int lastWorldX = 0;
        int lastWorldY = 0;
        int lastWorldZ = 0;

        while (successes < remainingGoal && runtime.workSet.popNextCrawlActionInto(runtime.workSet.actionView)) {
            profiler.recordActionAttempt();
            NukerActionQueue.View action = runtime.workSet.actionView;

            boolean ok = action.type == CandidatePolicy.BREAK
                ? performBreak(action)
                : performPlace(action);

            if (ok) {
                profiler.recordActionDelivered();
                lastIndex = action.mapIndex;
                lastWorldX = action.pos.getX();
                lastWorldY = action.pos.getY();
                lastWorldZ = action.pos.getZ();
                successes++;
            } else {
                profiler.recordActionFailed();
            }
        }

        if (lastIndex >= 0) {
            runtime.onActionSuccess(mapCache.clampToCandidateIndex(lastIndex), lastWorldX, lastWorldY, lastWorldZ);
        }
        return successes;
    }

    /**
     * HUD render. Called from {@link dev.firstmage.optimizednuker.OptimizedNukerAddon}'s
     * always-on Render2DEvent subscriber - NOT from the module's own event bus, so the
     * HUD persists with its last-captured data while the module is deactivated, per the
     * "hang how it got left, reset on re-enable" requirement.
     */
    public void renderProfilerHud(Render2DEvent event) {
        if (!debug.get() || !debugProfiling.get() || !debugProfileHudOutput.get()) return;
        if (mc.textRenderer == null) return;

        List<String> lines = profiler.liveScoreboardLines();
        if (lines.isEmpty()) return;

        int x = Math.max(0, debugProfileHudX.get());
        int y = Math.max(0, debugProfileHudY.get());
        int lineHeight = mc.textRenderer.fontHeight + 2;
        int width = 0;
        for (int i = 0; i < lines.size(); i++) {
            int w = mc.textRenderer.getWidth(lines.get(i));
            if (w > width) width = w;
        }

        event.drawContext.fill(x - 4, y - 4, x + width + 4, y + lineHeight * lines.size() + 2, 0x90000000);
        int textY = y;
        for (int i = 0; i < lines.size(); i++) {
            event.drawContext.drawTextWithShadow(mc.textRenderer, lines.get(i), x, textY, 0xFFFFFFFF);
            textY += lineHeight;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderQueued.get()) return;
        if (runtime.workSet.peekNextCrawlActionInto(runtime.workSet.crawlHeadView)) {
            RenderUtils.renderTickingBlock(runtime.workSet.crawlHeadView.pos,
                sideColor.get(), lineColor.get(), renderShapeMode.get(), 0, 8, true, false);
        }
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press && selectBlockBind.get().matches(event.input)) addTargetedBlockToList();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press && selectBlockBind.get().matches(event.input)) addTargetedBlockToList();
    }

    /**
     * Refresh the candidate map cache. Returns true if the map was rebuilt (caller
     * should rebuild the context). On rebuild, frontier is clamped, queues cleared,
     * crawl invalidated, full scan cursor reset, and the completion probe scheduled
     * to rediscover the initial frontier.
     */
    private boolean refreshMap(boolean force) {
        if (mc.player == null || mc.world == null) return false;

        boolean changed = mapCache.rebuildIfNeeded(force, shape.get(), sortMode.get(), range.get(),
            rangeUp.get(), rangeDown.get(), rangeLeft.get(), rangeRight.get(), rangeForward.get(), rangeBack.get(),
            mc.player.getHorizontalFacing());
        if (!changed) return false;

        runtime.frontierIndex = mapCache.clampToCandidateIndex(runtime.frontierIndex);
        runtime.frontierIsRealAction = false;
        runtime.workSet.clearQueues();
        runtime.invalidateCrawl();
        runtime.globalFrontierProbeCursor = 0;
        runtime.fullScanLowestSeen = -1;
        runtime.requestCompletionProbe(mapCache.candidateCount());
        return true;
    }

    /**
     * Execute a queued break action. Always returns true: the underlying
     * {@code BlockUtils.breakBlock}/packet-mine paths don't report success, so we
     * count the attempt as delivered and let world state drive the next tick. If
     * the block was unbreakable for some reason missed at classify time, the same
     * candidate will simply be re-classified on the next tick.
     */
    private boolean performBreak(NukerActionQueue.View action) {
        executePos.set(action.pos.getX(), action.pos.getY(), action.pos.getZ());
        // The runnable captures executePos; safe because the runnable runs
        // synchronously in this method before executePos is reused elsewhere.
        Runnable run = () -> {
            if (interact.get()) {
                BlockUtils.interact(new BlockHitResult(executePos.toCenterPos(), BlockUtils.getDirection(executePos), executePos, true), Hand.MAIN_HAND, swing.get());
            } else if (packetMine.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, executePos, BlockUtils.getDirection(executePos)));
                if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
                else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, executePos, BlockUtils.getDirection(executePos)));
            } else {
                BlockUtils.breakBlock(executePos, swing.get());
            }
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(executePos), Rotations.getPitch(executePos), run);
        else run.run();
        return true;
    }

    private boolean performPlace(NukerActionQueue.View action) {
        List<Block> allowed = action.type == CandidatePolicy.PLACE_LINE ? lineBlockList.get() : liquidFillBlocks.get();
        FindItemResult item = findPlacementBlock(allowed);
        if (!item.found()) return false;

        executePos.set(action.pos.getX(), action.pos.getY(), action.pos.getZ());
        boolean airPlace = action.type == CandidatePolicy.PLACE_LINE && airPlaceShell.get();
        Runnable run = () -> BlockUtils.place(executePos, item, rotate.get(), 50, swing.get(), airPlace);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(executePos), Rotations.getPitch(executePos), run);
        else run.run();
        return true;
    }

    private FindItemResult findPlacementBlock(List<Block> allowed) {
        return InvUtils.findInHotbar(stack -> stack.getItem() instanceof BlockItem blockItem && allowed.contains(blockItem.getBlock()));
    }

    /** Repopulates the {@link #scanContext} singleton in place. */
    private void refreshScanContext() {
        Set<String> selected = getNormalizedSelectedMetaShapeTokens();
        boolean needsMetaShapes = !selected.isEmpty() && (limitToMetaRegion.get() || lineWithBlocks.get());
        MiniHudRegionApi.Snapshot regions = needsMetaShapes ? MiniHudRegionApi.snapshot(selected) : MiniHudRegionApi.Snapshot.EMPTY;

        scanContext.regions = regions;
        scanContext.hasSelectedShapes = regions.hasRegions();
        scanContext.useMetaRegionLimit = limitToMetaRegion.get() && scanContext.hasSelectedShapes;
        scanContext.linePlacementAvailable = lineWithBlocks.get() && scanContext.hasSelectedShapes
            && !lineBlockList.get().isEmpty() && findPlacementBlock(lineBlockList.get()).found();
        scanContext.liquidPlacementAvailable = liquidFiller.get()
            && !liquidFillBlocks.get().isEmpty() && findPlacementBlock(liquidFillBlocks.get()).found();
    }

    /** Repopulates the singleton {@link #inputs} record in place. No allocation. */
    private void populateInputs() {
        inputs.populate(
            mc.world,
            mc.player,
            shape.get(),
            mode.get(),
            listMode.get(),
            range.get(),
            rangeUp.get(),
            rangeDown.get(),
            rangeLeft.get(),
            rangeRight.get(),
            rangeForward.get(),
            rangeBack.get(),
            scanContext.useMetaRegionLimit,
            invertMetaRegion.get(),
            scanContext.hasSelectedShapes,
            scanContext.regions,
            minHeight.get(),
            maxHeight.get(),
            lineWithBlocks.get(),
            lineBlockList.get(),
            lineMinHeight.get(),
            lineMaxHeight.get(),
            scanContext.linePlacementAvailable,
            liquidFiller.get(),
            liquidFillBlocks.get(),
            scanContext.liquidPlacementAvailable,
            placeRange.get(),
            airPlaceShell.get(),
            whitelist.get(),
            blacklist.get(),
            suitableTools.get(),
            interact.get(),
            enableRaytracing.get(),
            wallsRange.get()
        );
    }

    private void addTargetedBlockToList() {
        if (mc.currentScreen != null || mc.crosshairTarget == null) return;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
        Block block = mc.world.getBlockState(pos).getBlock();
        List<Block> list = listMode.get() == ListMode.Whitelist ? whitelist.get() : blacklist.get();

        if (list.contains(block)) {
            list.remove(block);
            info("Removed " + Names.get(block) + " from " + listMode.get().name());
        } else {
            list.add(block);
            info("Added " + Names.get(block) + " to " + listMode.get().name());
        }
    }

    private List<MiniHudRegionApi.ShapeHandle> loadSortedMetaShapes() {
        List<MiniHudRegionApi.ShapeHandle> shapes = new ArrayList<>(MiniHudRegionApi.listShapes());
        shapes.sort(Comparator.comparing(shape -> shape.displayName, String.CASE_INSENSITIVE_ORDER));
        return shapes;
    }

    private void reloadMetaShapeDraftFromSetting() {
        selectionState.reloadDraftFromStored(selectedMetaShapes.get(), loadSortedMetaShapes());
    }

    private void applyMetaShapeDraft(GuiTheme theme, WTable table) {
        selectedMetaShapes.set(selectionState.draftSelectionString());
        MiniHudRegionApi.invalidateCache();
        invalidateMetaSummary();

        if (Utils.canUpdate()) {
            // Meta selection change invalidates the context: shape predicates change.
            // Trigger a context rebuild without a movement rebase, then re-run the
            // completion probe to rediscover the initial frontier.
            buildContext(/*forceRebase*/ false);
            runtime.frontierIsRealAction = false;
            runtime.invalidateCrawl();
            runtime.globalFrontierProbeCursor = 0;
            runtime.fullScanLowestSeen = -1;
            runtime.requestCompletionProbe(mapCache.candidateCount());
        }

        initWidget(theme, table);
        info("Applied MiniHUD shape selection.");
    }

    private void applyQuickBlacklist() {
        LinkedHashSet<Block> merged = new LinkedHashSet<>(blacklist.get());
        merged.addAll(QUICK_BLACKLIST);
        blacklist.set(new ArrayList<>(merged));
        info("Added quick blacklist blocks.");
    }

    private Set<String> getSelectedMetaShapeTokens() {
        return selectionState.storedSelectionTokens(selectedMetaShapes.get());
    }

    /**
     * Returns the normalized stored token set, without writing it back to the setting
     * on every tick. The original code did a {@code selectedMetaShapes.set(...)} write
     * here when the canonical form differed from the stored form; that has been moved
     * to {@link #applyMetaShapeDraft} so the per-tick path is read-only.
     */
    private Set<String> getNormalizedSelectedMetaShapeTokens() {
        Set<String> stored = getSelectedMetaShapeTokens();
        if (stored.isEmpty()) return stored;

        List<MiniHudRegionApi.ShapeHandle> shapes = loadSortedMetaShapes();
        if (shapes.isEmpty()) return stored;

        return selectionState.normalizedStoredSelection(selectedMetaShapes.get(), shapes);
    }

    private void logMetaContextIfChanged() {
        if (!debug.get()) return;

        // Component-wise compare avoids building the summary string every tick.
        int selected = getSelectedMetaShapeTokens().size();
        int regionsSig = MiniHudRegionApi.rawShapeSignature();
        boolean hasSelected = scanContext.hasSelectedShapes;
        boolean useLimit = scanContext.useMetaRegionLimit;
        boolean invert = invertMetaRegion.get();

        if (selected == lastMetaSelected
            && hasSelected == lastMetaHasSelected
            && useLimit == lastMetaUseLimit
            && invert == lastMetaInvert
            && regionsSig == lastMetaRegionsSignature) return;

        lastMetaSelected = selected;
        lastMetaHasSelected = hasSelected;
        lastMetaUseLimit = useLimit;
        lastMetaInvert = invert;
        lastMetaRegionsSignature = regionsSig;

        debugMeta("CONTEXT selected={} hasSelected={} useLimit={} invert={} summary={}",
            selected, hasSelected, useLimit, invert, scanContext.regions.debugSummary());
    }

    private void debugMeta(String message, Object... args) {
        if (!debug.get() || metaDebugBudget <= 0) return;
        metaDebugBudget--;
        LOG.info("[meta-debug] " + message, args);
    }

    private void finishActionTick(int successes, int actionGoal) {
        if (debug.get() && successes >= actionGoal && mc.player != null && mc.world != null) {
            mc.world.playSound(null, mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 1.0f, 1.8f);
        }
        if (successes > 0) runtime.timer = delay.get();
    }

    private void endProfileTick(int successes) {
        profiler.endTick(successes, runtime.workSet.queuedActionCount(), runtime.frontierIndex,
            runtime.crawlAnchorIndex, debugProfileLogOutput.get(), debugProfileChatOutput.get(),
            this::info);
    }

    /** Called from {@link NukerScanners} after each classify. Profiler-only; one indexed increment when active. */
    void recordScan(NukerProfiler.Scanner scanner, byte classifyResult) {
        profiler.recordScan(scanner, classifyResult != CandidatePolicy.NONE);
    }

    /** Bridge for {@link NukerScanners} to wrap inner-dispatched probes (e.g. completion) in scanner timing. */
    long beginScannerTimer() {
        return profiler.beginScanner();
    }

    void endScannerTimer(NukerProfiler.Scanner scanner, long startNs) {
        profiler.endScanner(scanner, startNs);
    }

    public enum ListMode { Whitelist, Blacklist }
    public enum Mode { All, Flatten, Smash }
    public enum SortMode { Closest, Furthest, TopDown }
    public enum Shape { Cube, UniformCube, Sphere }

    /**
     * Singleton-mutable per-tick context. Owned by {@link OptimizedNuker}; refilled
     * once per tick from the active settings inside {@link #refreshScanContext}.
     */
    private static final class ScanContext {
        MiniHudRegionApi.Snapshot regions = MiniHudRegionApi.Snapshot.EMPTY;
        boolean hasSelectedShapes;
        boolean useMetaRegionLimit;
        boolean linePlacementAvailable;
        boolean liquidPlacementAvailable;

        void clear() {
            regions = MiniHudRegionApi.Snapshot.EMPTY;
            hasSelectedShapes = false;
            useMetaRegionLimit = false;
            linePlacementAvailable = false;
            liquidPlacementAvailable = false;
        }
    }
}
