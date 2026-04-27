package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.util.Colors;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Renders an MSR tier badge above the player's vanilla name tag.
 * Uses the 1.21.11 OrderedRenderCommandQueue rendering API.
 * Only visible to other players who also have the mod installed.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD")
    )
    private void msrRenderBadge(
            PlayerEntityRenderState state,
            MatrixStack matrices,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraRenderState,
            CallbackInfo ci
    ) {
        try {
            // state.displayName is a Text object in 1.21.11
            if (state.displayName == null) return;
            String username = state.displayName.getString();
            if (username == null || username.isBlank()) return;

            Optional<PlayerTier> opt = TierRegistry.getByUsername(username);
            if (opt.isEmpty()) return;

            PlayerTier player = opt.get();

            // Only show badge if the player has confirmed they have the mod
            boolean uuidKnown = player.uuid() != null && !player.uuid().startsWith("PASTE");
            if (uuidKnown && !MsrNetwork.isPeer(player.uuid())) return;

            MutableText badge = TierRegistry.buildBadge(player);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.textRenderer == null) return;
            TextRenderer tr = client.textRenderer;

            matrices.push();

            // Shift up above the vanilla nametag
            matrices.translate(0.0, 0.3, 0.0);

            float scale = 0.025f;
            matrices.scale(-scale, -scale, scale);

            float xOffset = -(tr.getWidth(badge) / 2.0f);

            // 1.21.11 uses queue.submitText() instead of tr.draw() directly
            queue.submitText(
                    matrices,
                    xOffset, 0f,
                    badge.asOrderedText(),
                    false,
                    TextRenderer.TextLayerType.NORMAL,
                    state.light,            // lightmap coordinates from render state
                    Colors.WHITE,           // text colour (our badge has its own colour)
                    0,                      // background alpha (0 = transparent)
                    Colors.BLACK            // shadow colour
            );

            matrices.pop();

        } catch (Exception e) {
            // Never crash the client for a cosmetic feature
        }
    }
}
