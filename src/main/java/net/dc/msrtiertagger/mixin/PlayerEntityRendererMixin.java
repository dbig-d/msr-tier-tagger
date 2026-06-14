package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.TierRegistry;
import net.minecraft.client.MinecraftClient;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.MutableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.UUID;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V",
            at = @At("TAIL")
    )
    private void msrModifyNametag(
            PlayerLikeEntity player,
            PlayerEntityRenderState state,
            float tickDelta,
            CallbackInfo ci
    ) {
        try {
            if (state.displayName == null) return;

            UUID uuid = player.getUuid();
            MinecraftClient mc = MinecraftClient.getInstance();

            // 1) Try the data.json UUID directly.
            Optional<PlayerTier> opt = uuid != null
                    ? TierRegistry.getByUuid(uuid.toString())
                    : Optional.empty();

            // 2) Resolve via the entity's OWN GameProfile — the identity Minecraft
            //    uses to render the player's skin. PvP servers (mcpvp, oceania) hand
            //    opponents an entity UUID that doesn't match data.json AND strip the
            //    username out of the visible nametag, so the UUID lookup, the
            //    text scan, and even the tab-list lookup (keyed by that same spoofed
            //    UUID) all miss — leaving only your own clean nametag resolving. The
            //    entity's GameProfile still carries the real Mojang username (that's
            //    how the correct skin renders), so match on it.
            if (opt.isEmpty() && player instanceof net.minecraft.entity.player.PlayerEntity pe) {
                com.mojang.authlib.GameProfile profile = pe.getGameProfile();
                if (profile != null) {
                    if (profile.name() != null && !profile.name().isBlank()) {
                        opt = TierRegistry.getByUsername(profile.name());
                    }
                    if (opt.isEmpty() && profile.id() != null) {
                        opt = TierRegistry.getByUuid(profile.id().toString());
                    }
                }
            }

            // 3) The tab-list GameProfile, keyed by the entity UUID (works on servers
            //    that keep the real UUID but wrap the visible nametag).
            if (opt.isEmpty() && uuid != null && mc != null && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
                if (entry != null && entry.getProfile() != null) {
                    opt = TierRegistry.getByUsername(entry.getProfile().name());
                }
            }

            // 4) Last resort: scan the wrapped nametag text for a known username token.
            if (opt.isEmpty()) {
                opt = TierRegistry.scanForPlayer(state.displayName.getString());
            }
            if (opt.isEmpty()) return; // not a ranked player — leave the nametag as-is

            PlayerTier tier = opt.get();

            // Show the gamemode currently being played (detected from OUR inventory)
            // for EVERYONE, not just the local player: on a duel/match server every
            // player you see is in the same mode, so that mode's tier is the right
            // thing to show on their nametag. In a lobby getCurrentGamemode() is null
            // and everyone falls back to their overall #rank. Mirrors the chat line.
            String gamemode = net.dc.msrtiertagger.data.GamemodeDetector.getCurrentGamemode();

            // Render the canonical MSR name, coloured by the player's highest-priority
            // badge (developer/tester/subtester/retired/premium) — or plain white if
            // they hold none. buildName sets an explicit colour on the name, which also
            // breaks style inheritance so it never picks up the tier colour. Rebuilt
            // every frame so the developer red/white gradient animates.
            MutableText name = TierRegistry.buildName(tier);

            // Put the badge on the RIGHT of the name (e.g. "__bigd | 🗡 HT1").
            // Other mods (e.g. Essentials) draw their own icon to the LEFT of the
            // nametag; keeping our badge on the right avoids colliding with it.
            MutableText badgeCore = TierRegistry.buildBadgeCore(tier, gamemode);
            state.displayName = name
                    .append(TierRegistry.separatorText())
                    .append(badgeCore);

        } catch (Exception e) {
            // never crash for cosmetics
        }
    }
}