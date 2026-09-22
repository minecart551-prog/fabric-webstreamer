package fr.theorozier.webstreamer.protocol;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Protocol version negotiation for backward compatibility.
 *
 * <p>When a client connects, both sides exchange version information.
 * If both support protobuf, they switch to the new protocol. Otherwise,
 * they continue using the legacy NBT packets.</p>
 *
 * <p><b>Backward compatibility guarantee:</b> Old clients that don't
 * send a version handshake are treated as legacy. The server continues
 * using NBT packets for them. Old servers that don't understand the
 * version handshake ignore it and continue using NBT packets.</p>
 */
public final class ProtocolVersion {

    /** Current protocol version. Increment when the schema changes. */
    public static final int CURRENT_VERSION = 1;

    /** The mod version this protocol was introduced in. */
    public static final String MOD_VERSION = "1.6.0";

    /** The Minecraft version this mod targets. */
    public static final String MC_VERSION = "1.20.1";

    /** Channel ID for the new protobuf protocol. */
    public static final String PROTO_CHANNEL = "webstreamer:proto";

    /** Channel ID for the legacy version handshake (sent on old channels). */
    public static final String HANDSHAKE_CHANNEL = "webstreamer:handshake";

    /** How long to wait for a version handshake response (ms). */
    public static final long HANDSHAKE_TIMEOUT_MS = 3000;

    private ProtocolVersion() {}

    /**
     * Check if a remote version is compatible with ours.
     * Two versions are compatible if they have the same major version.
     */
    public static boolean isCompatible(int remoteVersion) {
        return remoteVersion / 100 == CURRENT_VERSION / 100;
    }

    /**
     * Create a version handshake payload.
     * @return The version number to send.
     */
    public static int createHandshake() {
        return CURRENT_VERSION;
    }

    /**
     * Parse a version handshake from the remote side.
     * @param data The raw handshake data.
     * @return The protocol version, or -1 if invalid.
     */
    public static int parseHandshake(byte[] data) {
        if (data == null || data.length < 4) return -1;
        // Simple big-endian int32
        return ((data[0] & 0xFF) << 24) |
               ((data[1] & 0xFF) << 16) |
               ((data[2] & 0xFF) << 8) |
               (data[3] & 0xFF);
    }

    /**
     * Serialize a version to bytes.
     */
    public static byte[] serialize(int version) {
        return new byte[] {
            (byte) (version >> 24),
            (byte) (version >> 16),
            (byte) (version >> 8),
            (byte) version
        };
    }

    /**
     * State of protocol negotiation for a connection.
     */
    @Environment(EnvType.CLIENT)
    public enum NegotiationState {
        /** Waiting for server response. */
        PENDING,
        /** Server supports protobuf. */
        NEGOTIATED,
        /** Server is legacy, use NBT packets. */
        LEGACY,
        /** Handshake timed out, use NBT packets. */
        TIMEOUT
    }
}
