package fr.theorozier.webstreamer;

import fr.theorozier.webstreamer.display.DisplayBlock;
import fr.theorozier.webstreamer.display.DisplayBlockEntity;
import fr.theorozier.webstreamer.display.DisplayNetworking;
import fr.theorozier.webstreamer.server.ServerSourceRegistry;
import fr.theorozier.webstreamer.display.TVBlock;
import fr.theorozier.webstreamer.display.TVBlockEntity;
import fr.theorozier.webstreamer.display.BigTVBlock;
import fr.theorozier.webstreamer.display.BigTVBlockEntity;
import fr.theorozier.webstreamer.display.WebDisplayPBlock;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebStreamerMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("WebStreamer");
    public static final String MOD_ID = "webstreamer";

    public static Block DISPLAY_BLOCK;
    public static Block DISPLAY_P_BLOCK;
    public static BlockItem DISPLAY_ITEM;
    public static BlockItem DISPLAY_P_ITEM;
    public static BlockEntityType<DisplayBlockEntity> DISPLAY_BLOCK_ENTITY;

    public static Block TV_BLOCK;
    public static BlockItem TV_ITEM;
    public static BlockEntityType<TVBlockEntity> TV_BLOCK_ENTITY;

    public static Block BIG_TV_BLOCK;
    public static BlockItem BIG_TV_ITEM;
    public static BlockEntityType<BigTVBlockEntity> BIG_TV_BLOCK_ENTITY;

    public static ItemGroup WEBSTREAMER_TAB;

    @Override
    public void onInitialize() {

        DISPLAY_BLOCK = new DisplayBlock();
        DISPLAY_P_BLOCK = new WebDisplayPBlock();
        DISPLAY_ITEM = new BlockItem(DISPLAY_BLOCK, new FabricItemSettings());
        DISPLAY_P_ITEM = new BlockItem(DISPLAY_P_BLOCK, new FabricItemSettings());

        Registry.register(Registries.BLOCK, "webstreamer:display", DISPLAY_BLOCK);
        Registry.register(Registries.ITEM, "webstreamer:display", DISPLAY_ITEM);
        Registry.register(Registries.BLOCK, "webstreamer:display_p", DISPLAY_P_BLOCK);
        Registry.register(Registries.ITEM, "webstreamer:display_p", DISPLAY_P_ITEM);
        DISPLAY_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, "webstreamer:display", FabricBlockEntityTypeBuilder.create(DisplayBlockEntity::new, DISPLAY_BLOCK, DISPLAY_P_BLOCK).build());

        // Register TV blocks
        TV_BLOCK = new TVBlock();
        TV_ITEM = new BlockItem(TV_BLOCK, new FabricItemSettings());

        Registry.register(Registries.BLOCK, "webstreamer:tv", TV_BLOCK);
        Registry.register(Registries.ITEM, "webstreamer:tv", TV_ITEM);
        TV_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, "webstreamer:tv", FabricBlockEntityTypeBuilder.create(TVBlockEntity::new, TV_BLOCK).build());

        // Register BigTV blocks
        BIG_TV_BLOCK = new BigTVBlock();
        BIG_TV_ITEM = new BlockItem(BIG_TV_BLOCK, new FabricItemSettings());

        Registry.register(Registries.BLOCK, "webstreamer:big_tv", BIG_TV_BLOCK);
        Registry.register(Registries.ITEM, "webstreamer:big_tv", BIG_TV_ITEM);
        BIG_TV_BLOCK_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, "webstreamer:big_tv", FabricBlockEntityTypeBuilder.create(BigTVBlockEntity::new, BIG_TV_BLOCK).build());
        // Create custom creative tab
        WEBSTREAMER_TAB = Registry.register(Registries.ITEM_GROUP, "webstreamer:tab", FabricItemGroup.builder()
                .icon(() -> new ItemStack(DISPLAY_ITEM))
                .displayName(Text.literal("WebStreamer"))
                .entries((itemDisplayParameters, entries) -> {
                    entries.add(DISPLAY_ITEM);
                    entries.add(DISPLAY_P_ITEM);
                    entries.add(TV_ITEM);
                    entries.add(BIG_TV_ITEM);
                })
                .build());
        DisplayNetworking.registerDisplayUpdateReceiver();
        ServerTickEvents.END_SERVER_TICK.register(DisplayNetworking::cleanupPlaybackViewers);
        ServerSourceRegistry.load(FabricLoader.getInstance().getConfigDir());

        // Restart HTTP server when a server starts (handles singleplayer world re-open)
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ServerSourceRegistry.restartHttpServerIfNeeded(FabricLoader.getInstance().getConfigDir());

            // 1. Check WEBSTREAMER_IP env var
            String envIp = System.getenv("WEBSTREAMER_IP");
            if (envIp != null && !envIp.isEmpty()) {
                ServerSourceRegistry.setServerIp(envIp);
            } else {
                // 2. Read http-ip from webstreamer's server.properties
                String configuredIp = ServerSourceRegistry.readHttpIp(FabricLoader.getInstance().getConfigDir());
                if (configuredIp != null && !configuredIp.isEmpty()) {
                    ServerSourceRegistry.setServerIp(configuredIp);
                } else {
                    // 3. Auto-detect from network interfaces
                    String detected = detectServerIp();
                    if (detected != null) {
                        ServerSourceRegistry.setServerIp(detected);
                    }
                }
            }
        });

        // Stop HTTP server on server shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ServerSourceRegistry.stopHttpServer();
        });

        // Broadcast server sources to players when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            DisplayNetworking.sendSourcesBroadcast(handler.getPlayer());
        });
        
        LOGGER.info("WebStreamer started.");

    }

    /**
     * Detect the server's LAN IP by scanning network interfaces.
     * Skips loopback, down interfaces, and virtual/docker ranges.
     * Returns null if no suitable address found.
     */
    private static String detectServerIp() {
        try {
            String fallback = null;
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof java.net.Inet4Address)) continue;
                    String ip = addr.getHostAddress();
                    // Skip Docker/container ranges
                    if (ip.startsWith("172.17.") || ip.startsWith("172.18.") ||
                        ip.startsWith("172.19.") || ip.startsWith("172.2") ||
                        ip.startsWith("172.3")) continue;
                    // Prefer non-private IPs (public/WAN)
                    if (!addr.isSiteLocalAddress()) {
                        return ip;
                    }
                    // Remember first private LAN IP as fallback
                    if (fallback == null) {
                        fallback = ip;
                    }
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

}
