package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Renders an MSR tier badge as a second floating text line above
 * the player's name tag, visible only to other MSR mod users.
 */
@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {

    @Inject(
            method = "renderLabelIfPresent",
            at = @At("HEAD")
    )
    private void msrRenderBadge(
            PlayerEntityRenderState state,
            Text text,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            CallbackInfo ci
    ) {
        try {
            String username = state.name;
            if (username == null || username.isBlank()) return;

            Optional<PlayerTier> opt = TierRegistry.getByUsername(username);
            if (opt.isEmpty()) return;

            PlayerTier player = opt.get();

            boolean uuidKnown = player.uuid() != null && !player.uuid().startsWith("PASTE");
            if (uuidKnown && !MsrNetwork.isPeer(player.uuid())) return;

            MutableText badge = TierRegistry.buildBadge(player);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.textRenderer == null) return;
            TextRenderer tr = client.textRenderer;

            matrices.push();

            // Shift up past the vanilla nametag.
            // The vanilla nametag renders at roughly 0.5 blocks above the head.
            // We push 0.3 blocks higher so the badge sits just above the username.
            matrices.translate(0.0, 0.3, 0.0);

            // Minecraft's nametag renderer uses a tiny scale.
            float scale = 0.025f;
            matrices.scale(-scale, -scale, scale);

            Matrix4f matrix = matrices.peek().getPositionMatrix();
            float xOffset   = -(tr.getWidth(badge) / 2.0f);

            // Draw with background (the semi-transparent panel behind nametags)
            tr.draw(
                    badge,
                    xOffset, 0f,
                    0xFFFFFF,
                    false,
                    matrix,
                    vertexConsumers,
                    TextRenderer.TextLayerType.NORMAL,
                    0x40000000, // dark translucent background
                    light
            );

            matrices.pop();

        } catch (Exception e) {
            // Silent — never crash for a cosmetic
        }
    }
}
