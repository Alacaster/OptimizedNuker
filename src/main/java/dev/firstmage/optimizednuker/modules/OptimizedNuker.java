package dev.firstmage.optimizednuker.modules;

import meteordevelopment.meteorclient.events.entity.player.BlockBreakingCooldownEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OptimizedNuker extends Module {
    private static final Logger LOG = LoggerFactory.getLogger("OptimizedNuker");
    private static final int META_DEBUG_BUDGET_PER_TICK = 32;

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
    private final Setting<Integer> fullScanScansPerTick = sgGeneral.add(new IntSetting.Builder().name("full-scan-scans-per-tick").description("Normal number of full-scan checks to do per tick.").defaultValue(64).min(1).build());
    private final Setting<Integer> maxFullScanScansPerTick = sgGeneral.add(new IntSetting.Builder().name("max-full-scan-scans-per-tick").description("Maximum number of full-scan checks to do in a tick right after a full-scan reset.").defaultValue(512).min(1).build());
    private final Setting<Integer> maxFullQueueSize = sgGeneral.add(new IntSetting.Builder().name("max-full-queue-size").description("Pause full scanning when the full queue already has this many actions buffered.").defaultValue(512).min(1).build());

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
    private final Setting<Boolean> debugMetaRegion = sgMeta.add(new BoolSetting.Builder().name("debug-meta-region").description("Log MiniHUD meta-region gating decisions to the server log.").defaultValue(false).build());
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
    private final Setting<Boolean> renderQueued = sgRender.add(new BoolSetting.Builder().name("render-queued").description("Render the front action from the crawl, local and full queues.").defaultValue(true).build());
    private final Setting<ShapeMode> renderShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").description("How queued action boxes are rendered.").defaultValue(ShapeMode.Both).visible(renderQueued::get).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").description("Side color for queued action rendering.").defaultValue(new SettingColor(255, 0, 0, 60)).visible(renderQueued::get).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").description("Line color for queued action rendering.").defaultValue(new SettingColor(255, 0, 0, 255)).visible(renderQueued::get).build());

    private final NukerMapCache mapCache = new NukerMapCache();
    private final NukerRuntime runtime = new NukerRuntime();
    private final BlockPos.Mutable scanPos = new BlockPos.Mutable();
    private final BlockPos.Mutable metaNeighborPos = new BlockPos.Mutable();
    private ScanContext scanContext = ScanContext.EMPTY;
    private int metaDebugBudget;
    private String lastMetaDebugSummary = "";

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
        super(Categories.World, "optimized-nuker", "Map-driven nuker with crawl, local and full scanners.");
    }

    @Override
    public void onActivate() {
        runtime.workSet.ensureQueueCapacities(maxActionsPerTick.get(), maxFullQueueSize.get());
        metaDebugBudget = META_DEBUG_BUDGET_PER_TICK;
        refreshMap(true);
        runtime.positionAtLastMovement = mc.player != null ? new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()) : Vec3d.ZERO;
        runtime.workSet.clearAll();
        runtime.lastTickBestActionWorld = mc.player != null ? mc.player.getBlockPos() : BlockPos.ORIGIN;
        runtime.lastTickBestActionDistance = 0;
        scanContext = buildScanContext();
        runtime.timer = 0;
        runtime.lastLocalCursorExclusive = mapCache.goalIndexExclusive();
        runtime.lastCrawlCursorExclusive = mapCache.goalIndexExclusive();
        runtime.restartFullScan(mapCache.goalIndexExclusive(), 0);
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

        WButton refresh = table.add(theme.button("Refresh Meta Shapes / Reload Cache")).widget();
        refresh.action = () -> {
            MiniHudRegionApi.invalidateCache();
            initWidget(theme, table);
        };

        WButton quickBlacklist = table.add(theme.button("Quick Blacklist")).widget();
        quickBlacklist.action = this::applyQuickBlacklist;
        table.row();

        Set<String> selected = getNormalizedSelectedMetaShapeTokens();
        List<MiniHudRegionApi.ShapeHandle> shapes = new ArrayList<>(MiniHudRegionApi.listShapes());
        shapes.sort(Comparator.comparing(shape -> shape.displayName, String.CASE_INSENSITIVE_ORDER));

        if (shapes.isEmpty()) {
            String error = MiniHudRegionApi.getLastError();
            table.add(theme.label(error == null ? "No MiniHUD shapes were found." : "MiniHUD region API error: " + error)).expandX();
            table.row();
            return;
        }

        for (MiniHudRegionApi.ShapeHandle snapshot : shapes) {
            WCheckbox checkbox = table.add(theme.checkbox(isSelectedMetaShape(snapshot, selected))).widget();
            checkbox.action = () -> updateSelectedMetaShape(snapshot, checkbox.checked);

            String suffix = snapshot.supported ? (snapshot.enabled ? "" : " (disabled)") : " (unsupported: " + snapshot.typeId + ")";
            table.add(theme.label(snapshot.displayName + suffix)).expandX();
            table.row();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onBlockBreakingCooldown(BlockBreakingCooldownEvent event) {
        event.cooldown = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        runtime.workSet.ensureQueueCapacities(maxActionsPerTick.get(), maxFullQueueSize.get());
        metaDebugBudget = META_DEBUG_BUDGET_PER_TICK;
        refreshMap(false);
        scanContext = buildScanContext();
        logMetaContextIfChanged();
        CandidatePolicy.Inputs policy = buildCandidateInputs();

        Vec3d now = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        int movementBlocks = runtime.computeMovementBlocks(now);
        if (movementBlocks > 0) {
            handleMovement(now, movementBlocks);
            localScan(movementBlocks, policy);
        }

        if (runtime.timer > 0) {
            runtime.timer--;
        } else {
            int successes = modifyBlocks(policy);
            if (successes >= maxActionsPerTick.get()) return;
        }

        if (!runtime.workSet.crawl.isFull()) crawlScan(policy);
        if (!runtime.workSet.full.isFull()) fullScan(policy);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!renderQueued.get()) return;

        boolean haveLocal = runtime.workSet.local.readFirstInto(runtime.workSet.localHeadView);
        boolean haveCrawl = runtime.workSet.crawl.readFirstInto(runtime.workSet.crawlHeadView);
        boolean haveFull = runtime.workSet.full.readFirstInto(runtime.workSet.fullHeadView);

        if (haveLocal) renderActionHead(runtime.workSet.localHeadView.pos);
        if (haveCrawl && (!haveLocal || runtime.workSet.crawlHeadView.posLong != runtime.workSet.localHeadView.posLong)) renderActionHead(runtime.workSet.crawlHeadView.pos);
        if (haveFull
            && (!haveLocal || runtime.workSet.fullHeadView.posLong != runtime.workSet.localHeadView.posLong)
            && (!haveCrawl || runtime.workSet.fullHeadView.posLong != runtime.workSet.crawlHeadView.posLong)) {
            renderActionHead(runtime.workSet.fullHeadView.pos);
        }
    }

    private void renderActionHead(BlockPos pos) {
        RenderUtils.renderTickingBlock(pos, sideColor.get(), lineColor.get(), renderShapeMode.get(), 0, 8, true, false);
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (event.action == KeyAction.Press && selectBlockBind.get().matches(event.input)) addTargetedBlockToList();
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (event.action == KeyAction.Press && selectBlockBind.get().matches(event.input)) addTargetedBlockToList();
    }

    private void refreshMap(boolean force) {
        int[] cubeExtents = currentCubeExtents();
        boolean changed = mapCache.rebuildIfNeeded(force, shape.get(), sortMode.get(), range.get(), cubeExtents, mc.player.getHorizontalFacing());
        if (!changed) return;

        runtime.clampAnchor(mapCache.goalIndexExclusive());
        runtime.workSet.clearAll();
        runtime.lastLocalCursorExclusive = mapCache.goalIndexExclusive();
        runtime.lastCrawlCursorExclusive = mapCache.goalIndexExclusive();
        runtime.restartFullScan(mapCache.goalIndexExclusive(), 0);
    }

    private int[] currentCubeExtents() {
        return new int[]{rangeUp.get(), rangeDown.get(), rangeLeft.get(), rangeRight.get(), rangeForward.get(), rangeBack.get()};
    }

    private void handleMovement(Vec3d now, int movementBlocks) {
        runtime.positionAtLastMovement = now;
        runtime.workSet.clearAll();
        int rewind = countRewindIndicesForAnchor(runtime.fullScanCursor, movementBlocks);
        runtime.restartFullScan(mapCache.goalIndexExclusive(), Math.max(0, runtime.fullScanCursor - rewind));
    }

    private void localScan(int movementBlocks, CandidatePolicy.Inputs policy) {
        runtime.workSet.local.clear();
        if (mapCache.activeMap().length == 0 || mapCache.goalIndexExclusive() <= 0) {
            runtime.lastLocalCursorExclusive = 0;
            return;
        }

        int index = computeRetractedAnchorIndex(movementBlocks);
        int goal = mapCache.goalIndexExclusive();
        while (index < goal && !runtime.workSet.local.isFull()) {
            scanQueueIndex(index, runtime.workSet.local, policy);
            index++;
        }
        runtime.lastLocalCursorExclusive = clamp(index, 0, goal);
    }

    private void crawlScan(CandidatePolicy.Inputs policy) {
        runtime.workSet.crawl.clear();
        if (mapCache.activeMap().length == 0 || mapCache.goalIndexExclusive() <= 0) {
            runtime.lastCrawlCursorExclusive = 0;
            return;
        }

        int index = clamp(runtime.lastTickBestActionMapIndex, 0, Math.max(mapCache.goalIndexExclusive() - 1, 0));
        int goal = mapCache.goalIndexExclusive();
        while (index < goal && !runtime.workSet.crawl.isFull()) {
            scanQueueIndex(index, runtime.workSet.crawl, policy);
            index++;
        }
        runtime.lastCrawlCursorExclusive = clamp(index, 0, goal);
    }

    private int computeLocalSearchBudget(int movementBlocks) {
        if (mapCache.activeMap().length == 0 || mapCache.goalIndexExclusive() <= 0) return 0;
        int rewind = countRewindIndicesForAnchor(runtime.lastTickBestActionMapIndex, movementBlocks);
        int budget = Math.max(maxActionsPerTick.get(), rewind + maxActionsPerTick.get());
        return clamp(budget, 1, mapCache.goalIndexExclusive());
    }

    private int computeRetractedAnchorIndex(int movementBlocks) {
        int anchor = clamp(runtime.lastTickBestActionMapIndex, 0, Math.max(mapCache.goalIndexExclusive() - 1, 0));
        int rewind = countRewindIndicesForAnchor(anchor, movementBlocks);
        int retracted = anchor - rewind;
        int budget = computeLocalSearchBudget(movementBlocks);
        int minAllowed = Math.max(0, anchor - budget);
        return clamp(retracted, minAllowed, anchor);
    }

    private int countRewindIndicesForAnchor(int anchorIndex, int movementBlocks) {
        if (mapCache.activeMap().length == 0 || mapCache.goalIndexExclusive() <= 0) return 0;

        int blocks = Math.max(1, movementBlocks);
        if (shape.get() == Shape.Sphere) {
            int clampedIndex = clamp(anchorIndex, 0, Math.max(mapCache.goalIndexExclusive() - 1, 0));
            int anchorShell = clamp((int) Math.floor(mapCache.activeMap()[clampedIndex].distance), 0, SphereMapStore.MAX_RADIUS);
            int startShell = clamp(anchorShell - blocks, 0, SphereMapStore.MAX_RADIUS);
            return Math.max(1, SphereMapStore.voxelsBetweenIntegerDistances(startShell, anchorShell));
        }

        return Math.max(1, blocks * 64);
    }

    private int computeFullWrapExclusive() {
        int wrapExclusive = Math.max(runtime.lastLocalCursorExclusive, runtime.lastCrawlCursorExclusive);
        if (wrapExclusive <= 0) wrapExclusive = mapCache.goalIndexExclusive();
        return clamp(wrapExclusive, 0, mapCache.goalIndexExclusive());
    }

    private void fullScan(CandidatePolicy.Inputs policy) {
        if (mapCache.activeMap().length == 0 || mapCache.goalIndexExclusive() <= 0) return;
        if (runtime.workSet.full.isFull()) return;

        int wrapExclusive = computeFullWrapExclusive();
        if (wrapExclusive <= 0) return;

        int checksRemaining = runtime.fullScanJustReset ? Math.max(fullScanScansPerTick.get(), maxFullScanScansPerTick.get()) : fullScanScansPerTick.get();
        runtime.fullScanJustReset = false;

        while (checksRemaining > 0 && !runtime.workSet.full.isFull()) {
            if (runtime.fullScanCursor >= wrapExclusive) runtime.fullScanCursor = 0;
            scanQueueIndex(runtime.fullScanCursor, runtime.workSet.full, policy);
            runtime.fullScanCursor++;
            checksRemaining--;
        }
    }

    private int modifyBlocks(CandidatePolicy.Inputs policy) {
        int successes = 0;
        int bestMapIndex = Integer.MAX_VALUE;
        long bestPosLong = 0L;
        float bestDistance = 0F;
        boolean haveBest = false;

        while (successes < maxActionsPerTick.get() && runtime.workSet.popNextByPriorityInto(runtime.workSet.actionView)) {
            if (!tryExecuteQueuedAction(runtime.workSet.actionView, policy)) continue;

            if (!haveBest || runtime.workSet.actionView.mapIndex < bestMapIndex) {
                bestMapIndex = runtime.workSet.actionView.mapIndex;
                bestPosLong = runtime.workSet.actionView.posLong;
                bestDistance = runtime.workSet.actionView.distance;
                haveBest = true;
            }

            successes++;
        }

        if (haveBest) {
            runtime.lastTickBestActionWorld = BlockPos.fromLong(bestPosLong);
            runtime.lastTickBestActionMapIndex = bestMapIndex;
            runtime.lastTickBestActionDistance = bestDistance;
        }
        if (successes > 0) runtime.timer = delay.get();
        return successes;
    }

    private boolean tryExecuteQueuedAction(NukerActionQueue.View view, CandidatePolicy.Inputs policy) {
        SphereMapStore.MapPoint[] activeMap = mapCache.activeMap();
        if (view.mapIndex < 0 || view.mapIndex >= activeMap.length) return false;
        if (debugMetaRegion.get() && scanContext.hasSelectedShapes) {
            boolean inside = scanContext.regions.contains(view.pos);
            boolean shell = scanContext.regions.isShell(view.pos, metaNeighborPos);
            boolean exterior = scanContext.regions.isExteriorBoundary(view.pos, metaNeighborPos);
            debugMeta("EXEC try type={} pos={} idx={} inside={} shell={} exterior={} invert={} useLimit={}",
                actionTypeName(view.type), view.pos.toShortString(), view.mapIndex, inside, shell, exterior, invertMetaRegion.get(), scanContext.useMetaRegionLimit);
        }
        if (!CandidatePolicy.revalidate(view.pos, activeMap[view.mapIndex], view.type, policy, metaNeighborPos)) {
            debugMeta("EXEC rejected type={} pos={} idx={} reason=revalidate_failed", actionTypeName(view.type), view.pos.toShortString(), view.mapIndex);
            return false;
        }
        return view.type == CandidatePolicy.BREAK ? performBreak(view) : performPlace(view);
    }

    private boolean performBreak(NukerActionQueue.View action) {
        BlockPos targetPos = BlockPos.fromLong(action.posLong);
        Runnable run = () -> {
            if (interact.get()) {
                BlockUtils.interact(new BlockHitResult(targetPos.toCenterPos(), BlockUtils.getDirection(targetPos), targetPos, true), Hand.MAIN_HAND, swing.get());
            } else if (packetMine.get()) {
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, targetPos, BlockUtils.getDirection(targetPos)));
                if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
                else mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
                mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, targetPos, BlockUtils.getDirection(targetPos)));
            } else {
                BlockUtils.breakBlock(targetPos, swing.get());
            }
        };

        if (rotate.get()) Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), run);
        else run.run();
        return true;
    }

    private boolean performPlace(NukerActionQueue.View action) {
        List<Block> allowed = action.type == CandidatePolicy.PLACE_LINE ? lineBlockList.get() : liquidFillBlocks.get();
        FindItemResult item = findPlacementBlock(allowed);
        if (!item.found()) return false;

        BlockPos targetPos = BlockPos.fromLong(action.posLong);
        boolean airPlace = action.type == CandidatePolicy.PLACE_LINE && airPlaceShell.get();
        Runnable run = () -> BlockUtils.place(targetPos, item, rotate.get(), 50, swing.get(), airPlace);
        if (rotate.get()) Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), run);
        else run.run();
        return true;
    }

    private void scanQueueIndex(int mapIndex, NukerActionQueue queue, CandidatePolicy.Inputs policy) {
        if (queue.isFull()) return;
        if (mapIndex < 0 || mapIndex >= mapCache.activeMap().length) return;

        SphereMapStore.MapPoint point = mapCache.activeMap()[mapIndex];
        scanPos.set(mc.player.getX() + point.dx, mc.player.getY() + point.dy, mc.player.getZ() + point.dz);
        byte type = CandidatePolicy.classify(scanPos, point, policy, metaNeighborPos);
        if (type != CandidatePolicy.NONE) {
            queue.insertSorted(scanPos.asLong(), mapIndex, point.distance, type);
        }
    }

    private FindItemResult findPlacementBlock(List<Block> allowed) {
        return InvUtils.findInHotbar(stack -> stack.getItem() instanceof BlockItem blockItem && allowed.contains(blockItem.getBlock()));
    }

    private ScanContext buildScanContext() {
        Set<String> selected = getNormalizedSelectedMetaShapeTokens();
        boolean needsMetaShapes = !selected.isEmpty() && (limitToMetaRegion.get() || lineWithBlocks.get());
        MiniHudRegionApi.Snapshot regions = needsMetaShapes ? MiniHudRegionApi.snapshot(selected) : MiniHudRegionApi.Snapshot.EMPTY;

        boolean hasSelectedShapes = regions.hasRegions();
        boolean useMetaRegionLimit = limitToMetaRegion.get() && hasSelectedShapes;
        boolean linePlacementAvailable = lineWithBlocks.get() && hasSelectedShapes && !lineBlockList.get().isEmpty() && findPlacementBlock(lineBlockList.get()).found();
        boolean liquidPlacementAvailable = liquidFiller.get() && !liquidFillBlocks.get().isEmpty() && findPlacementBlock(liquidFillBlocks.get()).found();
        return new ScanContext(regions, hasSelectedShapes, useMetaRegionLimit, linePlacementAvailable, liquidPlacementAvailable);
    }

    private CandidatePolicy.Inputs buildCandidateInputs() {
        return new CandidatePolicy.Inputs(
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

    private void applyQuickBlacklist() {
        LinkedHashSet<Block> merged = new LinkedHashSet<>(blacklist.get());
        merged.addAll(QUICK_BLACKLIST);
        blacklist.set(new ArrayList<>(merged));
        info("Added quick blacklist blocks.");
    }

    private Set<String> getSelectedMetaShapeTokens() {
        String raw = selectedMetaShapes.get();
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private Set<String> getNormalizedSelectedMetaShapeTokens() {
        Set<String> stored = getSelectedMetaShapeTokens();
        if (stored.isEmpty()) return stored;

        List<MiniHudRegionApi.ShapeHandle> shapes = MiniHudRegionApi.listShapes();
        if (shapes.isEmpty()) return stored;

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (MiniHudRegionApi.ShapeHandle handle : shapes) {
            if (stored.contains(handle.selectionKey) || stored.contains(handle.displayName)) {
                normalized.add(handle.displayName);
            }
        }

        if (!normalized.equals(stored)) {
            selectedMetaShapes.set(String.join("|", normalized));
        }

        return normalized;
    }

    private boolean isSelectedMetaShape(MiniHudRegionApi.ShapeHandle handle, Set<String> selectedTokens) {
        return selectedTokens.contains(handle.displayName) || selectedTokens.contains(handle.selectionKey);
    }

    private void updateSelectedMetaShape(MiniHudRegionApi.ShapeHandle handle, boolean selected) {
        Set<String> tokens = new LinkedHashSet<>(getNormalizedSelectedMetaShapeTokens());
        tokens.remove(handle.selectionKey);
        tokens.remove(handle.displayName);

        if (selected) tokens.add(handle.displayName);
        selectedMetaShapes.set(String.join("|", tokens));
    }

    private void logMetaContextIfChanged() {
        if (!debugMetaRegion.get()) return;
        String summary = "selected=" + getSelectedMetaShapeTokens().size()
            + " hasSelected=" + scanContext.hasSelectedShapes
            + " useLimit=" + scanContext.useMetaRegionLimit
            + " invert=" + invertMetaRegion.get()
            + " summary=" + scanContext.regions.debugSummary();
        if (!summary.equals(lastMetaDebugSummary)) {
            lastMetaDebugSummary = summary;
            debugMeta("CONTEXT {}", summary);
        }
    }

    private void debugMeta(String message, Object... args) {
        if (!debugMetaRegion.get() || metaDebugBudget <= 0) return;
        metaDebugBudget--;
        LOG.info("[meta-debug] " + message, args);
    }

    private static String actionTypeName(byte type) {
        return switch (type) {
            case CandidatePolicy.BREAK -> "BREAK";
            case CandidatePolicy.PLACE_LINE -> "PLACE_LINE";
            case CandidatePolicy.PLACE_LIQUID -> "PLACE_LIQUID";
            default -> "NONE";
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int chebyshevDist(int x1, int y1, int z1, int x2, int y2, int z2) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int dz = Math.abs(z2 - z1);
        return Math.max(Math.max(dx, dy), dz);
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }

    public enum Mode {
        All,
        Flatten,
        Smash
    }

    public enum SortMode {
        Closest,
        Furthest,
        TopDown
    }

    public enum Shape {
        Cube,
        UniformCube,
        Sphere
    }

    private static final class ScanContext {
        private static final ScanContext EMPTY = new ScanContext(MiniHudRegionApi.Snapshot.EMPTY, false, false, false, false);

        private final MiniHudRegionApi.Snapshot regions;
        private final boolean hasSelectedShapes;
        private final boolean useMetaRegionLimit;
        private final boolean linePlacementAvailable;
        private final boolean liquidPlacementAvailable;

        private ScanContext(MiniHudRegionApi.Snapshot regions, boolean hasSelectedShapes, boolean useMetaRegionLimit, boolean linePlacementAvailable, boolean liquidPlacementAvailable) {
            this.regions = regions;
            this.hasSelectedShapes = hasSelectedShapes;
            this.useMetaRegionLimit = useMetaRegionLimit;
            this.linePlacementAvailable = linePlacementAvailable;
            this.liquidPlacementAvailable = liquidPlacementAvailable;
        }
    }
}
