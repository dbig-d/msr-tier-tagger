package net.dc.msrtiertagger;

import net.dc.msrtiertagger.data.GamemodeDetector;
import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side entrypoint for MSR Tier Tagger.
 *
 * In addition to tier fetching and networking, this class runs an
 * inventory watcher every tick that detects when the player leaves
 * the lobby (inventory changes from the known lobby kit) and schedules
 * a gamemode detection 3 seconds later once the kit has fully loaded.
 */
public class MSRTierTaggerClient implements ClientModInitializer {

    public static final String TIER_JSON_URL =
            "https://raw.githubusercontent.com/dbig-d/msr-tier-tagger/master/msr_tiers.json";

    // Inventory watcher state
    private static List<ItemStack> lastInventorySnapshot = new ArrayList<>();
    private static boolean wasInLobby = false;
    private static int detectCountdown = -1; // ticks remaining until gamemode detect runs

    @Override
    public void onInitializeClient() {
        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client initialising...");

        // Register payload types before anything else
        PayloadTypeRegistry.playC2S().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                MsrNetwork.MsrHelloPayload.ID,
                MsrNetwork.MsrHelloPayload.CODEC
        );

        // Register network receiver
        MsrNetwork.register();

        // Fetch tier data from GitHub on startup
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                TierRegistry.fetchAsync(TIER_JSON_URL)
        );

        // Send handshake on server join + print debug tier list
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.player != null) {
                client.execute(() -> {
                    MsrNetwork.sendHandshake(client.player.getUuidAsString());
                    // Debug: print all loaded tiers on server join
                    MSRTierTagger.LOGGER.info("[MSR DEBUG] === Tier data loaded: {} players ===",
                            TierRegistry.getPlayerCount());
                    TierRegistry.logAllTiers();
                });
            }
            // Reset gamemode on each new server connection
            GamemodeDetector.setGamemode(null);
            wasInLobby = false;
            lastInventorySnapshot.clear();
            detectCountdown = -1;
        });

        // Clean up on disconnect
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            MsrNetwork.clearPeers();
            GamemodeDetector.setGamemode(null);
        });

        // ── Inventory watcher tick ────────────────────────────────────────────
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Countdown to gamemode detection after lobby inventory change
            if (detectCountdown > 0) {
                detectCountdown--;
                if (detectCountdown == 0) {
                    GamemodeDetector.dumpInventory(client.player);
                    String detected = GamemodeDetector.detect(client.player);
                    GamemodeDetector.setGamemode(detected);
                    detectCountdown = -1;
                }
                return;
            }

            boolean inLobby = GamemodeDetector.isInLobby(client.player);

            // Take a snapshot of the current inventory for change detection
            List<ItemStack> currentSnapshot = snapshotInventory(client.player);

            if (inLobby) {
                // Player is in lobby — record this state and take snapshot
                if (!wasInLobby) {
                    MSRTierTagger.LOGGER.info("[MSR DEBUG] Lobby detected — inventory matches lobby kit.");
                }
                wasInLobby = true;
                lastInventorySnapshot = currentSnapshot;
                // Clear gamemode while in lobby
                if (GamemodeDetector.getCurrentGamemode() != null) {
                    GamemodeDetector.setGamemode(null);
                }
            } else if (wasInLobby) {
                // Was in lobby, inventory just changed — not in lobby anymore
                // Check if inventory actually changed (not just a transient tick)
                if (!inventoriesMatch(currentSnapshot, lastInventorySnapshot)) {
                    MSRTierTagger.LOGGER.info("[MSR] Left lobby — scheduling gamemode detection in 3s.");
                    wasInLobby = false;
                    // 3 seconds at 20 ticks/second = 60 ticks
                    detectCountdown = 60;
                }
            }
        });

        MSRTierTagger.LOGGER.info("[MSR Tier Tagger] Client ready.");
    }

    // ── Snapshot helpers ──────────────────────────────────────────────────────

    private static List<ItemStack> snapshotInventory(net.minecraft.client.network.ClientPlayerEntity player) {
        List<ItemStack> snapshot = new ArrayList<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            snapshot.add(inv.getStack(i).copy());
        }
        return snapshot;
    }

    private static boolean inventoriesMatch(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            ItemStack sa = a.get(i);
            ItemStack sb = b.get(i);
            if (!ItemStack.areItemsEqual(sa, sb)) return false;
            if (sa.getCount() != sb.getCount()) return false;
        }
        return true;
    }
}