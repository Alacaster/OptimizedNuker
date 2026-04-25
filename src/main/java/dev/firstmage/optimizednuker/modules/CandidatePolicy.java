package dev.firstmage.optimizednuker.modules;

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless block-classification logic operating on a singleton-mutable {@link Inputs}.
 * Inputs is repopulated once per tick during the observe phase; CandidatePolicy.classify
 * is the only allocator-free hot path used by every scanner.
 */
final class CandidatePolicy {
    static final byte NONE = -1;
    static final byte BREAK = 0;
    static final byte PLACE_LINE = 1;
    static final byte PLACE_LIQUID = 2;

    private CandidatePolicy() {}

    static byte classify(BlockPos pos, SphereMapStore.MapPoint point, Inputs in, BlockPos.Mutable reusableNeighbor) {
        if (pos.getY() < in.minHeight || pos.getY() > in.maxHeight) return NONE;
        if (!passesShapeRules(pos, in)) return NONE;
        if (in.useMetaRegionLimit) {
            boolean inside = in.regions.contains(pos);
            if (in.invertMetaRegion ? inside : !inside) return NONE;
        }

        BlockState state = in.world.getBlockState(pos);
        // Shell test only when needed; isMetaShell does up to 7 region.contains() calls.
        boolean shell = in.lineWithBlocks && in.hasSelectedShapes && (in.invertMetaRegion
            ? in.regions.isExteriorBoundary(pos, reusableNeighbor)
            : in.regions.isBoundary(pos, reusableNeighbor));

        if (in.lineWithBlocks && shell) {
            if (state.isAir()) {
                if (pos.getY() >= in.lineMinHeight
                    && pos.getY() <= in.lineMaxHeight
                    && in.linePlacementAvailable
                    && isWithinPlaceRange(pos, in)
                    && (in.airPlaceShell || BlockUtils.getPlaceSide(pos) != null)
                    && !isOutOfRange(pos, in)) {
                    return PLACE_LINE;
                }
            } else if (in.lineBlockSet.contains(state.getBlock())) {
                return NONE;
            }
        }

        if (in.liquidFiller && !state.getFluidState().isEmpty()) {
            if (in.liquidPlacementAvailable
                && isWithinPlaceRange(pos, in)
                && BlockUtils.getPlaceSide(pos) != null
                && !isOutOfRange(pos, in)) {
                return PLACE_LIQUID;
            }
        }

        if (state.isAir()) return NONE;
        if (in.liquidFiller && in.liquidFillSet.contains(state.getBlock())) return NONE;
        if (in.listMode == OptimizedNuker.ListMode.Whitelist
            ? !in.whitelistSet.contains(state.getBlock())
            : in.blacklistSet.contains(state.getBlock())) return NONE;
        if (in.mode == OptimizedNuker.Mode.Smash && state.getHardness(in.world, pos) != 0) return NONE;
        if (in.suitableTools && !in.interact && !in.player.getMainHandStack().isSuitableFor(state)) return NONE;
        if (!in.interact && !BlockUtils.canBreak(pos, state)) return NONE;
        if (isOutOfRange(pos, in)) return NONE;
        return BREAK;
    }

    private static boolean passesShapeRules(BlockPos pos, Inputs in) {
        switch (in.shape) {
            case Sphere -> {
                // Distance is already enforced by the scan window; no per-candidate check needed.
            }
            case UniformCube -> {
                int r = in.uniformCubeRadius;
                int dx = Math.abs(pos.getX() - in.playerBlockX);
                int dy = Math.abs(pos.getY() - in.playerBlockY);
                int dz = Math.abs(pos.getZ() - in.playerBlockZ);
                if (Math.max(Math.max(dx, dy), dz) > r) return false;
            }
            case Cube -> {
                int rx = pos.getX() - in.playerBlockX;
                int ry = pos.getY() - in.playerBlockY;
                int rz = pos.getZ() - in.playerBlockZ;

                int leftRight;
                int forwardBack;
                switch (in.facing) {
                    case SOUTH -> { leftRight = -rx; forwardBack = rz; }
                    case WEST -> { leftRight = rz; forwardBack = -rx; }
                    case NORTH -> { leftRight = rx; forwardBack = -rz; }
                    case EAST -> { leftRight = -rz; forwardBack = rx; }
                    default -> { leftRight = rx; forwardBack = rz; }
                }

                if (ry > in.rangeUp || ry < -in.rangeDown) return false;
                if (leftRight > in.rangeLeft || leftRight < -in.rangeRight) return false;
                if (forwardBack > in.rangeForward || forwardBack < -in.rangeBack) return false;
            }
        }

        return in.mode != OptimizedNuker.Mode.Flatten || pos.getY() + 0.5 >= in.playerY;
    }

    private static boolean isWithinPlaceRange(BlockPos pos, Inputs in) {
        double dx = (pos.getX() + 0.5) - in.eyeX;
        double dy = (pos.getY() + 0.5) - in.eyeY;
        double dz = (pos.getZ() + 0.5) - in.eyeZ;
        return dx * dx + dy * dy + dz * dz <= in.placeRangeSq;
    }

