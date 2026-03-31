package fr.theorozier.webstreamer.display;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
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

	private static final Map<PlaybackKey, Set<UUID>> PLAYBACK_VIEWERS = new HashMap<>();
	private static final Map<PlaybackKey, Boolean> CLIENT_PLAYBACK_RANGE = new HashMap<>();

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
            if (DisplayBlock.canUse(player)) {
                decodeDisplayUpdatePacket(buf, (pos, nbt) -> {
                    ServerWorld world = player.getServerWorld();
                    world.getServer().executeSync(() -> {
                        if (world.getBlockEntity(pos) instanceof DisplayBlockEntity blockEntity) {
                            blockEntity.readNbt(nbt);
                            blockEntity.markDirty();
                            world.updateListeners(pos, blockEntity.getCachedState(), blockEntity.getCachedState(), Block.NOTIFY_ALL);
                        }
                    });
                });
            }
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

    public static boolean shouldSendPlaybackRangeUpdate(RegistryKey<World> world, BlockPos pos, boolean inRange) {
        PlaybackKey key = new PlaybackKey(world, pos);
        Boolean previous = CLIENT_PLAYBACK_RANGE.get(key);
        if (previous != null && previous == inRange) {
            return false;
        }
        CLIENT_PLAYBACK_RANGE.put(key, inRange);
        return true;
    }

    private static record PlaybackKey(RegistryKey<World> world, BlockPos pos) { }
	
}