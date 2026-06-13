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

            // Resolve the player by UUID first. Servers (e.g. mcpvp.club) wrap the
            // nametag with team/rank prefixes, so the display-name string is often
            // NOT the bare username — which is why a name-only lookup showed [?]
            // even for ranked players. The client always knows the real UUID, so we
            // match on that and only fall back to the name for entries without one.
            UUID uuid = player.getUuid();
            Optional<PlayerTier> opt = uuid != null
                    ? TierRegistry.getByUuid(uuid.toString())
                    : Optional.empty();
            if (opt.isEmpty()) {
                // Resolve by name from the wrapped nametag text. Two modded players
                // standing next to each other used to see only plain white names on
                // each other: mcpvp.club assigns remote players an entity UUID that
                // doesn't match data.json (the local player resolves fine, so it's a
                // remote-UUID issue), AND the nametag is wrapped with team/rank
                // prefixes — so a whole-string getByUsername never matched either.
                // Scanning the wrapped text for a known username token fixes both.
                String shown = state.displayName.getString();
                opt = TierRegistry.scanForPlayer(shown);
            }
            if (opt.isEmpty()) return; // not a ranked player — leave the nametag as-is

            PlayerTier tier = opt.get();

            // Use gamemode-specific tier for the local player, overall for everyone else.
            MinecraftClient mc = MinecraftClient.getInstance();
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