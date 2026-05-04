package net.dc.msrtiertagger.data;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.HashMap;
import java.util.Map;

public class GamemodeDetector {

    private static volatile String currentGamemode = null;

    public static String getCurrentGamemode() { return currentGamemode; }

    public static void setGamemode(String gamemode) {
        currentGamemode = gamemode;
        TierRegistry.LOGGER.info("[MSR] Gamemode set to: {}",
                gamemode != null ? gamemode : "null (lobby/unknown)");
    }

    // ── Lobby detection ───────────────────────────────────────────────────────
    // Lobby hotbar (0-indexed):
    //   Slot 0: Iron Sword
    //   Slot 1: Nether Star
    //   Slot 6: Book
    //   Slot 7: Grindstone
    //   Slot 8: Player Head
    // Requires 3/5 to match (resilient to minor kit changes)

    public static boolean isInLobby(ClientPlayerEntity player) {
        try {
            var inv = player.getInventory();
            int matches = 0;
            if (inv.getStack(0).isOf(Items.IRON_SWORD))  matches++;
            if (inv.getStack(1).isOf(Items.NETHER_STAR)) matches++;
            if (inv.getStack(6).isOf(Items.BOOK))        matches++;
            if (inv.getStack(7).isOf(Items.GRINDSTONE))  matches++;
            if (inv.getStack(8).isOf(Items.PLAYER_HEAD)) matches++;
            return matches >= 3;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean hasAnyItems(ClientPlayerEntity player) {
        try {
            var inv = player.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Gamemode detection ────────────────────────────────────────────────────
    // Checks ordered from most-unique identifier to least-unique.

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

            // 3. UHC — ONLY gamemode that starts with exactly 10 arrows
            if (counts.getOrDefault(Items.ARROW, 0) == 10) {
                return "uhc";
            }

            // 4. Axe — has Bow + Crossbow (arrows != 10 already excluded UHC above)
            if (counts.getOrDefault(Items.BOW, 0) > 0
                    && counts.getOrDefault(Items.CROSSBOW, 0) > 0) {
                return "axe";
            }

            // 5. Sword — Diamond Sword, no Diamond Axe, no Bow
            if (counts.getOrDefault(Items.DIAMOND_SWORD, 0) > 0
                    && counts.getOrDefault(Items.DIAMOND_AXE, 0) == 0
                    && counts.getOrDefault(Items.BOW, 0) == 0) {
                return "sword";
            }

            // 6. Netherite Pot — Netherite armour + Instant Health II, no axe
            if (hasNetheriteArmour(player)
                    && hasInstantHealthII(player)
                    && counts.getOrDefault(Items.DIAMOND_AXE, 0) == 0
                    && counts.getOrDefault(Items.NETHERITE_AXE, 0) == 0) {
                return "npot";
            }

            // 7. Diamond Pot — exactly 5 steaks
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

            return null;

        } catch (Exception e) {
            return null;
        }
    }

    // ── Inventory dump (debug) ────────────────────────────────────────────────

    public static void dumpInventory(ClientPlayerEntity player) {
        try {
            var inv = player.getInventory();
            TierRegistry.LOGGER.info("[MSR DEBUG] === Inventory dump ===");
            TierRegistry.LOGGER.info("[MSR DEBUG] Hotbar (slots 0-8):");
            for (int i = 0; i < 9; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty())
                    TierRegistry.LOGGER.info("[MSR DEBUG]   [{}] {} x{}",
                            i, stack.getItem(), stack.getCount());
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] Main inventory (slots 9-35):");
            for (int i = 9; i < 36; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty())
                    TierRegistry.LOGGER.info("[MSR DEBUG]   [{}] {} x{}",
                            i, stack.getItem(), stack.getCount());
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] Armour (slots 36-39):");
            for (int i = 36; i < 40; i++) {
                var stack = inv.getStack(i);
                if (!stack.isEmpty())
                    TierRegistry.LOGGER.info("[MSR DEBUG]   [{}] {}", i, stack.getItem());
            }
            TierRegistry.LOGGER.info("[MSR DEBUG] === End dump ===");
        } catch (Exception e) {
            TierRegistry.LOGGER.warn("[MSR DEBUG] Dump failed: {}", e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Map<Item, Integer> countItems(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty())
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private static boolean hasNetheriteArmour(ClientPlayerEntity player) {
        // Slot 38 = chestplate in PlayerInventory
        return player.getInventory().getStack(38).isOf(Items.NETHERITE_CHESTPLATE);
    }

    private static boolean hasDiamondArmour(ClientPlayerEntity player) {
        return player.getInventory().getStack(38).isOf(Items.DIAMOND_CHESTPLATE);
    }

    private static boolean hasInstantHealthII(ClientPlayerEntity player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!stack.isOf(Items.SPLASH_POTION) && !stack.isOf(Items.POTION)) continue;
            PotionContentsComponent contents = stack.get(DataComponentTypes.POTION_CONTENTS);
            if (contents == null) continue;
            for (var effect : contents.getEffects()) {
                if (effect.getEffectType().value().equals(StatusEffects.INSTANT_HEALTH.value())
                        && effect.getAmplifier() >= 1) {
                    return true;
                }
            }
        }
        return false;
    }
}