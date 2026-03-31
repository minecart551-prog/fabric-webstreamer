package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * BigTV Block Entity - Fixed dimensions for a large TV (16:9 aspect ratio)
 * The video is automatically fitted to these dimensions without player customization
 */
public class BigTVBlockEntity extends DisplayBlockEntity {

    public BigTVBlockEntity(BlockPos pos, BlockState state) {
        super(WebStreamerMod.BIG_TV_BLOCK_ENTITY, pos, state);
        // Set fixed BigTV screen dimensions based on model geometry.
        // Width: 2.625 blocks, Height: 1.75 blocks.
        setSize(2.625f, 1.75f);

        Direction attachment = state.get(DisplayBlock.PROP_ATTACHMENT);
        if (attachment == Direction.UP) {
            setOffset(0.0, 0.565, 1.386);
        } else if (attachment == Direction.DOWN) {
            setOffset(0.0, -0.563, -1.387);
        } else {
            setOffset(0.0, 0.44, 1.39);
        }
    }
}
