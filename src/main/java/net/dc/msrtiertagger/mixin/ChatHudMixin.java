package net.dc.msrtiertagger.mixin;

import net.dc.msrtiertagger.data.GamemodeDetector;
import net.dc.msrtiertagger.data.TierRegistry;
import net.dc.msrtiertagger.data.TierRegistry.PlayerTier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Optional;

/**
 * Intercepts chat messages before they are displayed and rewrites the line for
 * known ranked players into the MSR format:
 *
 *     icon - tier/rank - colouredName: message
 *
 * Finding the sender is the tricky part. Vanilla tags the clickable name with a
 * Style {@code insertion} = username, but servers like mcpvp.club deliver chat as
 * pre-formatted system messages with no insertion at all — which is why the old
 * insertion-only lookup matched nothing and the badge never appeared. So we:
 *   1. walk the whole component tree for an insertion (vanilla / lenient servers), then
 *   2. fall back to scanning the rendered text for the first token that matches a
 *      ranked username in the registry (covers fully-formatted server chat).
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
			String raw = original.getString();
			if (raw == null || raw.isBlank()) return original;

			// Never touch interactive messages. Things like duel requests, party
			// invites and friend links carry clickEvents; rebuilding the line from
			// plain text would wipe them, turning the whole message white and
			// unclickable. Normal chat on these servers has no clickEvents, so it is
			// still reformatted — only clickable messages are left fully intact.
			if (hasClickEvent(original)) return original;

			// 1) Try the vanilla insertion (bare username on the clickable name).
			String token = extractSender(original);
			PlayerTier player = token != null
					? TierRegistry.getByUsername(token).orElse(null)
					: null;

			// 2) Fall back to scanning the visible text for a known ranked username.
			if (player == null) {
				token = scanForRankedToken(raw);
				if (token != null) player = TierRegistry.getByUsername(token).orElse(null);
			}
			if (player == null) return original; // not a ranked player's message

			// Locate the name in the visible text so we can split off the message body.
			// Prefer the canonical registry name; fall back to the matched token.
			int split = indexOfIgnoreCase(raw, player.name());
			String hit = player.name();
			if (split < 0 && token != null) {
				split = indexOfIgnoreCase(raw, token);
				hit = token;
			}
			if (split < 0) return original; // can't cleanly split — leave it untouched

			String body = stripLeadingSeparator(raw.substring(split + hit.length()));

			// Gamemode tier only makes sense for the local player (we read OUR inventory);
			// everyone else falls back to overall #rank.
			String gamemode = isLocalPlayer(player)
					? GamemodeDetector.getCurrentGamemode()
					: null;

			return TierRegistry.buildChatLine(player, gamemode, body);

		} catch (Exception e) {
			// Never crash the client for a cosmetic feature.
			return original;
		}
	}

	/** True if this ranked player is the local client player (by UUID, else name). */
	private static boolean isLocalPlayer(PlayerTier player) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || mc.player == null) return false;
		if (player.uuid() != null && !player.uuid().isBlank()
				&& mc.player.getUuid().toString().equalsIgnoreCase(player.uuid())) {
			return true;
		}
		return player.name().equalsIgnoreCase(mc.getSession().getUsername());
	}

	/** First whitespace-or-separator-delimited token that resolves to a ranked player. */
	private static String scanForRankedToken(String raw) {
		// Minecraft usernames are [A-Za-z0-9_]; split on everything else.
		for (String tok : raw.split("[^A-Za-z0-9_]+")) {
			if (tok.length() < 3) continue; // names are >= 3 chars
			if (TierRegistry.getByUsername(tok).isPresent()) return tok;
		}
		return null;
	}

	/** Drops a leading run of whitespace + one chat separator (: > » - |) + whitespace. */
	private static String stripLeadingSeparator(String s) {
		int i = 0;
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		if (i < s.length() && ":>»-|]".indexOf(s.charAt(i)) >= 0) i++;
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		return s.substring(i);
	}

	private static int indexOfIgnoreCase(String haystack, String needle) {
		if (needle == null || needle.isEmpty()) return -1;
		return haystack.toLowerCase(java.util.Locale.ROOT)
				.indexOf(needle.toLowerCase(java.util.Locale.ROOT));
	}

	/** True if any node in the component tree carries a clickEvent (link/button). */
	private static boolean hasClickEvent(Text text) {
		if (text.getStyle().getClickEvent() != null) return true;
		for (Text sibling : text.getSiblings()) {
			if (hasClickEvent(sibling)) return true;
		}
		return false;
	}

	/**
	 * Walk the whole component tree looking for a Style insertion — vanilla sets
	 * this on the clickable player name to the bare username.
	 */
	private static String extractSender(Text text) {
		String ins = text.getStyle().getInsertion();
		if (ins != null && !ins.isBlank()) return ins;
		for (Text sibling : text.getSiblings()) {
			String found = extractSender(sibling);
			if (found != null) return found;
		}
		return null;
	}
}
