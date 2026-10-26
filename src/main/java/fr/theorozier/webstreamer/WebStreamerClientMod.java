package fr.theorozier.webstreamer;

import fr.theorozier.webstreamer.twitch.TwitchClient;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.display.render.DisplayBlockEntityRenderer;
import fr.theorozier.webstreamer.display.render.DisplayLayerManager;
import fr.theorozier.webstreamer.jni.NativeLibrary;
import fr.theorozier.webstreamer.util.FFmpegLibrary;
import fr.theorozier.webstreamer.util.WebStreamerNativesManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.client.render.RenderLayer;
import fr.theorozier.webstreamer.youtube.YoutubeClient;

import java.lang.reflect.Method;

@Environment(EnvType.CLIENT)
public class WebStreamerClientMod implements ClientModInitializer {

    public static DisplayLayerManager DISPLAY_LAYERS;
    public static TwitchClient TWITCH_CLIENT;
    public static YoutubeClient YOUTUBE_CLIENT;

    private static boolean irisLoaded = false;
    private static Object irisApiInstance = null;
    private static Method isShaderPackInUseMethod = null;
    private static boolean irisCheckDone = false;

    public static boolean isShaderActive() {
        if (!irisCheckDone) {
            irisCheckDone = true;
            irisLoaded = FabricLoader.getInstance().isModLoaded("iris");
            if (irisLoaded) {
                try {
                    Class<?> cls = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    Object instance = cls.getMethod("getInstance").invoke(null);
                    if (instance != null) {
                        irisApiInstance = instance;
                        isShaderPackInUseMethod = cls.getMethod("isShaderPackInUse");
                    }
                } catch (Exception ignored) {}
            }
        }
        if (!irisLoaded || irisApiInstance == null || isShaderPackInUseMethod == null) {
            return false;
        }
        try {
            return (Boolean) isShaderPackInUseMethod.invoke(irisApiInstance);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onInitializeClient() {
        System.out.println("============ WEBSTREAMER CLIENT INIT START ============");
        WebStreamerMod.LOGGER.warn("========== WEBSTREAMER CLIENT MOD INITIALIZING ==========");

        // Initialize native library (optional — falls back to JavaCV if unavailable)
        NativeLibrary.init();

        // Download the FFmpeg platform natives at game launch instead of on the
        // first video after joining a server: the platform JAR is large, and
        // doing it at join time stalls/crashes clients mid-join. Runs on a
        // background daemon thread; if natives are already cached this returns
        // almost immediately. Download-only — the actual System.load of the
        // libraries stays deferred to first use, because loading them before
        // the GLFW window exists can interfere with GLX window creation (see
        // FFmpegLibrary's class javadoc).
        Thread nativesWarmup = new Thread(
                () -> WebStreamerNativesManager.warmUp(),
                "WebStreamer-natives-warmup");
        nativesWarmup.setDaemon(true);
        nativesWarmup.start();

        // Second launch-time step: preload the native DLLs and link the FFmpeg
        // classes, so joining a server no longer pays that one-time cost either
        // (on Windows that's ~40 DLL loads + JavaCPP class init, up to a couple
        // of seconds). Runs at CLIENT_STARTED — the head of MinecraftClient.run(),
        // i.e. AFTER the constructor created the GLFW window and GL context.
        // It must NOT run in onInitializeClient() like the download: loading
        // FFmpeg natives before the window exists interferes with GLX/GLFW
        // window creation on some GPUs (see FFmpegLibrary's class javadoc).
        // Background thread + FFmpegLibrary's INIT_LOCK keep it single-flight:
        // any later initializeFrameGrabber() from layer creation just blocks
        // until this finishes, then sees the linked result. If the download
        // failed (offline at launch), the link attempt is skipped so
        // FFmpegLibrary's sticky failure flag isn't set — the first video after
        // joining then retries everything exactly as before.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Thread ffmpegWarmup = new Thread(() -> {
                try {
                    nativesWarmup.join(); // wait for the download attempt to settle
                } catch (InterruptedException ignored) { }
                if (!WebStreamerNativesManager.nativesPresent()) {
                    return;
                }
                FFmpegLibrary.initializeFrameGrabber();
            }, "WebStreamer-ffmpeg-warmup");
            ffmpegWarmup.setDaemon(true);
            ffmpegWarmup.start();
        });

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
        DisplayNetworking.registerSourcesBroadcastReceiver();
        DisplayNetworking.registerHandshakeReceiver();

        // Send handshake when connecting to a server
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            DisplayNetworking.sendHandshake();
        });
    }

}
