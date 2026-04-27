package net.dc.msrtiertagger.network;

import net.dc.msrtiertagger.MSRTierTagger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the MSR peer discovery handshake using the 1.21.1 payload API.
 *
 * In 1.21.1+, custom packets require a registered CustomPayload type.
 * We define MsrHelloPayload as a record that carries the sender's UUID string,
 * register it with Fabric, then send/receive it on join/disconnect.
 */
public class MsrNetwork {

    // ── Payload type ──────────────────────────────────────────────────────────

    /**
     * The custom payload record for the MSR handshake.
     * Carries the sender's UUID string so receivers can add them to the peer set.
     */
    public record MsrHelloPayload(String uuid) implements CustomPayload {

        // The unique ID for this payload type
        public static final CustomPayload.Id<MsrHelloPayload> ID =
                new CustomPayload.Id<>(Identifier.of(MSRTierTagger.MOD_ID, "hello"));

        // Codec tells Fabric how to read/write this payload over the network
        public static final PacketCodec<PacketByteBuf, MsrHelloPayload> CODEC =
                PacketCodec.of(
                        (payload, buf) -> buf.writeString(payload.uuid()),   // encoder
                        buf -> new MsrHelloPayload(buf.readString())          // decoder
                );

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ── Peer tracking ─────────────────────────────────────────────────────────

    // UUIDs of players on this server confirmed to have the mod installed
    private static final Set<String> CONFIRMED_PEERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── Registration ─────────────────────────────────────────────────────────

    /**
     * Called from MSRTierTaggerClient.onInitializeClient().
     * Registers the payload type and the receiver handler.
     */
    public static void register() {
        // Register the receiver — runs when another MSR mod user sends us a hello
        ClientPlayNetworking.registerGlobalReceiver(
                MsrHelloPayload.ID,
                (payload, context) -> {
                    // context.client().execute() ensures we're on the main thread
                    context.client().execute(() -> {
                        CONFIRMED_PEERS.add(payload.uuid().toLowerCase());
                        MSRTierTagger.LOGGER.debug("[MSR] Confirmed peer: {}", payload.uuid());
                    });
                }
        );

        MSRTierTagger.LOGGER.info("[MSR] Network payload registered.");
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    /**
     * Send our UUID to everyone else on the server running this mod.
     * Called once from MSRTierTaggerClient when joining a server.
     */
    public static void sendHandshake(String myUuid) {
        try {
            ClientPlayNetworking.send(new MsrHelloPayload(myUuid));
            MSRTierTagger.LOGGER.info("[MSR] Handshake sent (UUID: {})", myUuid);
        } catch (Exception e) {
            // Safe to ignore — throws if not connected or channel not open yet
            MSRTierTagger.LOGGER.debug("[MSR] Could not send handshake: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static boolean isPeer(String uuid) {
        if (uuid == null) return false;
        return CONFIRMED_PEERS.contains(uuid.toLowerCase());
    }

    public static void clearPeers() {
        int count = CONFIRMED_PEERS.size();
        CONFIRMED_PEERS.clear();
        MSRTierTagger.LOGGER.debug("[MSR] Cleared {} peers on disconnect.", count);
    }
}
