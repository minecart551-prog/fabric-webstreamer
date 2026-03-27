# Waterframesfabric Rendering Architecture: Block Model + Video Content

## Overview

Waterframesfabric uses a **two-layer rendering system** that elegantly combines:
1. **Standard Minecraft block models** for the 3D frame structure
2. **Custom BlockEntityRenderer** for video texture overlay

This approach avoids directly manipulating Minecraft's BlockModelRenderer and instead leverages the existing blockstate/model system while overlaying video content via a BlockEntityRenderer.

---

## 1. Block Model Configuration (Blockstate System)

### File: `waterframesfabric/src/main/resources/assets/waterframes/blockstates/tv.json`

The blockstate defines multiple model variants for different orientations:

```json
{
  "variants": {
    "facing=north,attached_face=north": {"model": "waterframes:block/tv_back", "y": 0},
    "facing=north,attached_face=south": {"model": "waterframes:block/tv_back_extended", "y": 180},
    "facing=north,attached_face=west": {"model": "waterframes:block/tv_wall_right", "y": 0},
    "facing=north,attached_face=east": {"model": "waterframes:block/tv_wall_left", "y": 0},
    "facing=north,attached_face=up": {"model": "waterframes:block/tv", "y": 0},
    "facing=north,attached_face=down": {"model": "waterframes:block/tv_wall_top", "y": 0},
    
    "facing=south,attached_face=north": {"model": "waterframes:block/tv_back_extended", "y": 0},
    "facing=south,attached_face=south": {"model": "waterframes:block/tv_back", "y": 180},
    ...
  }
}
```

This blockstate configuration ensures Minecraft automatically renders the appropriate 3D block model based on the block's orientation properties.

---

## 2. Block Model Structure (3D Frame Definition)

### File: `waterframesfabric/src/main/resources/assets/waterframes/models/block/tv.json`

The block model defines the physical 3D frame with an empty **display element** for video overlay:

```json
{
  "credit": "Made with Blockbench",
  "texture_size": [64, 64],
  "textures": {
    "0": "waterframes:block/tv",
    "particle": "waterframes:block/displays"
  },
  "elements": [
    {
      "name": "support",
      "from": [5, 1, 4.5],
      "to": [7, 3, 5.5],
      "faces": {
        "north": {"uv": [7, 11, 7.5, 11.5], "texture": "#0"},
        "east": {"uv": [7, 11.5, 7.5, 12], "texture": "#0"},
        "south": {"uv": [7.5, 11, 8, 11.5], "texture": "#0"},
        "west": {"uv": [7.5, 11.5, 8, 12], "texture": "#0"},
        "up": {"uv": [7, 11.5, 6.5, 11], "texture": "#0"},
        "down": {"uv": [7, 11.5, 6.5, 12], "texture": "#0"}
      }
    },
    {
      "name": "base",
      "from": [1, 0, 3],
      "to": [15, 1, 7],
      "faces": {
        "north": {"uv": [0, 11, 3.25, 11.25], "texture": "#0"},
        "up": {"uv": [3.5, 12, 0, 11], "texture": "#0"},
        ...
      }
    },
    {
      "name": "corner_right",
      "from": [-9, 3, 3.65],
      "to": [-8, 24, 4],
      ...
    },
    {
      "name": "corner_bottom",
      "from": [-8, 3, 3.65],
      "to": [24, 4, 4],
      ...
    },
    {
      "name": "corner_top",
      "from": [-8, 23, 3.65],
      "to": [24, 24, 4],
      ...
    },
    {
      "name": "corner_left",
      "from": [24, 3, 3.65],
      "to": [25, 24, 4],
      ...
    },
    {
      "name": "display",
      "from": [-9, 3, 4],
      "to": [25, 24, 6],
      "faces": {
        "north": {"uv": [0, 0, 8.5, 5.25], "texture": "#0"},
        "east": {"uv": [0, 0, 0.25, 5.25], "texture": "#0"},
        "south": {"uv": [0, 5.25, 8.5, 10.5], "texture": "#0"},
        "west": {"uv": [8.25, 0, 8.5, 5.25], "texture": "#0"},
        "up": {"uv": [8.5, 0.25, 0, 0], "texture": "#0"},
        "down": {"uv": [0.25, 10.25, 8.5, 10.5], "texture": "#0"}
      }
    }
  ]
}
```

**Key elements**:
- Supports (2): Hold the display frame up
- Base: The stand/platform
- Corners (4): Frame edges
- **Display element**: The area where video is rendered (coordinates: -9 to 25 horizontally, 3 to 24 vertically, 4 to 6 z-depth)

---

## 3. Block Definition (Controls Rendering Strategy)

