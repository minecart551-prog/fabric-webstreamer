package fr.theorozier.webstreamer.display.render;

import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.config.WebStreamerClientConfig;
import fr.theorozier.webstreamer.display.BigTVBlock;
import fr.theorozier.webstreamer.display.DisplayBlock;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.display.TVBlock;
import com.mojang.blaze3d.systems.RenderSystem;
import fr.theorozier.webstreamer.WebStreamerClientMod;
import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.config.WebStreamerClientConfig;
import fr.theorozier.webstreamer.display.BigTVBlock;
import fr.theorozier.webstreamer.display.DisplayBlock;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.display.TVBlock;
import fr.theorozier.webstreamer.mixin.WorldRendererInvoker;
import fr.theorozier.webstreamer.util.WebStreamerConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.Camera;
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
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;

import org.joml.AxisAngle4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

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

    /** Number of vertical strips a curved display is tessellated into. */
    private static final int CURVATURE_SEGMENTS = 48;

    private final GameRenderer gameRenderer = MinecraftClient.getInstance().gameRenderer;
    private final TextRenderer textRenderer;

    @SuppressWarnings("unused")
    public DisplayBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {
        this.textRenderer = ctx.getTextRenderer();
    }

    @Override
    public int getRenderDistance() {
        return WebStreamerClientConfig.getDisplayBlockRenderDistance();
    }

    @Override
    public boolean isInRenderDistance(DisplayBlockEntity blockEntity, Vec3d cameraPos) {
        int dist = WebStreamerClientConfig.getDisplayBlockRenderDistance();
        return blockEntity.getPos().getSquaredDistance(cameraPos) < (double) (dist * dist);
    }

    private static boolean isTVBlock(net.minecraft.block.Block block) {
        return block instanceof TVBlock || block instanceof BigTVBlock;
    }

    /**
     * Project the display's bounding box into the viewport and test whether any
     * corner lands on screen (in front of the camera and inside NDC [-1,1]).
     * Replicates the exact camera-space transform the game uses for frustum
     * culling (WorldRenderer.setupFrustum): view = RotX(pitch) * RotY(yaw+180)
     * applied to (world - camera), the projection being the current world
     * projection. Because it matches how the game renders, the gates on this
     * never drop a display that is genuinely on screen.
     */
    private boolean isDisplayOnScreen(DisplayBlockEntity entity) {
        Camera camera = this.gameRenderer.getCamera();
        Vec3d camPos = camera.getPos();

        Matrix4f view = new Matrix4f()
                .rotateX((float) Math.toRadians(camera.getPitch()))
                .rotateY((float) Math.toRadians(camera.getYaw() + 180.0f));
        Matrix4f combined = new Matrix4f(RenderSystem.getProjectionMatrix());
        combined.mul(view);
        combined.translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);

        Box bb = new Box(entity.getPos());
        float hx = (float) ((bb.maxX - bb.minX) / 2d);
        float hy = (float) ((bb.maxY - bb.minY) / 2d);
        float hz = (float) ((bb.maxZ - bb.minZ) / 2d);
        float cx = (float) ((bb.minX + bb.maxX) / 2d);
        float cy = (float) ((bb.minY + bb.maxY) / 2d);
        float cz = (float) ((bb.minZ + bb.maxZ) / 2d);

        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vector4f corner = new Vector4f(cx + sx * hx, cy + sy * hy, cz + sz * hz, 1.0f);
                    corner.mul(combined);
                    // Corners behind the camera are not visible; if none is in
                    // front (w > 0) the whole box is behind us.
                    if (corner.w <= 0.0f) continue;
                    float nx = corner.x / corner.w;
                    float ny = corner.y / corner.w;
                    if (nx >= -1.0f && nx <= 1.0f && ny >= -1.0f && ny <= 1.0f) {
                        return true;
                    }
                }
            }
        }
        return false;
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
        if (attachment == Direction.UP || attachment == Direction.DOWN) {
            if (isTV) {
                matrices.translate(0.5f - facing.getOffsetX(), 0.5f, 0.5f - facing.getOffsetZ());
            } else if (attachment == Direction.UP) {
                matrices.translate(0.5f, -0.5f, 0.5f);
            } else {
                matrices.translate(0.5f, 1.5f, 0.5f);
            }
        } else {
            matrices.translate(0.5f - facing.getOffsetX(), 0.5f, 0.5f - facing.getOffsetZ());
        }

        // Apply user-defined offset based on attachment orientation
        double offsetX = entity.getOffsetX();
        double offsetY = entity.getOffsetY();
        double offsetZ = entity.getOffsetZ();

        switch (attachment) {
            case NORTH, SOUTH, EAST, WEST -> {
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, offsetY, -offsetZ);
                    case SOUTH -> matrices.translate(-offsetX, offsetY, offsetZ);
                    case EAST -> matrices.translate(offsetZ, offsetY, offsetX);
                    case WEST -> matrices.translate(-offsetZ, offsetY, -offsetX);
                }
            }
            case UP -> {
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, offsetY, -offsetZ);
                    case SOUTH -> matrices.translate(-offsetX, offsetY, offsetZ);
                    case EAST -> matrices.translate(offsetZ, offsetY, offsetX);
                    case WEST -> matrices.translate(-offsetZ, offsetY, -offsetX);
                }
            }
            case DOWN -> {
                switch (facing) {
                    case NORTH -> matrices.translate(offsetX, -offsetY, offsetZ);
                    case SOUTH -> matrices.translate(-offsetX, -offsetY, -offsetZ);
                    case EAST -> matrices.translate(-offsetZ, -offsetY, offsetX);
                    case WEST -> matrices.translate(offsetZ, -offsetY, -offsetX);
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

        // Apply user-defined rotation
        float userRotX = entity.getRotationX();
        float userRotY = entity.getRotationY();
        float userRotZ = entity.getRotationZ();
        if (userRotX != 0f || userRotY != 0f || userRotZ != 0f) {
            double radX = Math.toRadians(userRotX);
            double radY = Math.toRadians(userRotY);
            double radZ = Math.toRadians(userRotZ);
            if (userRotX != 0f) {
                matrices.multiply(new Quaternionf(new AxisAngle4d(radX, 1.0, 0.0, 0.0)));
            }
            if (userRotY != 0f) {
                matrices.multiply(new Quaternionf(new AxisAngle4d(radY, 0.0, 1.0, 0.0)));
            }
            if (userRotZ != 0f) {
                matrices.multiply(new Quaternionf(new AxisAngle4d(radZ, 0.0, 0.0, 1.0)));
            }
        }

        if (uri != null) {
            try {
                BlockPos pos = entity.getPos();
                float renderDistance = entity.getRenderDistance();
                float audioDistance = entity.getAudioDistance();
                int playerDist = pos.getManhattanDistance(this.gameRenderer.getCamera().getBlockPos());

                boolean inRange = !(renderDistance > 0f && playerDist > renderDistance);

                if (entity.getWorld() != null && DisplayNetworking.shouldSendPlaybackRangeUpdate(entity.getWorld().getRegistryKey(), pos, inRange, entity.isPlaybackPaused())) {
                    DisplayNetworking.sendPlaybackState(entity, inRange);
                }

                DisplayLayer layer = layerManager.getLayer(new DisplayLayerNode.Key(uri, entity));
                if (layer == null) {
                    matrices.pop();
                    return;
                }
                layer.setPlaybackPaused(entity.isPlaybackPaused());
                if (layer instanceof DisplayLayerSimple simpleLayer) {
                    simpleLayer.setInRange(inRange);
                    simpleLayer.setBrightness(entity.getBrightness());
                    // When visible-upload-only is disabled, always treat the layer
                    // as on screen so uploads are never gated.
                    boolean onScreen = !WebStreamerConfig.isVisibleUploadOnly() || this.isDisplayOnScreen(entity);
                    simpleLayer.markSeen(DisplayLayerManager.getRenderFrame(), inRange && onScreen);
                }

                if (!inRange) {
                    matrices.pop();
                    return;
                }

                if (layer.isLost()) {
                    // Reset the URI to re-fetch on next render cycle.
                    // The YoutubeClient.failedVideoIds cache ensures that permanently
                    // unavailable videos (VIDEO_UNAVAILABLE, NOT_FOUND) are rejected
                    // immediately with no HTTP request, so this is safe to call every frame.
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

                // Width/Height start coords
                float hw = w / 2f;
                float hh = h / 2f;

                // TV models use a shallower depth to align with the front face.
                float quadZ = isTV ? 0.01f : -0.55f;

                Matrix4f positionMatrix = matrices.peek().getPositionMatrix();

                float curvature = entity.getCurvature();
                DisplayBlockEntity.DisplaySide displaySide = entity.getDisplaySide();
                boolean drawFront = displaySide == DisplayBlockEntity.DisplaySide.FRONT || displaySide == DisplayBlockEntity.DisplaySide.BOTH;
                boolean drawBack = displaySide == DisplayBlockEntity.DisplaySide.BACK || displaySide == DisplayBlockEntity.DisplaySide.BOTH;

                if (drawFront) {
                    if (curvature <= 0f) {
                        emitFlatDisplayQuad(buffer, positionMatrix, hw, hh, quadZ, true);
                    } else {
                        drawCurvedDisplay(buffer, positionMatrix, w, hh, quadZ, curvature, true);
                    }
                }
                if (drawBack) {
                    if (curvature <= 0f) {
                        emitFlatDisplayQuad(buffer, positionMatrix, hw, hh, quadZ, false);
                    } else {
                        drawCurvedDisplay(buffer, positionMatrix, w, hh, quadZ, curvature, false);
                    }
                }

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

    /**
     * Emit a single flat display quad, with the texture and vertex order matching
     * the original display render. When {@code front} is false, the winding is
     * reversed so the quad is only visible from the back side.
     */
    private static void emitFlatDisplayQuad(VertexConsumer buffer, Matrix4f positionMatrix,
                                            float hw, float hh, float quadZ, boolean front) {

        if (front) {
            buffer.vertex(positionMatrix,  hw, -hh, quadZ).texture(0, 1).next();
            buffer.vertex(positionMatrix, -hw, -hh, quadZ).texture(1, 1).next();
            buffer.vertex(positionMatrix, -hw,  hh, quadZ).texture(1, 0).next();
            buffer.vertex(positionMatrix,  hw,  hh, quadZ).texture(0, 0).next();
        } else {
            buffer.vertex(positionMatrix,  hw,  hh, quadZ).texture(0, 0).next();
            buffer.vertex(positionMatrix, -hw,  hh, quadZ).texture(1, 0).next();
            buffer.vertex(positionMatrix, -hw, -hh, quadZ).texture(1, 1).next();
            buffer.vertex(positionMatrix,  hw, -hh, quadZ).texture(0, 1).next();
        }

    }

    /**
     * Emit the display quad tessellated into vertical strips bent along a
     * circular arc. The two edges stay on the original {@code quadZ} plane while
     * the center of the screen bows out of it. Curvature maps to the wrap angle
     * (0 = flat, 1 = a full cylinder), so at curvature 1 the left and right
     * borders travel all the way around and touch each other at the center of
     * the screen. Each strip is a single 4-vertex quad written in the same order
     * as the flat display quad, to match the render layer's buffer draw mode.
     * When {@code front} is false, the winding is reversed so the surface is only
     * visible from the back side.
     */
    private static void drawCurvedDisplay(VertexConsumer buffer, Matrix4f positionMatrix,
                                          float w, float hh, float quadZ, float curvature, boolean front) {

        float wrapAngle = (float) (Math.PI * 2.0 * curvature);
        float radius = w / wrapAngle;
        float cosHalf = (float) Math.cos(wrapAngle / 2.0);

        for (int i = 0; i < CURVATURE_SEGMENTS; ++i) {
            float t0 = (float) i / CURVATURE_SEGMENTS;
            float t1 = (float) (i + 1) / CURVATURE_SEGMENTS;

            // Angle offset from the center of the screen (t = 0.5).
            float a0 = wrapAngle * (t0 - 0.5f);
            float a1 = wrapAngle * (t1 - 0.5f);

            float x0 = radius * (float) Math.sin(a0);
            float x1 = radius * (float) Math.sin(a1);

            // Center of the screen bows out by radius * (1 - cos(wrapAngle/2));
            // the two edges (t = 0 and t = 1) meet on the quadZ plane when the
            // wrap angle reaches a full turn.
            float z0 = quadZ - radius * ((float) Math.cos(a0) - cosHalf);
            float z1 = quadZ - radius * ((float) Math.cos(a1) - cosHalf);

            // Texture U increases toward the left edge, matching the flat quad.
            float texU0 = 1f - t0;
            float texU1 = 1f - t1;

            if (front) {
                buffer.vertex(positionMatrix, x1, -hh, z1).texture(texU1, 1).next();
                buffer.vertex(positionMatrix, x0, -hh, z0).texture(texU0, 1).next();
                buffer.vertex(positionMatrix, x0,  hh, z0).texture(texU0, 0).next();
                buffer.vertex(positionMatrix, x1,  hh, z1).texture(texU1, 0).next();
            } else {
                buffer.vertex(positionMatrix, x1,  hh, z1).texture(texU1, 0).next();
                buffer.vertex(positionMatrix, x0,  hh, z0).texture(texU0, 0).next();
                buffer.vertex(positionMatrix, x0, -hh, z0).texture(texU0, 1).next();
                buffer.vertex(positionMatrix, x1, -hh, z1).texture(texU1, 1).next();
            }
        }

    }

}