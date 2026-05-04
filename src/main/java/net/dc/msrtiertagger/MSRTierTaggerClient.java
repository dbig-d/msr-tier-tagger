package net.dc.msrtiertagger;

import net.dc.msrtiertagger.data.GamemodeDetector;
import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.item.Items;

/**
 * Client-side entrypoint for MSR Tier Tagger.
 *
 * Inventory state machine:
 *
 *   LOBBY     -> inventory matches lobby kit (iron sword, nether star, etc.)
 *   CLEARING  -> inventory went empty / non-lobby (server clearing for kit load)
 *   WAITING   -> countdown ticks until we detect the loaded kit
 *   IN_MATCH  -> gamemode has been identified
 *
 * Transitions:
 *   LOBBY -> CLEARING  when lobby items disappear
 *   CLEARING -> WAITING  when inventory becomes non-empty again (kit loading)
 *   WAITING -> IN_MATCH  when countdown reaches 0 and detect() runs
 *   any -> LOBBY  when lobby items reappear
 */
public class MSRTierTaggerClient implements ClientModInitializer {

    public static final String TIER_JSON_URL =
            "https://raw.githubusercontent.com/dbig-d/msr-tier-tagger/master/msr_tiers.json";

    private enum State { LOBBY, CLEARING, WAITING, IN_MATCH }

    private static State state       = State.LOBBY;
    private static int   countdown   = -1;
    private static int   tickCounter = 0; // for periodic debug logging

    @Override
    public void onInitializeClient() {
        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client initialising...");

        PayloadTypeRegistry.playC2S().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );

        MsrNetwork.register();

        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                TierRegistry.fetchAsync(TIER_JSON_URL)
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.execute(() -> {
                    MsrNetwork.sendHandshake(client.player.getUuidAsString());
                    MSRTierTagger.LOGGER.info("[MSR DEBUG] === Tier data loaded: {} players ===",
                            TierRegistry.getPlayerCount());
                    TierRegistry.logAllTiers();
                });
            }
            state     = State.LOBBY;
            countdown = -1;
            GamemodeDetector.setGamemode(null);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MsrNetwork.clearPeers();
            GamemodeDetector.setGamemode(null);
            state = State.LOBBY;
        });

        // ── Inventory state machine tick ──────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            tickCounter++;

            boolean inLobby    = GamemodeDetector.isInLobby(client.player);
            boolean hasItems   = GamemodeDetector.hasAnyItems(client.player);

            // Always transition to LOBBY if lobby kit detected
            if (inLobby && state != State.LOBBY) {
                MSRTierTagger.LOGGER.info("[MSR DEBUG] Lobby detected — returned to lobby.");
                state = State.LOBBY;
                countdown = -1;
                GamemodeDetector.setGamemode(null);
                return;
            }

            switch (state) {
                case LOBBY -> {
                    if (!inLobby) {
                        // Left lobby — inventory is clearing or kit loading
                        MSRTierTagger.LOGGER.info(
                                "[MSR DEBUG] Left lobby (inLobby=false, hasItems={}). -> CLEARING",
                                hasItems);
                        state = State.CLEARING;
                    }
                }

                case CLEARING -> {
                    if (inLobby) {
                        // Went back to lobby (false alarm)
                        state = State.LOBBY;
                    } else if (hasItems) {
                        // Items appeared — kit is loading, start countdown
                        MSRTierTagger.LOGGER.info(
                                "[MSR DEBUG] Items appeared after clear. Starting 3s detection countdown.");
                        state     = State.WAITING;
                        countdown = 20; // 1 second at 20 ticks/sec
                    }
                    // if still empty, stay in CLEARING
                }

                case WAITING -> {
                    if (inLobby) {
                        state = State.LOBBY;
                        countdown = -1;
                        return;
                    }
                    countdown--;
                    // Log every 20 ticks (1 sec) so we can see the countdown
                    if (countdown % 20 == 0) {
                        MSRTierTagger.LOGGER.info(
                                "[MSR DEBUG] Detection countdown: {}t remaining", countdown);
                    }
                    if (countdown <= 0) {
                        MSRTierTagger.LOGGER.info("[MSR DEBUG] Running gamemode detection...");
                        GamemodeDetector.dumpInventory(client.player);
                        String detected = GamemodeDetector.detect(client.player);
                        GamemodeDetector.setGamemode(detected);
                        state     = State.IN_MATCH;
                        countdown = -1;
                    }
                }

                case IN_MATCH -> {
                    // Nothing to do — waiting for return to lobby
                }
            }
        });

        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client ready.");
    }
}