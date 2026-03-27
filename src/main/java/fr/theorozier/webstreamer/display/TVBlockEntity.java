package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

/**
 * TV Block Entity - Fixed dimensions for a standard TV (16:9 aspect ratio)
 * The video is automatically fitted to these dimensions without player customization
 */
public class TVBlockEntity extends DisplayBlockEntity {

    public TVBlockEntity(BlockPos pos, BlockState state) {
        super(WebStreamerMod.TV_BLOCK_ENTITY, pos, state);
        // Set fixed TV screen dimensions based on model geometry (approximate to the TV front panel)
        // Width: 2.125 blocks, Height: 1.3125 blocks (matches display element from model frame)
        setSize(2.125f, 1.3125f);
        // Align the rendered quad to match model vertical placement.
        setOffset(0.0, 0.34375, 0.0);
    }
}
