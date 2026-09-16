package fr.theorozier.webstreamer.display;

import fr.theorozier.webstreamer.WebStreamerMod;
import fr.theorozier.webstreamer.server.ServerSourceRegistry;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * <p>This static class provides methods to send display block update when editing one,
 * from client to server. Note that the opposite packet from server to client is 
 * implemented natively by the game so we don't need to implement it.</p>
 */
public class DisplayNetworking {
	
	public static final Identifier DISPLAY_BLOCK_UPDATE_PACKET_ID = new Identifier("webstreamer:display_block_update");
	public static final Identifier DISPLAY_PLAYBACK_STATE_PACKET_ID = new Identifier("webstreamer:display_playback_state");
	public static final Identifier SERVER_SOURCES_BROADCAST_PACKET_ID = new Identifier("webstreamer:server_sources_broadcast");

	private static final Map<PlaybackKey, Set<UUID>> PLAYBACK_VIEWERS = new HashMap<>();
	private static final Map<PlaybackKey, Boolean> CLIENT_PLAYBACK_RANGE = new HashMap<>();
	/** Last time the client sent a playback state packet per display (for the self-heal resync). */
	private static final Map<PlaybackKey, Long> CLIENT_PLAYBACK_SENT_AT = new HashMap<>();

	/** Client-side cache of server sources (name → URL list), populated by server broadcasts. */
	private static volatile Map<String, List<String>> clientSourceCache = Map.of();

	private static PacketByteBuf encodeDisplayUpdatePacket(DisplayBlockEntity blockEntity) {
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeBlockPos(blockEntity.getPos());
		NbtCompound comp = new NbtCompound();
		blockEntity.writeNbt(comp);
		buf.writeNbt(comp);
		return buf;
	}
	
	private static void decodeDisplayUpdatePacket(PacketByteBuf buf, BiConsumer<BlockPos, NbtCompound> consumer) {
		BlockPos pos = buf.readBlockPos();
		NbtCompound nbt = buf.readNbt();
		consumer.accept(pos, nbt);
	}
	
	/**
	 * Client-side only, send a display update packet to the server.
	 * @param blockEntity The display block entity.
	 */
	@Environment(EnvType.CLIENT)
	public static void sendDisplayUpdate(DisplayBlockEntity blockEntity) {
		ClientPlayNetworking.send(DISPLAY_BLOCK_UPDATE_PACKET_ID, encodeDisplayUpdatePacket(blockEntity));
	}

    @Environment(EnvType.CLIENT)
    public static void sendPlaybackState(DisplayBlockEntity blockEntity, boolean inRange) {
        if (blockEntity.getWorld() == null) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(blockEntity.getPos());
        buf.writeBoolean(inRange);
        ClientPlayNetworking.send(DISPLAY_PLAYBACK_STATE_PACKET_ID, buf);
    }

    // -------------------------------------------------------------------------
    // Server sources cache (client-side, populated by server broadcasts)
    // -------------------------------------------------------------------------

    /**
     * Client-side only, resolve a server source name from the local cache.
     * The cache is populated by {@link #SERVER_SOURCES_BROADCAST_PACKET_ID} packets
     * sent by the server when sources.txt is loaded or reloaded.
     *
     * @return The resolved URL list for this source, or {@code null} if not found.
     */
    @Environment(EnvType.CLIENT)
    public static List<String> resolveServerSource(String name) {
        return clientSourceCache.get(name);
    }

    /**
     * Client-side only, register the receiver for server sources broadcast packets.
     * Called once from {@link fr.theorozier.webstreamer.WebStreamerClientMod#onInitializeClient()}.
     */
    @Environment(EnvType.CLIENT)
    public static void registerSourcesBroadcastReceiver() {
        ClientPlayNetworking.registerGlobalReceiver(SERVER_SOURCES_BROADCAST_PACKET_ID, (client, handler2, buf, responseSender) -> {
            int count = buf.readVarInt();
            HashMap<String, List<String>> map = new HashMap<>(count);
            for (int i = 0; i < count; i++) {
                String name = buf.readString();
                int listSize = buf.readVarInt();
                ArrayList<String> urls = new ArrayList<>(listSize);
                for (int j = 0; j < listSize; j++) {
                    urls.add(buf.readString());
                }
                map.put(name, urls);
            }
            client.executeSync(() -> {
                clientSourceCache = Collections.unmodifiableMap(map);
            });
        });
    }

    // -------------------------------------------------------------------------
    // Server-side broadcast methods
    // -------------------------------------------------------------------------

    /**
     * Server-side only, send the full sources map to a single player.
     */
    public static void sendSourcesBroadcast(ServerPlayerEntity player) {
        Map<String, List<String>> rawSources = ServerSourceRegistry.getAll();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(rawSources.size());
        for (String name : rawSources.keySet()) {
            List<String> urls = ServerSourceRegistry.resolveList(name);
            buf.writeString(name);
            buf.writeVarInt(urls.size());
            for (String url : urls) {
                buf.writeString(url);
            }
        }
        ServerPlayNetworking.send(player, SERVER_SOURCES_BROADCAST_PACKET_ID, buf);
    }