### File: `waterframesfabric/src/main/java/me/srrapero720/waterframes/common/block/DisplayBlock.java`

The key is the `getRenderShape()` method that controls whether Minecraft renders the block model:

```java
@Override 
public RenderShape getRenderShape(BlockState state) {
    return state.hasProperty(VISIBLE) ? 
        (state.getValue(VISIBLE) ? RenderShape.MODEL : RenderShape.INVISIBLE) : 
        RenderShape.MODEL;
}
```

**How it works**:
- `RenderShape.MODEL` → Minecraft renders the block model using the blockstate/model system
- `RenderShape.INVISIBLE` → Block model rendering is disabled
- The `VISIBLE` property allows toggling frame visibility

---

## 4. Custom Block Entity Renderer (Video Overlay)

### File: `waterframesfabric/src/main/java/me/srrapero720/waterframes/client/rendering/DisplayRenderer.java`

The DisplayRenderer is a BlockEntityRenderer that renders the video content **on top of** the block model:

```java
public class DisplayRenderer implements BlockEntityRenderer<DisplayTile> {

    private static final Function<ResourceLocation, RenderType> BLOCK_TRANSLUCENT_CULL_CUSTOM_TEXTURE = 
        Util.memoize((p_173198_) -> {
            RenderType.CompositeState rendertype$compositestate = 
                RenderType.CompositeState.builder()
                    .setShaderState(RenderType.RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(p_173198_, false, false))
                    .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderType.LIGHTMAP)
                    .setOverlayState(RenderType.NO_OVERLAY)
                    .createCompositeState(false);
            return RenderType.create("block_translucent_cull_custom_texture", 
                DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, true, true, rendertype$compositestate);
        });

    private final BlockEntityRendererProvider.Context context;
    
    public DisplayRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public void render(DisplayTile tile, float partialTicks, PoseStack pose, 
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        
        var display = tile.activeDisplay();
        if (display == null || !DisplaysConfig.keepsRendering()) return;
        display.preRender();

        var direction = tile.getDirection();
        var box = tile.getRenderBox();
        var invertedFace = tile.caps.invertedFace(tile);
        var boxFace = BoxFace.get(Facing.get(invertedFace ? direction.getOpposite() : direction));
        var facing = boxFace.facing;
        
        // Full brightness for display
        packedLight = LightTexture.FULL_BRIGHT;

        boolean front = !tile.caps.projects() || tile.data.renderBothSides;
        boolean back = tile.caps.projects() || tile.data.renderBothSides;
        boolean flipX = tile.caps.projects() != tile.data.flipX;
        boolean flipY = tile.data.flipY;
        
        int r, b, g;
        r = g = b = tile.data.brightness;
        int a = tile.data.alpha;

        // Apply local transformations for rotation
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(facing.rotation().rotation((float) Math.toRadians(-tile.data.rotation)));
        pose.translate(-0.5, -0.5, -0.5);

        // TWEAK FOR "EXTRA-RESIZING"
        if (tile.caps.growMax(tile, facing, invertedFace)) {
            box.setMax(facing.axis, box.getMax(facing.axis) + tile.caps.growSize());
        } else {
            box.setMin(facing.axis, box.getMin(facing.axis) - tile.caps.growSize());
        }

        // RENDERING VIDEO CONTENT
        if (display.isLoading()) {
            this.vertex(pose, bufferSource, getLoadingBox(tile, box, facing), boxFace, facing, 
                        packedLight, packedOverlay, front, back, flipX, flipY, r, g, b, a, 
                        WaterFrames.LOADING_ANIMATION);
        } else if (display.canRender()) {
            var tex = display.getTextureId();
            if (tex != null) {
                this.vertex(pose, bufferSource, box, boxFace, facing, packedLight, packedOverlay,
                            front, back, flipX, flipY, r, g, b, a, tex);
            }

            if (display.isBuffering()) {
                this.vertex(pose, bufferSource, getLoadingBox(tile, box, facing), boxFace, facing, 
                            packedLight, packedOverlay, front, back, flipX, flipY, r, g, b, a, 
                            WaterFrames.LOADING_ANIMATION);
            }
        }

        pose.popPose();
    }
}
```

**Key rendering details**:

### Custom RenderType Creation
```java
private static final Function<ResourceLocation, RenderType> BLOCK_TRANSLUCENT_CULL_CUSTOM_TEXTURE = 
    Util.memoize((p_173198_) -> {
        RenderType.CompositeState rendertype$compositestate = 
            RenderType.CompositeState.builder()
                .setShaderState(RenderType.RENDERTYPE_TRANSLUCENT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(p_173198_, false, false))
                .setTransparencyState(RenderType.TRANSLUCENT_TRANSPARENCY)
                .setLightmapState(RenderType.LIGHTMAP)
                .setOverlayState(RenderType.NO_OVERLAY)
                .createCompositeState(false);
        return RenderType.create("block_translucent_cull_custom_texture", 
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, true, true, rendertype$compositestate);
    });
```

