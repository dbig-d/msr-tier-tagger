package net.dc.msrtiertagger.network;

import net.dc.msrtiertagger.MSRTierTagger;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the MSR peer discovery handshake.
 *
 * When you join a server this mod sends a small custom packet on the
 * "msr-tier-tagger:hello" channel containing your UUID.
 * Any other player running this mod receives that packet and adds
 * your UUID to their confirmed-peers set.
 */
public class MsrNetwork {

    // ── Payload record ────────────────────────────────────────────────────────

    /**
     * CustomPayload implementation for the MSR hello packet.
     * Carries just the sender's UUID string.
     */
    public record HelloPayload(String uuid) implements CustomPayload {

        public static final CustomPayload.Id<HelloPayload> ID =
                new CustomPayload.Id<>(Identifier.of(MSRTierTagger.MOD_ID, "hello"));

        public static final PacketCodec<PacketByteBuf, HelloPayload> CODEC =
                PacketCodecs.STRING
                        .xmap(HelloPayload::new, HelloPayload::uuid)
                        .cast();

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ── Peer tracking ─────────────────────────────────────────────────────────

    private static final Set<String> CONFIRMED_PEERS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── Registration ─────────────────────────────────────────────────────────

    public static void register() {
        // Register the payload type so Fabric knows how to encode/decode it
        PayloadTypeRegistry.playC2S().register(HelloPayload.ID, HelloPayload.CODEC);

        // Listen for hello packets from other MSR mod users
        ClientPlayNetworking.registerGlobalReceiver(HelloPayload.ID,
                (payload, context) -> {
                    String peerUuid = payload.uuid();
                    CONFIRMED_PEERS.add(peerUuid.toLowerCase());
                    MSRTierTagger.LOGGER.debug("[MSR] Confirmed peer: {}", peerUuid);
                }
        );

        MSRTierTagger.LOGGER.info("[MSR] Network channel registered.");
    }

    // ── Handshake send ────────────────────────────────────────────────────────

    /**
     * Send our UUID to everyone else on the server running this mod.
     * Called once on server join.
     */
    public static void sendHandshake(String myUuid) {
        try {
            ClientPlayNetworking.send(new HelloPayload(myUuid));
            MSRTierTagger.LOGGER.info("[MSR] Handshake sent (UUID: {})", myUuid);
        } catch (Exception e) {
            MSRTierTagger.LOGGER.warn("[MSR] Could not send handshake: {}", e.getMessage());
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