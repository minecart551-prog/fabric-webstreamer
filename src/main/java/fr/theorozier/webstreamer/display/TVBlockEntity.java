package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * TV Block Entity - Fixed dimensions for a standard TV (16:9 aspect ratio)
 * The video is automatically fitted to these dimensions without player customization
 */
public class TVBlockEntity extends DisplayBlockEntity {

    public TVBlockEntity(BlockPos pos, BlockState state) {
        super(WebStreamerMod.TV_BLOCK_ENTITY, pos, state);
        setSize(2.0f, 1.18f);

        Direction attachment = state.get(DisplayBlock.PROP_ATTACHMENT);
        if (attachment == Direction.UP) {
            setOffset(0.0, 0.35, 1.28);
        } else if (attachment == Direction.DOWN) {
            setOffset(0.0, 0.407, -1.7);
        } else {
            setOffset(0.0, 0.03, 0.9);
        }
    }
}
