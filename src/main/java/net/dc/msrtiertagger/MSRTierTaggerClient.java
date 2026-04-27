package net.dc.msrtiertagger;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * Client-side entrypoint for MSR Tier Tagger.
 * Wires together: tier data fetching, network handshake, and cleanup on disconnect.
 */
public class MSRTierTaggerClient implements ClientModInitializer {

    /**
     * GitHub raw URL for the MSR tier JSON.
     * After pushing msr_tiers.json to your GitHub repo, paste the raw URL here.
     * Example: https://raw.githubusercontent.com/dbig-d/msr-tier-tagger/main/msr_tiers.json
     */
    public static final String TIER_JSON_URL =
            "https://raw.githubusercontent.com/dbig-d/msr-tier-tagger/main/msr_tiers.json";

    @Override
    public void onInitializeClient() {
        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client initialised.");

        // Register the MSR network channel so we can receive handshakes from peers
        MsrNetwork.register();

        // Fetch tier data from GitHub when the client starts up
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                TierRegistry.fetchAsync(TIER_JSON_URL)
        );

        // When joining a server, announce ourselves to other MSR mod users
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                MsrNetwork.sendHandshake(client.player.getUuidAsString());
            }
        });
        // Clean up peer list when leaving a server
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                MsrNetwork.clearPeers()
        );
    }
}