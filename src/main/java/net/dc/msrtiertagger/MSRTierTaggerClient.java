package net.dc.msrtiertagger;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Client-side entrypoint for MSR Tier Tagger.
 */
public class MSRTierTaggerClient implements ClientModInitializer {

    /**
     * Raw GitHub URL for msr_tiers.json.
     * After pushing the file to your repo, this URL is already correct
     * assuming your GitHub username is dbig-d.
     * If not, replace dbig-d with your actual GitHub username.
     */
    public static final String TIER_JSON_URL =
            "https://raw.githubusercontent.com/dbig-d/msr-tier-tagger/main/msr_tiers.json";

    @Override
    public void onInitializeClient() {
        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client initialising...");

        // Step 1: Register the payload type with Fabric's type registry FIRST.
        // This must happen before registering the receiver or sending anything.
        // PLAY_C2S = client-to-server direction (we send to server, server fans out)
        // Since mcpvp.club won't have our mod, we register both directions
        // so the client can at least send without crashing.
        PayloadTypeRegistry.playC2S().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );

        // Step 2: Register the network receiver
        MsrNetwork.register();

        // Step 3: Fetch tier data from GitHub when the game starts
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                TierRegistry.fetchAsync(TIER_JSON_URL)
        );

        // Step 4: When joining a server, announce ourselves to other MSR users
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                // Small delay to ensure the connection is fully established
                client.execute(() ->
                        MsrNetwork.sendHandshake(client.player.getUuidAsString())
                );
            }
        });

        // Step 5: Clean up when leaving a server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                MsrNetwork.clearPeers()
        );

        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client ready.");
    }
}
