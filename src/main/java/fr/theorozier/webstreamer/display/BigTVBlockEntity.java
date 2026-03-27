package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * BigTV Block Entity - Fixed dimensions for a large TV (16:9 aspect ratio)
 * The video is automatically fitted to these dimensions without player customization
 */
public class BigTVBlockEntity extends DisplayBlockEntity {

    public BigTVBlockEntity(BlockPos pos, BlockState state) {
        super(WebStreamerMod.BIG_TV_BLOCK_ENTITY, pos, state);
        // Set fixed BigTV screen dimensions based on model geometry.
        // Width: 2.75 blocks, Height: 1.875 blocks.
        setSize(2.75f, 1.875f);
        setOffset(0.0, 0.5625, 0.0);
    }
}
