package net.dc.msrtiertagger.data;

import com.google.gson.*;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TierRegistry {

    public static final Logger LOGGER =
            LoggerFactory.getLogger("msr-tiertagger-registry");

    private static final Map<String, PlayerTier> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, PlayerTier> BY_UUID = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    // ── Fetch ─────────────────────────────────────────────────────────────────

    public static void fetchAsync(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("[MSR] Fetching tier data from GitHub...");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "MSR-TierTagger/1.0");

                if (conn.getResponseCode() != 200) {
                    LOGGER.warn("[MSR] HTTP {} when fetching tier data", conn.getResponseCode());
                    return;
                }

                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(conn.getInputStream())
                ).getAsJsonObject();

                BY_NAME.clear();
                BY_UUID.clear();

                JsonArray players = root.getAsJsonArray("players");
                int count = 0;
                for (JsonElement el : players) {
                    PlayerTier tier = PlayerTier.fromJson(el.getAsJsonObject());
                    BY_NAME.put(tier.username().toLowerCase(Locale.ROOT), tier);
                    if (tier.uuid() != null && !tier.uuid().startsWith("PASTE")) {
                        BY_UUID.put(tier.uuid().toLowerCase(Locale.ROOT), tier);
                    }
                    count++;
                }
                loaded = true;
                LOGGER.info("[MSR] Loaded {} player tier entries.", count);

            } catch (Exception e) {
                LOGGER.warn("[MSR] Could not fetch tier data: {}", e.getMessage());
            }
        });
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    public static Optional<PlayerTier> getByUsername(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(username.toLowerCase(Locale.ROOT)));
    }

    public static Optional<PlayerTier> getByUuid(String uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(BY_UUID.get(uuid.toLowerCase(Locale.ROOT)));
    }

    public static boolean isLoaded() { return loaded; }

    public static int getPlayerCount() { return BY_NAME.size(); }

    public static void logAllTiers() {
        if (BY_NAME.isEmpty()) {
            LOGGER.info("[MSR DEBUG] No tier data loaded yet — JSON may still be fetching.");
            return;
        }
        BY_NAME.forEach((name, tier) ->
                LOGGER.info("[MSR DEBUG] {} -> overall:{} gamemode tiers:{}",
                        name, tier.overallTier(), tier.gamemodes())
        );
    }

    // ── Tier colours (RGB hex) ────────────────────────────────────────────────
    // Tier 1: Gold    (#FFD700)
    // Tier 2: Silver  (#C0C0C0)
    // Tier 3: Bronze  (#CD7F32)
    // Tier 4: Dark blue-gray (#5B6EAE)
    // Tier 5: Slate gray     (#778899)

    public static int tierColourRgb(String tier) {
        if (tier == null) return 0x778899;
        return switch (tier) {
            case "LT1", "HT1" -> 0xFFD700; // Gold
            case "LT2", "HT2" -> 0xC0C0C0; // Silver
            case "LT3", "HT3" -> 0xCD7F32; // Bronze
            case "LT4", "HT4" -> 0x5B6EAE; // Dark blue-gray
            default            -> 0x778899; // Slate gray (LT5/HT5)
        };
    }

    // ── Badge builder ─────────────────────────────────────────────────────────

    /**
     * Builds the coloured tier badge text.
     * If gamemode is non-null and the player has a ranking for that mode,
     * shows the gamemode-specific tier instead of overall.
     * Result:  HT1 |   (caller appends the player name after)
     */
    public static MutableText buildBadge(PlayerTier player, String gamemode) {
        // Pick gamemode-specific tier if available, fall back to overall
        String tier = player.overallTier();
        if (gamemode != null) {
            String gmTier = player.gamemodes().get(gamemode);
            if (gmTier != null && !"UNRANKED".equals(gmTier)) {
                tier = gmTier;
            }
        }

        boolean bold = tier != null && tier.startsWith("H");

        String suffix = "";
        if ("KING".equals(player.special()))         suffix = " \u2764";
        else if ("RETIRED".equals(player.special())) suffix = " \u2020";
        else if ("DEV".equals(player.special()))     suffix = " \u2699";

        // Tier label with exact RGB colour
        MutableText tierPart = Text.literal((tier != null ? tier : "?") + suffix)
                .setStyle(Style.EMPTY
                        .withColor(tierColourRgb(tier))
                        .withBold(bold));

        // Separator in gray
        MutableText sep = Text.literal(" | ")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GRAY)
                        .withBold(false));

        return tierPart.append(sep);
    }

    /** Overload — shows overall tier when no gamemode is active. */
    public static MutableText buildBadge(PlayerTier player) {
        return buildBadge(player, null);
    }

    // ── PlayerTier record ─────────────────────────────────────────────────────

    public record PlayerTier(
            String username,
            String uuid,
            String overallTier,
            int rank,
            Map<String, String> gamemodes,
            String special
    ) {
        public static PlayerTier fromJson(JsonObject obj) {
            String username = obj.has("username") ? obj.get("username").getAsString() : "unknown";
            String uuid     = obj.has("uuid")     ? obj.get("uuid").getAsString()     : null;
            String overall  = obj.has("overall")  ? obj.get("overall").getAsString()  : "LT5";
            int rank        = obj.has("rank")      ? obj.get("rank").getAsInt()        : 99;
            String special  = obj.has("special") && !obj.get("special").isJsonNull()
                    ? obj.get("special").getAsString() : null;

            Map<String, String> tiers = new LinkedHashMap<>();
            if (obj.has("tiers")) {
                obj.getAsJsonObject("tiers").entrySet()
                        .forEach(e -> tiers.put(e.getKey(), e.getValue().getAsString()));
            }
            return new PlayerTier(username, uuid, overall, rank, tiers, special);
        }
    }
}