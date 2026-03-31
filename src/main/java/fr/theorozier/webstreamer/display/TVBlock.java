package fr.theorozier.webstreamer.display;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.block.ShapeContext;
import org.jetbrains.annotations.Nullable;

/**
 * TV Block - A fixed-size display block for YouTube videos
 * Videos are automatically fitted to the TV's aspect ratio without player customization
 */
public class TVBlock extends DisplayBlock {

    public TVBlock() {
        super();
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new TVBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!(world.getBlockEntity(pos) instanceof DisplayBlockEntity displayEntity)) {
            return super.getOutlineShape(state, world, pos, context);
        }

        return createDynamicOutlineShape(state, displayEntity);
    }
}
