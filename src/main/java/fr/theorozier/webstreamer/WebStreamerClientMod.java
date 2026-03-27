package fr.theorozier.webstreamer;

import fr.theorozier.webstreamer.twitch.TwitchClient;
import fr.theorozier.webstreamer.display.render.DisplayBlockEntityRenderer;
import fr.theorozier.webstreamer.display.render.DisplayLayerManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
// import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry; // Old
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.RenderLayer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;
import fr.theorozier.webstreamer.youtube.YoutubeClient;

@Environment(EnvType.CLIENT)
public class WebStreamerClientMod implements ClientModInitializer {

    public static DisplayLayerManager DISPLAY_LAYERS;
    public static TwitchClient TWITCH_CLIENT;
    public static YoutubeClient YOUTUBE_CLIENT;

    @Override
    public void onInitializeClient() {
        System.out.println("============ WEBSTREAMER CLIENT INIT START ============");
        WebStreamerMod.LOGGER.warn("========== WEBSTREAMER CLIENT MOD INITIALIZING ==========");

        // BlockEntityRendererRegistry.register(WebStreamerMod.DISPLAY_BLOCK_ENTITY, DisplayBlockEntityRenderer::new);
        BlockEntityRendererFactories.register(WebStreamerMod.DISPLAY_BLOCK_ENTITY, DisplayBlockEntityRenderer::new);
        System.out.println("[CLIENT] Registered DISPLAY_BLOCK_ENTITY renderer");
        WebStreamerMod.LOGGER.info("[CLIENT INIT] Registered DISPLAY_BLOCK_ENTITY renderer");
        
        BlockEntityRendererFactories.register(WebStreamerMod.TV_BLOCK_ENTITY, DisplayBlockEntityRenderer::new);
        System.out.println("[CLIENT] Registered TV_BLOCK_ENTITY renderer");
        WebStreamerMod.LOGGER.info("[CLIENT INIT] Registered TV_BLOCK_ENTITY renderer");
        
        BlockEntityRendererFactories.register(WebStreamerMod.BIG_TV_BLOCK_ENTITY, DisplayBlockEntityRenderer::new);
        System.out.println("[CLIENT] Registered BIG_TV_BLOCK_ENTITY renderer");
        WebStreamerMod.LOGGER.info("[CLIENT INIT] Registered BIG_TV_BLOCK_ENTITY renderer");
        
        BlockRenderLayerMap.INSTANCE.putBlock(WebStreamerMod.DISPLAY_BLOCK, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(WebStreamerMod.TV_BLOCK, RenderLayer.getCutout());
        BlockRenderLayerMap.INSTANCE.putBlock(WebStreamerMod.BIG_TV_BLOCK, RenderLayer.getCutout());
    
        DISPLAY_LAYERS = new DisplayLayerManager();
        TWITCH_CLIENT = new TwitchClient();
        YOUTUBE_CLIENT = new YoutubeClient();
        FFmpegLogCallback.setLevel(avutil.AV_LOG_QUIET);
    
    }

}
