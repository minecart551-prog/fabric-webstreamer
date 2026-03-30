package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.BigTVBlock;
import fr.theorozier.webstreamer.display.DisplayBlock;
import fr.theorozier.webstreamer.display.TVBlock;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.mixin.WorldRendererInvoker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

import org.joml.AxisAngle4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.net.URI;
import java.util.stream.StreamSupport;

@Environment(EnvType.CLIENT)
public class DisplayBlockEntityRenderer implements BlockEntityRenderer<DisplayBlockEntity> {

    private static final Text NO_LAYER_AVAILABLE_TEXT = Text.translatable("gui.webstreamer.display.status.noLayerAvailable");
    private static final Text UNKNOWN_FORMAT_TEXT = Text.translatable("gui.webstreamer.display.status.unknownFormat");
    private static final Text NO_URL_TEXT = Text.translatable("gui.webstreamer.display.status.noUrl");

    private static final Quaternionf ROTATE_90 = new Quaternionf(new AxisAngle4d(Math.PI / 2.0, 0.0, 1.0, 0.0));
    private static final Quaternionf ROTATE_180 = new Quaternionf(new AxisAngle4d(Math.PI, 0.0, 1.0, 0.0));
    private static final Quaternionf ROTATE_270 = new Quaternionf(new AxisAngle4d(Math.PI / 2.0 * 3.0, 0.0, 1.0, 0.0));

    private static final Quaternionf ROTATE_FLOOR = new Quaternionf(new AxisAngle4d(Math.PI / 2.0, 1.0, 0.0, 0.0));
    private static final Quaternionf ROTATE_CEILING = new Quaternionf(new AxisAngle4d(Math.PI / 2.0 * 3.0, 1.0, 0.0, 0.0));

    private final GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;
    private final TextRenderer textRenderer;

