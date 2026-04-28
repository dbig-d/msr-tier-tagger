package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.PlayerLikeEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

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

            String username = state.displayName.getString();
            if (username == null || username.isBlank()) return;

            Optional<PlayerTier> opt = TierRegistry.getByUsername(username);

            if (opt.isPresent()) {
                MutableText badge = TierRegistry.buildBadge(opt.get());
                state.displayName = badge.append(state.displayName);
                return;
            }

            // [?] for players not in JSON — comment out these 2 lines when done testing
            MutableText unknown = Text.literal("[?] ")
                    .setStyle(Style.EMPTY.withColor(Formatting.DARK_GRAY).withItalic(true));
            state.displayName = unknown.append(state.displayName);

        } catch (Exception e) {
            // never crash for cosmetics
        }
    }
}