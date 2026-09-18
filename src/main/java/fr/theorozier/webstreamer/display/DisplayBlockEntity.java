
package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.display.WebDisplayPBlock;
import fr.theorozier.webstreamer.display.source.DisplaySource;
import fr.theorozier.webstreamer.display.source.RawDisplaySource;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * <p>The block entity that is backing {@link DisplayBlock}, it's mainly composed of the
 * {@link DisplaySource}, size and audio configuration.</p>
 */
public class DisplayBlockEntity extends BlockEntity {

    private boolean removed = false;
    // ...existing code...

    @Override
    public void markRemoved() {
        super.markRemoved();

        synchronized (this.cachedRenderDataGuard) {
            this.cachedRenderData = null;
        }

        // Release any layer owned by this display immediately. Cleanup is keyed by
        // the display entity (not by URI) so it works even while the URI is
        // unresolved/null — the old URI-gated lookup left an orphan layer in the map
        // (consuming the cost budget) for up to 60 seconds, starving other displays
        // and forcing a block to be broken twice before it would play again.
        // Never call DisplaySource.getUri() from here — it may make blocking HTTP
        // calls (e.g. Twitch) that would freeze the render thread.
        if (net.fabricmc.api.EnvType.CLIENT == net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()) {
            try {
                fr.theorozier.webstreamer.WebStreamerClientMod.DISPLAY_LAYERS.cleanupLayersForDisplay(this);
            } catch (Exception e) {
                // Ignore errors (e.g., OutOfLayerException, UnknownFormatException)
            }
        }

        this.removed = true;
    }

    private DisplaySource source = new RawDisplaySource();
    private float width = 1;
    private float height = 1;
    private float audioDistance = 10f;
    private float audioVolume = 1f;
    private float renderDistance = 64f;
    private double offsetX = 0.0;
    private double offsetY = 0.0;
    private double offsetZ = 0.0;
    private float rotationX = 0f;
    private float rotationY = 0f;
    private float rotationZ = 0f;
    private float curvature = 0f;
    private float brightness = 1f;
    private DisplaySide displaySide = DisplaySide.FRONT;
    private boolean requiresOp = false;
    private boolean playbackPaused = false;

    protected DisplayBlockEntity(net.minecraft.block.entity.BlockEntityType<? extends DisplayBlockEntity> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        this(WebStreamerMod.DISPLAY_BLOCK_ENTITY, pos, state);
    }

    public void setSource(@NotNull DisplaySource source) {
        Objects.requireNonNull(source);
        this.source = source;
        this.markRenderDataSourceDirty();
        this.markDirty();
    }

    @NotNull
    public DisplaySource getSource() {
        if (removed || source == null) {
            return new fr.theorozier.webstreamer.display.source.RawDisplaySource(); // returns null URI
        }
        return source;
    }

    /**
     * Reset the internal source URI, that is currently used with Twitch sources in order
     * to reset the channels' cache. This also mark the render data as dirty in order to
     * update the URI on next render.
     */
    public void resetSourceUri() {
        this.source.resetUri();
        this.markRenderDataSourceDirty();
    }