    @SuppressWarnings("unused")
    public DisplayBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
        this.textRenderer = ctx.getTextRenderer();
    }

    private static boolean isTVBlock(net.minecraft.block.Block block) {
        return block instanceof TVBlock || block instanceof BigTVBlock;
    }

    @Override
    public void render(DisplayBlockEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {


        // If the block entity is removed, world is null, or the block is no longer a DisplayBlock, skip rendering and layer logic
        if (entity.getWorld() == null || entity.isRemoved() || !(entity.getWorld().getBlockState(entity.getPos()).getBlock() instanceof DisplayBlock)) {
            return;
        }

        boolean isTV = isTVBlock(entity.getCachedState().getBlock());

        // Get the render data from the block entity, this is just an extension to the
        // block entity, so it will always be present and is lazily instantiated and will
        // last as long as the block entity.
        DisplayRenderData renderData = (DisplayRenderData) entity.getRenderData();
        if (renderData == null) {
            // Block entity is removed or invalid, skip rendering
            return;
        }

        DisplayLayerManager layerManager = WebStreamerClientMod.DISPLAY_LAYERS;
        Text statusText = null;

        // Asynchronously get the URI of this display, if the URI is not yet available,
        // null is just returned.
        URI uri = renderData.getUri(layerManager.getResources().getExecutor());
        if (uri == null) {
            // No valid URI, skip rendering
            return;
        }

        // If the player is currently holding a display item, we draw the outline shape
        // of the display block, we also display as status text the source status.
        PlayerEntity player = MinecraftClient.getInstance().player;
        if (player != null) {

            boolean hasDisplayEquipped = StreamSupport.stream(player.getItemsEquipped().spliterator(), false)
                    .map(ItemStack::getItem)
                    .anyMatch(WebStreamerMod.DISPLAY_ITEM::equals);

            if (hasDisplayEquipped) {

                VoxelShape displayShape = entity.getCachedState().getOutlineShape(entity.getWorld(), entity.getPos());
                if (displayShape != null) {
                    matrices.push();
                    WorldRendererInvoker.drawCuboidShapeOutline(matrices, vertexConsumers.getBuffer(RenderLayer.getLines()), displayShape, 0, 0, 0, 235 / 255f, 168 / 255f, 0f, 1f);
                    matrices.pop();
                }

                statusText = Text.literal(entity.getSource().getStatus());

            }

        }

        Direction attachment = entity.getCachedState().get(DisplayBlock.PROP_ATTACHMENT);
        Direction facing = entity.getCachedState().get(DisplayBlock.PROP_FACING);

        matrices.push();

        // Apply block position offset first
        switch (attachment) {
            case NORTH, SOUTH, EAST, WEST -> matrices.translate(0.5f - facing.getOffsetX(), 0.5f, 0.5f - facing.getOffsetZ());
            case UP, DOWN -> matrices.translate(0.5f, 0.5f, 0.5f);
        }

        // Apply user-defined offset based on attachment orientation
        double offsetX = entity.getOffsetX();
        double offsetY = entity.getOffsetY();
        double offsetZ = entity.getOffsetZ();

        switch (attachment) {
            case NORTH, SOUTH, EAST, WEST -> {
                // For wall-mounted displays, apply offset in world coordinates
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, offsetY, -offsetZ);
                    case SOUTH -> matrices.translate(-offsetX, offsetY, offsetZ);
                    case EAST -> matrices.translate(offsetZ, offsetY, offsetX);
                    case WEST -> matrices.translate(-offsetZ, offsetY, -offsetX);
                }
            }
            case UP -> {
                // For floor-mounted displays, X offset is horizontal, Y offset is in facing direction, Z is vertical
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, offsetZ, -offsetY);
                    case SOUTH -> matrices.translate(-offsetX, offsetZ, offsetY);
                    case EAST -> matrices.translate(offsetY, offsetZ, offsetX);
                    case WEST -> matrices.translate(-offsetY, offsetZ, -offsetX);
                }
            }
            case DOWN -> {
                // For ceiling-mounted displays, X offset is horizontal, Y offset is in facing direction, Z is vertical
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, -offsetZ, offsetY);
                    case SOUTH -> matrices.translate(-offsetX, -offsetZ, -offsetY);
                    case EAST -> matrices.translate(-offsetY, -offsetZ, offsetX);
                    case WEST -> matrices.translate(offsetY, -offsetZ, -offsetX);
                }
            }
        }

        switch (facing) {
            case NORTH -> {}
            case SOUTH -> matrices.multiply(ROTATE_180);
            case EAST -> matrices.multiply(ROTATE_270);
            case WEST -> matrices.multiply(ROTATE_90);
            default -> throw new IllegalArgumentException();
        }

        switch (attachment) {
            case NORTH, SOUTH, EAST, WEST -> {}
            case UP, DOWN -> {
                // For TV and BigTV blocks, floor/ceiling states should still render vertically.
                // Keep same face orientation as normal wall placements and avoid horizontal quad.
            }
        }

        // Apply conditional rotation for floor/ceiling placement
        if (attachment == Direction.UP) {
            if (!isTVBlock(entity.getCachedState().getBlock())) {
                matrices.multiply(ROTATE_FLOOR);
            }
        } else if (attachment == Direction.DOWN) {
            if (!isTVBlock(entity.getCachedState().getBlock())) {
                matrices.multiply(ROTATE_CEILING);
            }
        }

        if (uri != null) {
            try {
                BlockPos pos = entity.getPos();
                float audioDistance = entity.getAudioDistance();
                int playerDist = pos.getManhattanDistance(this.gameRenderer.getCamera().getBlockPos());
                boolean inRange = !(audioDistance > 0f && playerDist > audioDistance);

                DisplayLayer layer = layerManager.getLayer(new DisplayLayerNode.Key(uri, entity));
                if (layer == null) {
                    matrices.pop();
                    return;
                }
                if (layer instanceof DisplayLayerSimple simpleLayer) {
                    simpleLayer.setInRange(inRange);
                }

                if (!inRange) {
                    matrices.pop();
                    return;
                }

                if (layer.isLost()) {
                    entity.resetSourceUri();
                    matrices.pop();
                    return;
                }

                if (!(layer instanceof DisplayLayerSimple simpleLayer2) || !simpleLayer2.isReady()) {
                    matrices.pop();
                    return;
                }

                VertexConsumer buffer = vertexConsumers.getBuffer(layer.getRenderLayer());

                float audioVolume = entity.getAudioVolume();
                layer.pushAudioSource(pos, playerDist, audioDistance, audioVolume);

                // Width/Height end coords
                float w = entity.getWidth();
                float h = entity.getHeight();

                // For TV and BigTV, force fixed panel geometry and quad depth
                if (isTV) {
                    w = entity.getWidth();
                    h = entity.getHeight();
                }

                // Width/Height start coords
                float hw = w / 2f;
                float hh = h / 2f;

                // TV models use a shallower depth to align with the front face.
                float quadZ = isTV ? 0.01f : -0.55f;

                Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
                buffer.vertex(positionMatrix,  hw, -hh, quadZ).texture(0, 1).next();
                buffer.vertex(positionMatrix, -hw, -hh, quadZ).texture(1, 1).next();
                buffer.vertex(positionMatrix, -hw,  hh, quadZ).texture(1, 0).next();
                buffer.vertex(positionMatrix,  hw,  hh, quadZ).texture(0, 0).next();

            } catch (DisplayLayerNode.OutOfLayerException e) {
                statusText = NO_LAYER_AVAILABLE_TEXT;
            } catch (DisplayLayerNode.UnknownFormatException e) {
                statusText = UNKNOWN_FORMAT_TEXT;
            }
        } else {
            statusText = NO_URL_TEXT;
        }

        if (statusText != null) {

            matrices.push();

            final float scaleFactor = 128f / Math.min(entity.getWidth(), entity.getHeight());
            final float scale = 1f / scaleFactor;
            final float halfWidth = this.textRenderer.getWidth(statusText) / scaleFactor / 2f;
            final float halfHeight = this.textRenderer.fontHeight / scaleFactor / 2f;

            matrices.translate(halfWidth, halfHeight, -0.65f);
            matrices.scale(-scale, -scale, 1f);
            this.textRenderer.draw(statusText, 0f, 0f, 0x00ffffff, false, matrices.peek().getPositionMatrix(), vertexConsumers, TextLayerType.NORMAL, 0xBB222222, light);
            matrices.pop();

        }

        matrices.pop();

    }

}