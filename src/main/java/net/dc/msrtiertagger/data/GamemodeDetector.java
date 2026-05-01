package net.dc.msrtiertagger.data;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.PlayerScreenHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects which MSR gamemode the local player is currently playing
 * by inspecting their inventory contents.
 *
 * Flow:
 *   1. isInLobby() checks for the 5 known lobby hotbar items.
 *   2. When the lobby inventory changes, MSRTierTaggerClient schedules
 *      a 3-second delayed call to detect().
 *   3. detect() returns a gamemode key string matching the keys in msr_tiers.json:
 *      "sword", "axe", "uhc", "mace", "crystal",
 *      "smp", "nsmp", "dpot", "npot", "hybrid"
 *      or null if unknown / still in lobby.
 */
public class GamemodeDetector {

    // The currently detected gamemode — null means lobby or unknown
    private static volatile String currentGamemode = null;

    public static String getCurrentGamemode() {
        return currentGamemode;
    }

    public static void setGamemode(String gamemode) {
        currentGamemode = gamemode;
        TierRegistry.LOGGER.info("[MSR] Gamemode detected: {}", gamemode != null ? gamemode : "lobby");
    }

    // ── Lobby detection ───────────────────────────────────────────────────────

    /**
     * Returns true if the player's hotbar matches the known lobby kit.
     * Slot numbers (0-indexed from PlayerInventory):
     *   Slot 0: Iron Sword    (hotbar slot 1)
     *   Slot 1: Nether Star   (hotbar slot 2)
     *   Slot 6: Book          (hotbar slot 7)
     *   Slot 7: Grindstone    (hotbar slot 8)
     *   Slot 8: Player Head   (hotbar slot 9)
     *
     * We check at least 3 of 5 to be resilient to minor kit differences.
     */
    public static boolean isInLobby(ClientPlayerEntity player) {
        try {
            var inv = player.getInventory();
            int matches = 0;
            if (inv.getStack(0).isOf(Items.IRON_SWORD))   matches++;
            if (inv.getStack(1).isOf(Items.NETHER_STAR))  matches++;
            if (inv.getStack(6).isOf(Items.BOOK))         matches++;
            if (inv.getStack(7).isOf(Items.GRINDSTONE))   matches++;
            if (inv.getStack(8).isOf(Items.PLAYER_HEAD))  matches++;
            return matches >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Dumps the full hotbar (slots 0-8) and armour slots to the log.
     * Called right before gamemode detection so you can see what items
     * are present and debug detection failures.
     */
    public static void dumpInventory(ClientPlayerEntity player) {
        try {
            var inv = player.getInventory();
            TierRegistry.LOGGER.info("[MSR DEBUG] === Inventory dump (3s after lobby change) ===");
            TierRegistry.LOGGER.info("[MSR DEBUG] Hotbar:");
            for (int i = 0; i < 9; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    TierRegistry.LOGGER.info("[MSR DEBUG]   Slot {}: {} x{}",
                            i, stack.getItem().toString(), stack.getCount());
                }
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] Main inventory (9-35):");
            for (int i = 9; i < 36; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    TierRegistry.LOGGER.info("[MSR DEBUG]   Slot {}: {} x{}",
                            i, stack.getItem().toString(), stack.getCount());
                }
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] Armour slots (36-39):");
            for (int i = 36; i < 40; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    TierRegistry.LOGGER.info("[MSR DEBUG]   Slot {}: {}",
                            i, stack.getItem().toString());
                }
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] === End inventory dump ===");
        } catch (Exception e) {
            TierRegistry.LOGGER.warn("[MSR DEBUG] Could not dump inventory: {}", e.getMessage());
        }
    }

    // ── Gamemode detection ────────────────────────────────────────────────────

    /**
     * Inspects the player's full inventory and returns the detected gamemode key,
     * or null if the inventory doesn't match any known kit.
     *
     * Checks are ordered from most-unique identifier to least-unique.
     */
    public static String detect(ClientPlayerEntity player) {
        try {
            Map<Item, Integer> counts = countItems(player);

            // 1. Crystal — only gamemode with End Crystals
            if (counts.getOrDefault(Items.END_CRYSTAL, 0) > 0) {
                return "crystal";
            }

            // 2. Mace — has a Mace, no End Crystals
            if (counts.getOrDefault(Items.MACE, 0) > 0) {
                return "mace";
            }

            // 3. UHC — has 8+ Honey Bottles (retextured as Golden Heads)
            if (counts.getOrDefault(Items.HONEY_BOTTLE, 0) >= 8) {
                return "uhc";
            }

            // 4. Axe — has Bow + Crossbow + exactly 6 arrows
            if (counts.getOrDefault(Items.BOW, 0) > 0
                    && counts.getOrDefault(Items.CROSSBOW, 0) > 0
                    && counts.getOrDefault(Items.ARROW, 0) == 6) {
                return "axe";
            }

            // 5. Sword — has Diamond Sword, no Diamond Axe, no Bow
            if (counts.getOrDefault(Items.DIAMOND_SWORD, 0) > 0
                    && counts.getOrDefault(Items.DIAMOND_AXE, 0) == 0
                    && counts.getOrDefault(Items.BOW, 0) == 0) {
                return "sword";
            }

            // 6. Netherite Pot — Netherite armour + Instant Health II splash potion, no axe
            if (hasNetheriteArmour(player)
                    && hasInstantHealthII(player)
                    && counts.getOrDefault(Items.DIAMOND_AXE, 0) == 0
                    && counts.getOrDefault(Items.NETHERITE_AXE, 0) == 0) {
                return "npot";
            }

            // 7. Diamond Pot — exactly 5 steaks (Cooked Beef)
            if (counts.getOrDefault(Items.COOKED_BEEF, 0) == 5) {
                return "dpot";
            }

            // 8. Hybrid SMP — Wind Charges + Cobwebs
            if (counts.getOrDefault(Items.WIND_CHARGE, 0) > 0
                    && counts.getOrDefault(Items.COBWEB, 0) > 0) {
                return "hybrid";
            }

            // 9. Diamond SMP — Diamond armour + Cobwebs + XP Bottles
            if (hasDiamondArmour(player)
                    && counts.getOrDefault(Items.COBWEB, 0) > 0
                    && counts.getOrDefault(Items.EXPERIENCE_BOTTLE, 0) > 0) {
                return "smp";
            }

            // 10. Netherite SMP — Netherite armour, no Cobwebs, no Wind Charges
            if (hasNetheriteArmour(player)
                    && counts.getOrDefault(Items.COBWEB, 0) == 0
                    && counts.getOrDefault(Items.WIND_CHARGE, 0) == 0) {
                return "nsmp";
            }

            return null; // unrecognised kit

        } catch (Exception e) {
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Build a map of Item -> total count across the full inventory. */
    private static Map<Item, Integer> countItems(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new HashMap<>();
        var inv = player.getInventory();
        // main inventory (0-35) + armour (36-39) + offhand (40)
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Returns true if the player has a Netherite chestplate equipped. */
    private static boolean hasNetheriteArmour(ClientPlayerEntity player) {
        var inv = player.getInventory();
        // Armour slots in PlayerInventory: 36=boots, 37=leggings, 38=chest, 39=helmet
        return inv.getStack(38).isOf(Items.NETHERITE_CHESTPLATE);
    }

    /** Returns true if the player has a Diamond chestplate equipped. */
    private static boolean hasDiamondArmour(ClientPlayerEntity player) {
        var inv = player.getInventory();
        return inv.getStack(38).isOf(Items.DIAMOND_CHESTPLATE);
    }

    /**
     * Returns true if any splash potion in the inventory has Instant Health II.
     * Checks both SPLASH_POTION and LINGERING_POTION item types.
     */
    private static boolean hasInstantHealthII(ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(Items.SPLASH_POTION) && !stack.isOf(Items.POTION)) continue;

            PotionContentsComponent contents =
                    stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;

            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value().equals(StatusEffects.INSTANT_HEALTH.value())
                        && effect.getAmplifier() >= 1) { // amplifier 1 = level II
                    return true;
                }
            }
        }
        return false;
    }
}