    public void setSize(float width, float height) {
        if (this.getCachedState().getBlock() instanceof WebDisplayPBlock) {
            width = Math.min(width, WebDisplayPBlock.MAX_SIZE);
            height = Math.min(height, WebDisplayPBlock.MAX_SIZE);
        }
        this.width = width;
        this.height = height;
        this.markDirty();
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public void setAudioConfig(float distance, float volume) {
        this.audioDistance = Math.min(distance, this.renderDistance);
        this.audioVolume = volume;
        this.markDirty();
    }

    public float getAudioDistance() {
        return audioDistance;
    }

    public float getAudioVolume() {
        return audioVolume;
    }

    public void setRenderDistance(float renderDistance) {
        this.renderDistance = renderDistance;
        this.audioDistance = Math.min(this.audioDistance, this.renderDistance);
        this.markDirty();
    }

    public float getRenderDistance() {
        return renderDistance;
    }

    public void setOffset(double offsetX, double offsetY, double offsetZ) {
        if (this.getCachedState().getBlock() instanceof WebDisplayPBlock) {
            offsetX = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetX));
            offsetY = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetY));
            offsetZ = Math.max(-WebDisplayPBlock.MAX_OFFSET, Math.min(WebDisplayPBlock.MAX_OFFSET, offsetZ));
        }
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.markDirty();
    }

    public double getOffsetX() {
        return offsetX;
    }

    public double getOffsetY() {
        return offsetY;
    }

    public double getOffsetZ() {
        return offsetZ;
    }

    public void setRotation(float rotationX, float rotationY, float rotationZ) {
        this.rotationX = rotationX;
        this.rotationY = rotationY;
        this.rotationZ = rotationZ;
        this.markDirty();
    }

    public float getRotationX() {
        return rotationX;
    }

    public float getRotationY() {
        return rotationY;
    }

    public float getRotationZ() {
        return rotationZ;
    }

    /**
     * Set the screen curvature of the display, in the range [0, 1] where 0 is a
     * flat screen and 1 is the strongest bow toward the viewer. Only used by the
     * two web display block types; TV blocks are fixed at 0.
     */
    public void setCurvature(float curvature) {
        this.curvature = Math.max(0f, Math.min(1f, curvature));
        this.markDirty();
    }

    public float getCurvature() {
        return curvature;
    }

    /**
     * Set the display brightness, in the range [0, 1] where 0 is completely
     * dark and 1 is full brightness.
     */
    public void setBrightness(float brightness) {
        this.brightness = Math.max(0f, Math.min(1f, brightness));
        this.markDirty();
    }

    public float getBrightness() {
        return brightness;
    }

    /**
     * Set which side(s) of the display surface are rendered.
     */
    public void setDisplaySide(DisplaySide displaySide) {
        this.displaySide = displaySide == null ? DisplaySide.FRONT : displaySide;
        this.markDirty();
    }

    public DisplaySide getDisplaySide() {
        return this.displaySide;
    }

    /** Which side of the display surface is rendered. */
    public enum DisplaySide {
        FRONT,
        BACK,
        BOTH
    }

    public boolean requiresOp() {
        return requiresOp;
    }

    public void setRequiresOp(boolean requiresOp) {
        this.requiresOp = requiresOp;
        this.markDirty();
    }

    public boolean isPlaybackPaused() {
        return this.playbackPaused;
    }

    public void setPlaybackPaused(boolean playbackPaused) {
        this.playbackPaused = playbackPaused;
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {

        super.writeNbt(nbt);

        NbtCompound displayNbt = new NbtCompound();
        nbt.put("display", displayNbt);

        displayNbt.putFloat("width", this.width);
        displayNbt.putFloat("height", this.height);
        displayNbt.putFloat("audioDistance", this.audioDistance);
        displayNbt.putFloat("audioVolume", this.audioVolume);
        displayNbt.putFloat("renderDistance", this.renderDistance);
        displayNbt.putDouble("offsetX", this.offsetX);
        displayNbt.putDouble("offsetY", this.offsetY);
        displayNbt.putDouble("offsetZ", this.offsetZ);
        displayNbt.putBoolean("requiresOp", this.requiresOp);
        displayNbt.putBoolean("playbackPaused", this.playbackPaused);
        displayNbt.putFloat("rotationX", this.rotationX);
        displayNbt.putFloat("rotationY", this.rotationY);
        displayNbt.putFloat("rotationZ", this.rotationZ);
        displayNbt.putFloat("curvature", this.curvature);
        displayNbt.putFloat("brightness", this.brightness);
        displayNbt.putString("displaySide", this.displaySide.name());

        if (this.source != null) {
            displayNbt.putString("type", this.source.getType());
            this.source.writeNbt(displayNbt);
        } else {
            displayNbt.putString("type", "");
        }

    }

    @Override
    public void readNbt(NbtCompound nbt) {

        super.readNbt(nbt);

        if (nbt.get("display") instanceof NbtCompound displayNbt) {

            if (displayNbt.get("width") instanceof NbtFloat width) {
                this.width = width.floatValue();
            } else {
                this.width = 1;
            }

            if (displayNbt.get("height") instanceof NbtFloat height) {
                this.height = height.floatValue();
            } else {
                this.height = 1;
            }

            if (displayNbt.get("audioDistance") instanceof NbtFloat audioDistance) {
                this.audioDistance = audioDistance.floatValue();
            } else {
                this.audioDistance = 10f;
            }

            if (displayNbt.get("audioVolume") instanceof NbtFloat audioVolume) {
                this.audioVolume = audioVolume.floatValue();
            } else {
                this.audioVolume = 1f;
            }

            if (displayNbt.get("renderDistance") instanceof NbtFloat renderDistance) {
                this.renderDistance = renderDistance.floatValue();
            } else {
                this.renderDistance = 64f;
            }

            // Clamp audioDistance to renderDistance so audio never exceeds view range.
            this.audioDistance = Math.min(this.audioDistance, this.renderDistance);

            if (displayNbt.get("offsetX") instanceof NbtDouble offsetX) {
                this.offsetX = offsetX.doubleValue();
            } else {
                this.offsetX = 0.0;
            }

            if (displayNbt.get("offsetY") instanceof NbtDouble offsetY) {
                this.offsetY = offsetY.doubleValue();
            } else {
                this.offsetY = 0.0;
            }

            if (displayNbt.get("offsetZ") instanceof NbtDouble offsetZ) {
                this.offsetZ = offsetZ.doubleValue();
            } else {
                this.offsetZ = 0.0;
            }

            if (displayNbt.get("requiresOp") instanceof net.minecraft.nbt.NbtByte requiresOp) {
                this.requiresOp = requiresOp.byteValue() != 0;
            } else {
                this.requiresOp = false;
            }

            if (displayNbt.get("playbackPaused") instanceof net.minecraft.nbt.NbtByte playbackPaused) {
                this.playbackPaused = playbackPaused.byteValue() != 0;
            } else {
                this.playbackPaused = false;
            }

            if (displayNbt.get("rotationX") instanceof NbtFloat rx) {
                this.rotationX = rx.floatValue();
            } else {
                this.rotationX = 0f;
            }

            if (displayNbt.get("rotationY") instanceof NbtFloat ry) {
                this.rotationY = ry.floatValue();
            } else {
                this.rotationY = 0f;
            }

            if (displayNbt.get("rotationZ") instanceof NbtFloat rz) {
                this.rotationZ = rz.floatValue();
            } else {
                this.rotationZ = 0f;
            }

            if (displayNbt.get("curvature") instanceof NbtFloat curvature) {
                this.curvature = Math.max(0f, Math.min(1f, curvature.floatValue()));
            } else {
                this.curvature = 0f;
            }

            if (displayNbt.get("brightness") instanceof NbtFloat brightness) {
                this.brightness = Math.max(0f, Math.min(1f, brightness.floatValue()));
            } else {
                this.brightness = 1f;
            }

            String sideName = displayNbt.getString("displaySide");
            this.displaySide = DisplaySide.BACK.name().equals(sideName) ? DisplaySide.BACK
                    : DisplaySide.BOTH.name().equals(sideName) ? DisplaySide.BOTH : DisplaySide.FRONT;

            if (displayNbt.get("type") instanceof NbtString type) {
                this.source = DisplaySource.newSourceFromType(type.asString());
                this.source.readNbt(displayNbt);
            } else {
                this.source = new RawDisplaySource();
            }

            this.markRenderDataSourceDirty();

        }

        // Validate render distance and audio distance based on block type
        if (this.getCachedState().getBlock() instanceof WebDisplayPBlock || 
            this.getCachedState().getBlock() instanceof TVBlock || 
            this.getCachedState().getBlock() instanceof BigTVBlock) {
            // TV blocks have a maximum render distance of 64 blocks
            this.renderDistance = Math.min(this.renderDistance, 64f);
        } else if (this.getCachedState().getBlock() instanceof DisplayBlock) {
            // Base display blocks have a maximum render distance of 512 blocks
            this.renderDistance = Math.min(this.renderDistance, 512f);
        }
        // Always clamp audio distance to render distance
        this.audioDistance = Math.min(this.audioDistance, this.renderDistance);

    }

    @Nullable
    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }

    @Override
    public NbtCompound toInitialChunkDataNbt() {
        return createNbt();
    }

    /** Utility method to make a log message prefixed by this display's position. */
    public String makeLog(String message) {
        return "[" + this.pos.getX() + "/" + this.pos.getY() + "/" + this.pos.getZ() + "] " + message;
    }

    /*
     * == RENDER DATA
     *
     * The render data is present on both server and client side, but is only actually
     * used on client side for storing the display render data.
     */

    private final Object cachedRenderDataGuard = new Object();
    private Object cachedRenderData;

    /**
     * <b>Should only be called from client side.</b>
     *
     * @return A <code>DisplayRenderData</code> class, only valid on client side.
     */
    public Object getRenderData() {
        if (removed) return null;
        synchronized (this.cachedRenderDataGuard) {
            if (this.cachedRenderData == null) {
                this.cachedRenderData = new fr.theorozier.webstreamer.display.render.DisplayRenderData(this);
            }
            return this.cachedRenderData;
        }
    }

    /**
     * Mark internal render data URL as dirty. This will force the URI to be
     * re-resolved on the next render tick. Thread-safe.
     */
    public void markRenderDataSourceDirty() {
        synchronized (this.cachedRenderDataGuard) {
            if (this.cachedRenderData != null) {
                ((fr.theorozier.webstreamer.display.render.DisplayRenderData) this.cachedRenderData).markSourceDirty();
            }
        }
    }

}