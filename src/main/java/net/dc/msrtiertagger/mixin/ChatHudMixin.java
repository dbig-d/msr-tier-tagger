package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.dc.msrtiertagger.network.MsrNetwork;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Intercepts chat messages before they are displayed and prepends
 * the MSR tier badge for known ranked players.
 *
 * The badge only appears if:
 *   1. The sender's username matches an entry in TierRegistry.
 *   2. The sender's UUID was received via the MSR handshake
 *      (confirming they also have the mod installed).
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {

	@ModifyVariable(
			method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"),
			argsOnly = true
	)
	private Text msrPrependBadge(Text original) {
		try {
			// Extract sender name from the Style insertion field.
			// In vanilla chat, the player name component has an insertion = username.
			String sender = extractSender(original);
			if (sender == null) return original;

			Optional<PlayerTier> opt = TierRegistry.getByUsername(sender);
			if (opt.isEmpty()) return original;

			PlayerTier player = opt.get();

			// Require the sender to have confirmed they have the mod
			// via the handshake, unless UUID hasn't been filled in yet.
			boolean uuidKnown = player.uuid() != null && !player.uuid().startsWith("PASTE");
			if (uuidKnown && !MsrNetwork.isPeer(player.uuid())) {
				return original;
			}

			// Prepend badge:  [HT1👑] <PlayerName> message
			MutableText badge = TierRegistry.buildBadge(player);
			return badge.append(original);

		} catch (Exception e) {
			// Never crash the client for a cosmetic feature
			return original;
		}
	}

	/**
	 * Walk the Text component tree looking for a sibling with a Style
	 * insertion set — vanilla uses this for the clickable player name.
	 */
	private static String extractSender(Text text) {
		// Check root insertion
		if (text.getStyle().getInsertion() != null) {
			return text.getStyle().getInsertion();
		}
		// Walk siblings
		for (Text sibling : text.getSiblings()) {
			String ins = sibling.getStyle().getInsertion();
			if (ins != null && !ins.isBlank()) return ins;
			// Recurse one level
			for (Text child : sibling.getSiblings()) {
				String cins = child.getStyle().getInsertion();
				if (cins != null && !cins.isBlank()) return cins;
			}
		}
		return null;
	}
}
