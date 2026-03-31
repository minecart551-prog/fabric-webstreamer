package fr.theorozier.webstreamer;

import fr.theorozier.webstreamer.twitch.TwitchClient;
import fr.theorozier.webstreamer.display.render.DisplayBlockEntityRenderer;
import fr.theorozier.webstreamer.display.render.DisplayLayerManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.RenderLayer;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegLogCallback;
import fr.theorozier.webstreamer.youtube.YoutubeClient;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Environment(EnvType.CLIENT)
public class WebStreamerClientMod implements ClientModInitializer {

    private static final String TEMP_FILE_PREFIX = "webstreamer_yt_";

    public static DisplayLayerManager DISPLAY_LAYERS;
    public static TwitchClient TWITCH_CLIENT;
    public static YoutubeClient YOUTUBE_CLIENT;

    private static void cleanupYoutubeTempFiles() {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        int deleted = 0;
        int failed = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(TEMP_FILE_PREFIX)) {
                    try {
                        if (Files.deleteIfExists(file)) {
                            deleted++;
                        }
                    } catch (IOException e) {
                        failed++;
                        WebStreamerMod.LOGGER.warn("[CLIENT] Failed to delete temporary file {}", file, e);
                    }
                }
            }
        } catch (IOException e) {
            WebStreamerMod.LOGGER.warn("[CLIENT] Could not scan temporary directory {} for stale WebStreamer files", tempDir, e);
            return;
        }

        if (deleted > 0) {
            WebStreamerMod.LOGGER.info("[CLIENT] Deleted {} stale webstreamer temporary file(s) from {}", deleted, tempDir);
        } else {
            WebStreamerMod.LOGGER.info("[CLIENT] No stale webstreamer temporary files found in {}", tempDir);
        }

        if (failed > 0) {
            WebStreamerMod.LOGGER.warn("[CLIENT] Failed to delete {} stale webstreamer temporary file(s) in {}", failed, tempDir);
        }
    }

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
    
        cleanupYoutubeTempFiles();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            WebStreamerMod.LOGGER.info("[CLIENT] Client stopping, cleaning up WebStreamer temporary files.");
            cleanupYoutubeTempFiles();
        });

        DISPLAY_LAYERS = new DisplayLayerManager();
        TWITCH_CLIENT = new TwitchClient();
        YOUTUBE_CLIENT = new YoutubeClient();
        FFmpegLogCallback.setLevel(avutil.AV_LOG_QUIET);
    
    }

}
