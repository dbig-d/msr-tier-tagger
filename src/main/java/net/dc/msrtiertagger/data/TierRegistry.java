package net.dc.msrtiertagger.data;

import com.google.gson.*;
import net.dc.msrtiertagger.MSRTierTagger;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches MSR tier data from the GitHub JSON file and stores it in memory.
 * Lookups work by both username (lowercase) and UUID.
 */
public class TierRegistry {

    // username (lowercase) -> PlayerTier
    private static final Map<String, PlayerTier> BY_NAME = new ConcurrentHashMap<>();
    // uuid (lowercase, dashed) -> PlayerTier
    private static final Map<String, PlayerTier> BY_UUID = new ConcurrentHashMap<>();

    private static volatile boolean loaded = false;

    // ── Fetch ────────────────────────────────────────────────────────────────

    public static void fetchAsync(String url) {
        CompletableFuture.runAsync(() -> {
            try {
                MSRTierTagger.LOGGER.info("[MSR] Fetching tier data from GitHub...");
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "MSR-TierTagger/1.0");

                if (conn.getResponseCode() != 200) {
                    MSRTierTagger.LOGGER.warn("[MSR] HTTP {} when fetching tier data", conn.getResponseCode());
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
                MSRTierTagger.LOGGER.info("[MSR] Loaded {} player tier entries.", count);

            } catch (Exception e) {
                MSRTierTagger.LOGGER.warn("[MSR] Could not fetch tier data: {}", e.getMessage());
            }
        });
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    public static Optional<PlayerTier> getByUsername(String username) {
        if (username == null) return Optional.empty();
        return Optional.ofNullable(BY_NAME.get(username.toLowerCase(Locale.ROOT)));
    }

    public static Optional<PlayerTier> getByUuid(String uuid) {
        if (uuid == null) return Optional.empty();
        return Optional.ofNullable(BY_UUID.get(uuid.toLowerCase(Locale.ROOT)));
    }

    public static boolean isLoaded() { return loaded; }

    // ── Tier display helpers ──────────────────────────────────────────────────

    /** Returns the Minecraft formatting colour for a given tier string. */
    public static Formatting tierColour(String tier) {
        if (tier == null) return Formatting.WHITE;
        return switch (tier) {
            case "LT1", "HT1" -> Formatting.GOLD;
            case "LT2", "HT2" -> Formatting.GRAY;
            case "LT3", "HT3" -> Formatting.RED;
            default            -> Formatting.WHITE;
        };
    }

    /**
     * Builds the coloured badge Text shown in chat and above heads.
     * Examples:  §6[HT1👑]  §7[LT2]  §c[HT3]
     */
    public static MutableText buildBadge(PlayerTier player) {
        String tier   = player.overallTier();
        boolean bold  = tier != null && tier.startsWith("H");
        String suffix = "";

        if ("KING".equals(player.special()))         suffix = " 👑";
        else if ("RETIRED".equals(player.special())) suffix = " †";
        else if ("DEV".equals(player.special()))     suffix = " ⚙";

        // Format:  HT1 |   or   LT3 |
        // Tier name is coloured + bold (if HT), separator is white dim
        String tierLabel = (tier != null ? tier : "?") + suffix;

        MutableText tierText = Text.literal(tierLabel)
                .setStyle(Style.EMPTY
                        .withColor(tierColour(tier))
                        .withBold(bold));

        MutableText separator = Text.literal(" | ")
                .setStyle(Style.EMPTY
                        .withColor(Formatting.GRAY)
                        .withBold(false));

        return tierText.append(separator);
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