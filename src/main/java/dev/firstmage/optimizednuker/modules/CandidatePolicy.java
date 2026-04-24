package dev.firstmage.optimizednuker.modules;

import meteordevelopment.meteorclient.utils.world.BlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;

final class CandidatePolicy {
    static final byte NONE = -1;
    static final byte BREAK = 0;
    static final byte PLACE_LINE = 1;
    static final byte PLACE_LIQUID = 2;

    private CandidatePolicy() {}

    static byte classify(BlockPos pos, SphereMapStore.MapPoint point, Inputs in, BlockPos.Mutable reusableNeighbor) {
        if (!passesHeightRules(pos, in)) return NONE;
        if (!passesCurrentShapeRules(pos, point, in)) return NONE;
        if (!passesMetaRules(pos, in)) return NONE;

        BlockState state = in.world.getBlockState(pos);
        boolean shell = isMetaShell(pos, in, reusableNeighbor);

        if (in.lineWithBlocks && shell) {
            if (state.isAir()) {
                if (passesLineHeightRules(pos, in)
                    && in.linePlacementAvailable
                    && isWithinPlaceRange(pos, in)
                    && (in.airPlaceShell || BlockUtils.getPlaceSide(pos) != null)
                    && !isOutOfRange(pos, in)) {
                    return PLACE_LINE;
                }
            } else if (in.lineBlockList.contains(state.getBlock())) {
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
        if (liquidFillBlockProtected(state.getBlock(), in)) return NONE;
        if (!passesListRules(state, in)) return NONE;
        if (in.mode == OptimizedNuker.Mode.Smash && state.getHardness(in.world, pos) != 0) return NONE;
        if (in.suitableTools && !in.interact && !in.player.getMainHandStack().isSuitableFor(state)) return NONE;
        if (!in.interact && !BlockUtils.canBreak(pos, state)) return NONE;
        if (isOutOfRange(pos, in)) return NONE;
        return BREAK;
    }

    static boolean revalidate(BlockPos pos, SphereMapStore.MapPoint point, byte expectedType, Inputs in, BlockPos.Mutable reusableNeighbor) {
        return classify(pos, point, in, reusableNeighbor) == expectedType;
    }

    private static boolean passesCurrentShapeRules(BlockPos pos, SphereMapStore.MapPoint point, Inputs in) {
        switch (in.shape) {
            case Sphere -> {
                if (point.distance > in.range) return false;
            }
            case UniformCube -> {
                int r = (int) Math.round(in.range);
                if (OptimizedNuker.chebyshevDist(in.player.getBlockX(), in.player.getBlockY(), in.player.getBlockZ(), pos.getX(), pos.getY(), pos.getZ()) > r) {
                    return false;
                }
            }
            case Cube -> {
                int rx = pos.getX() - in.player.getBlockX();
                int ry = pos.getY() - in.player.getBlockY();
                int rz = pos.getZ() - in.player.getBlockZ();
                Direction facing = in.player.getHorizontalFacing();

                int leftRight;
                int forwardBack;
                switch (facing) {
                    case SOUTH -> {
                        leftRight = -rx;
                        forwardBack = rz;
                    }
                    case WEST -> {
                        leftRight = rz;
                        forwardBack = -rx;
                    }
                    case NORTH -> {
                        leftRight = rx;
                        forwardBack = -rz;
                    }
                    case EAST -> {
                        leftRight = -rz;
                        forwardBack = rx;
                    }
                    default -> {
                        leftRight = rx;
                        forwardBack = rz;
                    }
                }

                if (ry > in.rangeUp || ry < -in.rangeDown) return false;
                if (leftRight > in.rangeLeft || leftRight < -in.rangeRight) return false;
                if (forwardBack > in.rangeForward || forwardBack < -in.rangeBack) return false;
            }
        }

        return in.mode != OptimizedNuker.Mode.Flatten || pos.getY() + 0.5 >= in.player.getY();
    }

    private static boolean passesMetaRules(BlockPos pos, Inputs in) {
        if (!in.useMetaRegionLimit) return true;
        boolean inside = in.regions.contains(pos);
        return in.invertMetaRegion ? !inside : inside;
    }

    private static boolean isMetaShell(BlockPos pos, Inputs in, BlockPos.Mutable reusableNeighbor) {
        if (!in.hasSelectedShapes) return false;
        return in.invertMetaRegion ? in.regions.isExteriorBoundary(pos, reusableNeighbor) : in.regions.isShell(pos, reusableNeighbor);
    }

    private static boolean passesHeightRules(BlockPos pos, Inputs in) {
        return pos.getY() >= in.minHeight && pos.getY() <= in.maxHeight;
    }

    private static boolean passesLineHeightRules(BlockPos pos, Inputs in) {
        return pos.getY() >= in.lineMinHeight && pos.getY() <= in.lineMaxHeight;
    }

    private static boolean passesListRules(BlockState state, Inputs in) {
        if (in.listMode == OptimizedNuker.ListMode.Whitelist) return in.whitelist.contains(state.getBlock());
        return !in.blacklist.contains(state.getBlock());
    }

    private static boolean liquidFillBlockProtected(Block block, Inputs in) {
        return in.liquidFiller && in.liquidFillBlocks.contains(block);
    }

    private static boolean isWithinPlaceRange(BlockPos pos, Inputs in) {
        Vec3d center = pos.toCenterPos();
        Vec3d eye = in.player.getEyePos();
        return eye.squaredDistanceTo(center) <= in.placeRange * in.placeRange;
    }

    private static boolean isOutOfRange(BlockPos blockPos, Inputs in) {
        if (!in.enableRaytracing) return false;
        Vec3d pos = blockPos.toCenterPos();
        RaycastContext context = new RaycastContext(in.player.getEyePos(), pos, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, in.player);
        BlockHitResult result = in.world.raycast(context);
        if (result == null || !result.getBlockPos().equals(blockPos)) {
            Vec3d eye = in.player.getEyePos();
            return eye.squaredDistanceTo(pos) > in.wallsRange * in.wallsRange;
        }
        return false;
    }

    static final class Inputs {
        final World world;
        final ClientPlayerEntity player;
        final OptimizedNuker.Shape shape;
        final OptimizedNuker.Mode mode;
        final OptimizedNuker.ListMode listMode;
        final double range;
        final int rangeUp;
        final int rangeDown;
        final int rangeLeft;
        final int rangeRight;
        final int rangeForward;
        final int rangeBack;
        final boolean useMetaRegionLimit;
        final boolean invertMetaRegion;
        final boolean hasSelectedShapes;
        final MiniHudRegionApi.Snapshot regions;
        final int minHeight;
        final int maxHeight;
        final boolean lineWithBlocks;
        final List<Block> lineBlockList;
        final int lineMinHeight;
        final int lineMaxHeight;
        final boolean linePlacementAvailable;
        final boolean liquidFiller;
        final List<Block> liquidFillBlocks;
        final boolean liquidPlacementAvailable;
        final double placeRange;
        final boolean airPlaceShell;
        final List<Block> whitelist;
        final List<Block> blacklist;
        final boolean suitableTools;
        final boolean interact;
        final boolean enableRaytracing;
        final double wallsRange;

        Inputs(
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
            this.range = range;
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
            this.lineBlockList = lineBlockList;
            this.lineMinHeight = lineMinHeight;
            this.lineMaxHeight = lineMaxHeight;
            this.linePlacementAvailable = linePlacementAvailable;
            this.liquidFiller = liquidFiller;
            this.liquidFillBlocks = liquidFillBlocks;
            this.liquidPlacementAvailable = liquidPlacementAvailable;
            this.placeRange = placeRange;
            this.airPlaceShell = airPlaceShell;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
            this.suitableTools = suitableTools;
            this.interact = interact;
            this.enableRaytracing = enableRaytracing;
            this.wallsRange = wallsRange;
        }
    }
}