    /**
     * Server-side only, broadcast the full sources map to all connected players.
     * Local paths (starting with /) are resolved to HTTP URLs.
     */
    public static void broadcastSourcesToAll(MinecraftServer server) {
        Map<String, List<String>> rawSources = ServerSourceRegistry.getAll();
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(rawSources.size());
        for (String name : rawSources.keySet()) {
            List<String> urls = ServerSourceRegistry.resolveList(name);
            buf.writeString(name);
            buf.writeVarInt(urls.size());
            for (String url : urls) {
                buf.writeString(url);
            }
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, SERVER_SOURCES_BROADCAST_PACKET_ID, buf);
        }
    }
    
    /**
     * Server-side (integrated or dedicated) display packet receiver.
     */
    public static void registerDisplayUpdateReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(DISPLAY_BLOCK_UPDATE_PACKET_ID, new DisplayUpdateHandler());
        ServerPlayNetworking.registerGlobalReceiver(DISPLAY_PLAYBACK_STATE_PACKET_ID, new PlaybackStateHandler());
    }

    private static class DisplayUpdateHandler implements ServerPlayNetworking.PlayChannelHandler {

        @Override
        public void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
            decodeDisplayUpdatePacket(buf, (pos, nbt) -> {
                ServerWorld world = player.getServerWorld();
                world.getServer().executeSync(() -> {
                    if (world.getBlockEntity(pos) instanceof DisplayBlockEntity blockEntity) {
                        if (blockEntity.requiresOp() && !player.isCreative()) {
                            return;
                        }
                        blockEntity.readNbt(nbt);
                        blockEntity.markDirty();
                        world.markDirty(pos);
                        world.updateListeners(pos, blockEntity.getCachedState(), blockEntity.getCachedState(), Block.NOTIFY_ALL);
                    }
                });
            });
        }

    }

    private static class PlaybackStateHandler implements ServerPlayNetworking.PlayChannelHandler {

        @Override
        public void receive(MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender) {
            BlockPos pos = buf.readBlockPos();
            boolean inRange = buf.readBoolean();
            ServerWorld world = player.getServerWorld();
            world.getServer().executeSync(() -> handlePlaybackState(world, player, pos, inRange));
        }

    }

    private static void handlePlaybackState(ServerWorld world, ServerPlayerEntity player, BlockPos pos, boolean inRange) {
        PlaybackKey key = new PlaybackKey(world.getRegistryKey(), pos);
        Set<UUID> viewers = PLAYBACK_VIEWERS.computeIfAbsent(key, k -> new HashSet<>());
        boolean hadViewers = !viewers.isEmpty();
        if (inRange) {
            viewers.add(player.getUuid());
        } else {
            viewers.remove(player.getUuid());
        }
        if (viewers.isEmpty()) {
            PLAYBACK_VIEWERS.remove(key);
            if (hadViewers) {
                updatePlaybackPaused(world, pos, true);
            }
        } else if (!hadViewers) {
            updatePlaybackPaused(world, pos, false);
        } else if (inRange) {
            // A viewer was already tracked, but the entity may still be stuck
            // paused (e.g. stale playbackPaused in NBT after a chunk reload or
            // because the client-side toggle raced a server update). Reconcile
            // whenever we receive an in-range report for a paused display.
            // updatePlaybackPaused() no-ops when the state hasn't changed.
            updatePlaybackPaused(world, pos, false);
        }
    }

    public static void cleanupPlaybackViewers(MinecraftServer server) {
        for (var it = PLAYBACK_VIEWERS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            PlaybackKey key = entry.getKey();
            Set<UUID> viewers = entry.getValue();
            for (var uuidIt = viewers.iterator(); uuidIt.hasNext(); ) {
                UUID uuid = uuidIt.next();
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null || player.getWorld().getRegistryKey() != key.world()) {
                    uuidIt.remove();
                }
            }
            if (viewers.isEmpty()) {
                it.remove();
                ServerWorld world = server.getWorld(key.world());
                if (world != null) {
                    updatePlaybackPaused(world, key.pos(), true);
                }
            }
        }
    }

    private static void updatePlaybackPaused(ServerWorld world, BlockPos pos, boolean paused) {
        if (world.getBlockEntity(pos) instanceof DisplayBlockEntity blockEntity) {
            if (blockEntity.isPlaybackPaused() != paused) {
                blockEntity.setPlaybackPaused(paused);
                blockEntity.markDirty();
                world.updateListeners(pos, blockEntity.getCachedState(), blockEntity.getCachedState(), Block.NOTIFY_ALL);
            }
        }
    }

    /**
     * Client-side only, decide whether a playback state packet should be sent.
     * The packet is sent when the in-range state changes, and additionally as a
     * periodic self-heal resync while the player is in range but the display is
     * still paused (see {@link #handlePlaybackState}).
     */
    public static boolean shouldSendPlaybackRangeUpdate(RegistryKey<World> world, BlockPos pos, boolean inRange, boolean displayPaused) {
        PlaybackKey key = new PlaybackKey(world, pos);
        Boolean previous = CLIENT_PLAYBACK_RANGE.get(key);
        Long sentAt = CLIENT_PLAYBACK_SENT_AT.get(key);
        long now = System.nanoTime();
        if (previous == null || previous != inRange) {
            CLIENT_PLAYBACK_RANGE.put(key, inRange);
            CLIENT_PLAYBACK_SENT_AT.put(key, now);
            return true;
        }
        // Self-heal: re-register presence while the display is stuck paused so
        // the server can reconcile it. Rate-limited to avoid packet spam.
        if (inRange && displayPaused && (sentAt == null || now - sentAt >= 2_000_000_000L)) {
            CLIENT_PLAYBACK_SENT_AT.put(key, now);
            return true;
        }
        return false;
    }

    private static record PlaybackKey(RegistryKey<World> world, BlockPos pos) { }
	
}