    private static boolean isOutOfRange(BlockPos blockPos, Inputs in) {
        if (!in.enableRaytracing) return false;
        double cx = blockPos.getX() + 0.5;
        double cy = blockPos.getY() + 0.5;
        double cz = blockPos.getZ() + 0.5;
        // Cheap distance gate: if the target is already within walls range, skip raycast entirely.
        double edx = cx - in.eyeX;
        double edy = cy - in.eyeY;
        double edz = cz - in.eyeZ;
        double distSq = edx * edx + edy * edy + edz * edz;
        if (distSq <= in.wallsRangeSq) return false;
        // Beyond walls range: must raycast to confirm direct line of sight.
        // Vec3d and RaycastContext allocate; only happens for opt-in raytracing past walls range.
        Vec3d eye = new Vec3d(in.eyeX, in.eyeY, in.eyeZ);
        Vec3d target = new Vec3d(cx, cy, cz);
        RaycastContext context = new RaycastContext(eye, target,
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, in.player);
        BlockHitResult result = in.world.raycast(context);
        return result == null || !result.getBlockPos().equals(blockPos);
    }

    /**
     * Singleton-mutable per-tick parameters. Owned by {@link OptimizedNuker} and
     * repopulated once per tick during the observe phase. Block-list settings are
     * mirrored into HashSets for O(1) contains() in the classify hot path.
     */
    static final class Inputs {
        World world;
        ClientPlayerEntity player;
        OptimizedNuker.Shape shape;
        OptimizedNuker.Mode mode;
        OptimizedNuker.ListMode listMode;
        net.minecraft.util.math.Direction facing;

        // Player position cached to avoid per-candidate getX/getBlockX calls.
        int playerBlockX;
        int playerBlockY;
        int playerBlockZ;
        double playerY;
        double eyeX;
        double eyeY;
        double eyeZ;

        double range;
        int uniformCubeRadius;
        int rangeUp;
        int rangeDown;
        int rangeLeft;
        int rangeRight;
        int rangeForward;
        int rangeBack;

        boolean useMetaRegionLimit;
        boolean invertMetaRegion;
        boolean hasSelectedShapes;
        MiniHudRegionApi.Snapshot regions = MiniHudRegionApi.Snapshot.EMPTY;

        int minHeight;
        int maxHeight;

        boolean lineWithBlocks;
        int lineMinHeight;
        int lineMaxHeight;
        boolean linePlacementAvailable;
        boolean liquidFiller;
        boolean liquidPlacementAvailable;
        double placeRangeSq;
        boolean airPlaceShell;

        // HashSets for O(1) hot-path contains() (block instances are singletons).
        final HashSet<Block> whitelistSet = new HashSet<>();
        final HashSet<Block> blacklistSet = new HashSet<>();
        final HashSet<Block> lineBlockSet = new HashSet<>();
        final HashSet<Block> liquidFillSet = new HashSet<>();

        boolean suitableTools;
        boolean interact;
        boolean enableRaytracing;
        double wallsRangeSq;

        void populate(
            World world,
            ClientPlayerEntity player,
            OptimizedNuker.Shape shape,
            OptimizedNuker.Mode mode,
            OptimizedNuker.ListMode listMode,
            double range,
            int rangeUp,
            int rangeDown,
            int rangeLeft,
            int rangeRight,
            int rangeForward,
            int rangeBack,
            boolean useMetaRegionLimit,
            boolean invertMetaRegion,
            boolean hasSelectedShapes,
            MiniHudRegionApi.Snapshot regions,
            int minHeight,
            int maxHeight,
            boolean lineWithBlocks,
            List<Block> lineBlockList,
            int lineMinHeight,
            int lineMaxHeight,
            boolean linePlacementAvailable,
            boolean liquidFiller,
            List<Block> liquidFillBlocks,
            boolean liquidPlacementAvailable,
            double placeRange,
            boolean airPlaceShell,
            List<Block> whitelist,
            List<Block> blacklist,
            boolean suitableTools,
            boolean interact,
            boolean enableRaytracing,
            double wallsRange
        ) {
            this.world = world;
            this.player = player;
            this.shape = shape;
            this.mode = mode;
            this.listMode = listMode;
            this.facing = player.getHorizontalFacing();
            this.playerBlockX = player.getBlockX();
            this.playerBlockY = player.getBlockY();
            this.playerBlockZ = player.getBlockZ();
            this.playerY = player.getY();
            Vec3d eye = player.getEyePos();
            this.eyeX = eye.x;
            this.eyeY = eye.y;
            this.eyeZ = eye.z;
            this.range = range;
            this.uniformCubeRadius = (int) Math.round(range);
            this.rangeUp = rangeUp;
            this.rangeDown = rangeDown;
            this.rangeLeft = rangeLeft;
            this.rangeRight = rangeRight;
            this.rangeForward = rangeForward;
            this.rangeBack = rangeBack;
            this.useMetaRegionLimit = useMetaRegionLimit;
            this.invertMetaRegion = invertMetaRegion;
            this.hasSelectedShapes = hasSelectedShapes;
            this.regions = regions;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.lineWithBlocks = lineWithBlocks;
            this.lineMinHeight = lineMinHeight;
            this.lineMaxHeight = lineMaxHeight;
            this.linePlacementAvailable = linePlacementAvailable;
            this.liquidFiller = liquidFiller;
            this.liquidPlacementAvailable = liquidPlacementAvailable;
            this.placeRangeSq = placeRange * placeRange;
            this.airPlaceShell = airPlaceShell;
            this.suitableTools = suitableTools;
            this.interact = interact;
            this.enableRaytracing = enableRaytracing;
            this.wallsRangeSq = wallsRange * wallsRange;

            refillSet(whitelistSet, whitelist);
            refillSet(blacklistSet, blacklist);
            refillSet(lineBlockSet, lineBlockList);
            refillSet(liquidFillSet, liquidFillBlocks);
        }

        private static void refillSet(Set<Block> set, List<Block> source) {
            set.clear();
            if (source != null) set.addAll(source);
        }
    }
}