This creates a translucent render type that uses a custom texture resource location, allowing per-frame texture swapping.

### Vertex Generation
```java
public void vertex(PoseStack pose, VertexConsumer builder, AlignedBox box, BoxFace boxface, 
                   BoxCorner corner, Facing facing, int packedLight, int packedOverlay, 
                   boolean flipX, boolean flipY, int r, int g, int b, int a) {
    
    Vec3i normal = facing.normal;
    builder.vertex(pose.last().pose(), box.get(corner.x), box.get(corner.y), box.get(corner.z))
            .color(r, g, b, a)
            .uv(corner.isFacing(boxface.getTexU()) != flipX ? 1f : 0f, 
                corner.isFacing(boxface.getTexV()) != flipY ? 1f : 0f)
            .overlayCoords(OverlayTexture.NO_OVERLAY)
            .uv2(packedLight)
            .normal(pose.last().normal(), normal.getX(), normal.getY(), normal.getZ())
            .endVertex();
}
```

Manually constructs vertices for the display area, allowing custom UV mapping and dynamic texture binding.

---

## 5. Block Entity Registration

### File: `waterframesfabric/src/main/java/me/srrapero720/waterframes/DisplaysRegistry.java`

The DisplayRenderer is registered for all display block entities:

```java
import me.srrapero720.waterframes.client.rendering.DisplayRenderer;
...
@Environment(EnvType.CLIENT)
private static void registerBlockEntityRenderers() {
    BlockEntityRenderers.register(TILE_FRAME, DisplayRenderer::new);
    BlockEntityRenderers.register(TILE_PROJECTOR, DisplayRenderer::new);
    BlockEntityRenderers.register(TILE_TV, DisplayRenderer::new);
    BlockEntityRenderers.register(TILE_BIG_TV, DisplayRenderer::new);
    BlockEntityRenderers.register(TILE_TV_BOX, DisplayRenderer::new);
}
```

---

## Rendering Flow Summary

```
1. Minecraft's Rendering Engine Tick
   ↓
2. Block Renderer Passes (Solid Pass)
   ├─ Reads BlockState (blockstates/tv.json)
   ├─ Loads Block Model (models/block/tv.json)
   ├─ Renders 3D Frame Structure (supports, base, corners)
   └─ Renders Display Element with placeholder texture
   ↓
3. Block Entity Renderer Pass (After vanilla rendering)
   ├─ DisplayRenderer.render() called
   ├─ Creates translucent render type with video texture
   ├─ Constructs quad with video texture coordinates
   └─ Overlays video on top of display element
   ↓
4. Result: 3D frame + video texture combined seamlessly
```

---

## Key Technical Insights

1. **No BlockModelRenderer Direct Usage**: Unlike some approaches, waterframesfabric doesn't directly use `BlockModelRenderer` or `BlockRenderDispatcher`. Instead, it leverages Minecraft's automatic blockstate/model rendering system.

2. **RenderType Customization**: A custom RenderType is created that supports per-frame texture binding through `RenderStateShard.TextureStateShard`, allowing dynamic video frame swapping.

3. **Two-Pass Rendering**: 
   - **Pass 1 (Solid/Translucent)**: Standard block model rendering by Minecraft
   - **Pass 2 (BlockEntity)**: Custom video overlay rendering

4. **Matrix Stack Transformations**: Full PoseStack transformations are applied for rotation, scaling, and positioning of the video content relative to the block model.

5. **Handled Properties**:
   - `VISIBLE`: Controls whether block model shows
   - `flipX` / `flipY`: Video content flipping
   - `brightness` / `alpha`: Display brightness and transparency
   - `rotation`: Display rotation

6. **AlignedBox Integration**: Uses `AlignedBox` and `BoxFace` utilities to calculate render areas based on the display configuration, supporting arbitrary sizes and transformations.

---

## Example: How Video Replaces Display Element

1. **Block model defines**: `display` element at coordinates (-9 to 25, 3 to 24, 4 to 6)
2. **DisplayTile stores**: Video dimensions and positioning in `displayBox` 
3. **DisplayRenderer**:
   - Creates vertices at the calculated box coordinates
   - Binds video texture resource
   - Renders translucent quads with video UVs
4. **Result**: The placeholder texture from the block model is visually replaced by the dynamic video frame

This means the block frame is always rendered normally, and the video content is rendered as a custom quad overlay on top of it, effectively replacing the visual appearance of the display element.
