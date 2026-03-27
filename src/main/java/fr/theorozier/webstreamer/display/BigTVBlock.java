package fr.theorozier.webstreamer.display;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import org.jetbrains.annotations.Nullable;

/**
 * BigTV Block - A large fixed-size display block for YouTube videos
 * Videos are automatically fitted to the big TV's aspect ratio without player customization
 */
public class BigTVBlock extends DisplayBlock {

    public BigTVBlock() {
        super();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new BigTVBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        Direction attachment = state.get(PROP_ATTACHMENT);
        Direction facing = state.get(PROP_FACING);
        if (attachment == Direction.UP || attachment == Direction.DOWN) {
            // For BigTVs on floor/ceiling, use wall shape to make outline thin in depth
            return switch (facing) {
                case NORTH -> SHAPE_NORTH;
                case SOUTH -> SHAPE_SOUTH;
                case EAST -> SHAPE_EAST;
                case WEST -> SHAPE_WEST;
                default -> SHAPE_FLOOR;
            };
        }
        return super.getOutlineShape(state, world, pos, context);
    }
}
