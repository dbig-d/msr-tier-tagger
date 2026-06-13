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

            // 2) Resolve by the player's REAL profile name from the tab list. PvP
            //    servers (mcpvp, oceania) replace opponents' nametags with team /
            //    scoreboard text that no longer contains the username, and hand them
            //    an entity UUID that doesn't match data.json — so neither the UUID
            //    lookup nor scanning the visible name found them, and only your own
            //    (clean) nametag ever resolved. The tab-list GameProfile keeps the
            //    true Mojang username no matter how the nametag is rendered.
            if (opt.isEmpty() && uuid != null && mc != null && mc.getNetworkHandler() != null) {
                var entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
                if (entry != null && entry.getProfile() != null) {
                    opt = TierRegistry.getByUsername(entry.getProfile().name());
                }
            }

            // 3) Last resort: scan the wrapped nametag text for a known username token.
            if (opt.isEmpty()) {
                opt = TierRegistry.scanForPlayer(state.displayName.getString());
            }
            if (opt.isEmpty()) return; // not a ranked player — leave the nametag as-is

            PlayerTier tier = opt.get();

            // Use gamemode-specific tier for the local player, overall for everyone else.
            boolean isLocalPlayer = mc != null && mc.player != null
                    && uuid != null && mc.player.getUuid().equals(uuid);
            String gamemode = isLocalPlayer
                    ? net.dc.msrtiertagger.data.GamemodeDetector.getCurrentGamemode()
                    : null;

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