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

/**
 * Holds the MSR tier data and the helpers that turn it into in-game text.
 *
 * The JSON it consumes (msr_tiers.json) mirrors the website's data.json model:
 *   points        — tier code -> points
 *   titles        — [{ min, name, color }] thresholds, high to low
 *   gamemodes      — main modes (count toward points)
 *   subgamemodes   — subtier modes (do NOT count toward points)
 *   players        — name, uuid, badges, tiers{}, subtiers{}
 *
 * Crucially, overall standing is NOT stored. Rank and title are computed here
 * from points exactly like the website does, so they stay correct as tiers and
 * the points table change over time.
 */
public class TierRegistry {

    public static final Logger LOGGER =
            LoggerFactory.getLogger("msr-tiertagger-registry");

    private static final Map<String, PlayerTier> BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, PlayerTier> BY_UUID = new ConcurrentHashMap<>();

    // Scoring tables parsed from the JSON (fall back to the website's defaults).
    private static volatile Map<String, Integer> POINTS = defaultPoints();
    private static volatile List<Title> TITLES = defaultTitles();
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

                load(root);
                LOGGER.info("[MSR] Loaded {} player tier entries.", BY_NAME.size());

            } catch (Exception e) {
                LOGGER.warn("[MSR] Could not fetch tier data: {}", e.getMessage());
            }
        });
    }

    // ── Load + independent ranking ──────────────────────────────────────────────

    private static void load(JsonObject root) {
        // Points table
        Map<String, Integer> points = new LinkedHashMap<>();
        if (root.has("points") && root.get("points").isJsonObject()) {
            for (var e : root.getAsJsonObject("points").entrySet()) {
                points.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        if (points.isEmpty()) points = defaultPoints();

        // Titles (sorted high -> low so the first match wins)
        List<Title> titles = new ArrayList<>();
        if (root.has("titles") && root.get("titles").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("titles")) {
                JsonObject o = el.getAsJsonObject();
                titles.add(new Title(
                        o.has("min") ? o.get("min").getAsInt() : 0,
                        o.has("name") ? o.get("name").getAsString() : "",
                        parseHex(o.has("color") ? o.get("color").getAsString() : null, 0xFFFFFF)
                ));
            }
        }
        if (titles.isEmpty()) titles = defaultTitles();
        titles.sort((a, b) -> Integer.compare(b.min(), a.min()));

        POINTS = points;
        TITLES = titles;

        // Parse players, then compute total points -> sort -> assign rank + title.
        List<Raw> raws = new ArrayList<>();
        if (root.has("players") && root.get("players").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("players")) {
                raws.add(Raw.parse(el.getAsJsonObject()));
            }
        }
        for (Raw r : raws) {
            int total = 0;
            // Subtiers never count — same rule as the website.
            for (TierEntry te : r.tiers.values()) total += points.getOrDefault(te.tier(), 0);
            r.total = total;
        }
        raws.sort((a, b) -> a.total != b.total
                ? Integer.compare(b.total, a.total)
                : a.name.compareToIgnoreCase(b.name));

        Map<String, PlayerTier> byName = new ConcurrentHashMap<>();
        Map<String, PlayerTier> byUuid = new ConcurrentHashMap<>();
        int rank = 0;
        for (Raw r : raws) {
            rank++;
            PlayerTier pt = r.toPlayerTier(rank, titleFor(r.total, titles));
            byName.put(pt.name().toLowerCase(Locale.ROOT), pt);
            if (pt.uuid() != null && !pt.uuid().isBlank() && !pt.uuid().startsWith("PASTE")) {
                byUuid.put(pt.uuid().toLowerCase(Locale.ROOT), pt);
            }
        }

        BY_NAME.clear(); BY_NAME.putAll(byName);
        BY_UUID.clear(); BY_UUID.putAll(byUuid);
        loaded = true;
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
        BY_NAME.forEach((name, p) ->
                LOGGER.info("[MSR DEBUG] {} -> rank:#{} pts:{} title:{} tiers:{} subtiers:{} badges:{}",
                        name, p.rank(), p.totalPoints(),
                        p.title() != null ? p.title().name() : "?",
                        p.tiers().keySet(), p.subtiers().keySet(), p.badges())
        );
    }

    // ── Title lookup ────────────────────────────────────────────────────────────

    public static Title titleFor(int points) { return titleFor(points, TITLES); }

    private static Title titleFor(int points, List<Title> titles) {
        for (Title t : titles) if (points >= t.min()) return t; // titles are high -> low
        return titles.isEmpty() ? new Title(0, "Rookie", 0x5a6e88)
                                : titles.get(titles.size() - 1);
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
     * Builds the coloured tier badge text shown before a player's name.
     *
     * If a gamemode is supplied (the local player's detected mode) and the player
     * has a placement there, the badge shows that gamemode's tier (e.g. "HT1 | ").
     * Otherwise it shows the player's independently-computed overall standing —
     * their rank, coloured by their title — (e.g. "#3 | ").
     */
    public static MutableText buildBadge(PlayerTier player, String gamemode) {
        // Gamemode-specific main tier when we know what the player is queued in.
        if (gamemode != null) {
            TierEntry te = player.tiers().get(gamemode);
            if (te != null) {
                boolean bold = te.tier().startsWith("H");
                String suffix = te.retired() ? " †" : ""; // dagger = retired
                MutableText tierPart = Text.literal(te.tier() + suffix)
                        .setStyle(Style.EMPTY
                                .withColor(tierColourRgb(te.tier()))
                                .withBold(bold));
                return tierPart.append(separator());
            }
        }

        // Default: overall standing (rank), coloured by the player's title.
        String suffix = player.developer() ? " ⚙"                  // gear  = developer
                : (player.isFullyRetired() ? " †" : "");           // dagger = retired
        boolean bold = player.rank() <= 3;
        int colour = player.title() != null ? player.title().colorRgb() : 0xFFFFFF;

        MutableText rankPart = Text.literal("#" + player.rank() + suffix)
                .setStyle(Style.EMPTY.withColor(colour).withBold(bold));
        return rankPart.append(separator());
    }

    /** Overload — shows overall standing when no gamemode is active. */
    public static MutableText buildBadge(PlayerTier player) {
        return buildBadge(player, null);
    }

    private static MutableText separator() {
        return Text.literal(" | ")
                .setStyle(Style.EMPTY.withColor(Formatting.GRAY).withBold(false));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static int parseHex(String hex, int fallback) {
        if (hex == null) return fallback;
        try {
            return Integer.parseInt(hex.replace("#", "").trim(), 16);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<String, Integer> defaultPoints() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("HT1", 70); m.put("LT1", 55); m.put("HT2", 45); m.put("LT2", 38);
        m.put("HT3", 30); m.put("LT3", 23); m.put("HT4", 17); m.put("LT4", 12);
        m.put("HT5", 8);  m.put("LT5", 5);
        return m;
    }

    private static List<Title> defaultTitles() {
        List<Title> t = new ArrayList<>();
        t.add(new Title(450, "Combat Grandmaster", 0xffd76b));
        t.add(new Title(350, "Combat Master",      0x7bc4ff));
        t.add(new Title(250, "Combat Ace",         0xa974f0));
        t.add(new Title(150, "Combat Specialist",  0x54c66b));
        t.add(new Title(80,  "Combat Cadet",       0x4670a0));
        t.add(new Title(30,  "Combat Novice",      0x8aa0bb));
        t.add(new Title(0,   "Rookie",             0x5a6e88));
        return t;
    }

    // ── Records ─────────────────────────────────────────────────────────────────

    /** A rank title threshold (e.g. min 450 -> "Combat Grandmaster", gold). */
    public record Title(int min, String name, int colorRgb) {}

    /** A single tier placement — either "HT3" or {tier:"HT3", retired:true, peak:true}. */
    public record TierEntry(String tier, boolean retired, boolean peak) {
        static TierEntry from(JsonElement el) {
            if (el.isJsonPrimitive()) {
                return new TierEntry(el.getAsString(), false, false);
            }
            JsonObject o = el.getAsJsonObject();
            return new TierEntry(
                    o.has("tier") ? o.get("tier").getAsString() : "?",
                    o.has("retired") && o.get("retired").getAsBoolean(),
                    o.has("peak") && o.get("peak").getAsBoolean()
            );
        }
    }

    /**
     * A ranked player. Tiers and subtiers preserve the string-or-object form via
     * {@link TierEntry}; badges (premium/tester/subtester/house/developer) are
     * parsed and exposed for future perks (e.g. username colours) but do not yet
     * change rendering. totalPoints/rank/title are computed, not stored.
     */
    public record PlayerTier(
            String name,
            String uuid,
            String region,
            boolean premium,
            boolean tester,
            boolean developer,
            String subtester,           // subtier id, or null
            String house,               // blue/red/yellow/green, or null
            Map<String, TierEntry> tiers,
            Map<String, TierEntry> subtiers,
            int totalPoints,
            int rank,
            Title title
    ) {
        public boolean isFullyRetired() {
            if (tiers.isEmpty()) return false;
            for (TierEntry te : tiers.values()) if (!te.retired()) return false;
            return true;
        }

        /** Badge ids the player holds, highest priority first (mirrors the website order). */
        public List<String> badges() {
            List<String> b = new ArrayList<>();
            if (developer)          b.add("developer");
            if (tester)             b.add("tester");
            if (subtester != null)  b.add("subtester:" + subtester);
            if (isFullyRetired())   b.add("retired");
            if (premium)            b.add("premium");
            if (house != null)      b.add("house:" + house);
            return b;
        }
    }

    /** Mutable parsing holder used only while loading, before rank is known. */
    private static final class Raw {
        String name = "unknown", uuid, region = "", subtester, house;
        boolean premium, tester, developer;
        final Map<String, TierEntry> tiers = new LinkedHashMap<>();
        final Map<String, TierEntry> subtiers = new LinkedHashMap<>();
        int total;

        static Raw parse(JsonObject o) {
            Raw r = new Raw();
            // Accept "name" (current) or legacy "username".
            if (o.has("name"))          r.name = o.get("name").getAsString();
            else if (o.has("username")) r.name = o.get("username").getAsString();

            r.uuid      = str(o, "uuid");
            r.region    = o.has("region") && !o.get("region").isJsonNull() ? o.get("region").getAsString() : "";
            r.subtester = str(o, "subtester");
            r.house     = str(o, "house");
            r.premium   = bool(o, "premium");
            r.tester    = bool(o, "tester");
            r.developer = bool(o, "developer");

            parseTiers(o, "tiers", r.tiers);
            parseTiers(o, "subtiers", r.subtiers);
            return r;
        }

        private static void parseTiers(JsonObject o, String key, Map<String, TierEntry> dst) {
            if (!o.has(key) || !o.get(key).isJsonObject()) return;
            for (var e : o.getAsJsonObject(key).entrySet()) {
                JsonElement v = e.getValue();
                // Legacy sentinel from the old schema — treat as "no placement".
                if (v.isJsonPrimitive() && "UNRANKED".equals(v.getAsString())) continue;
                dst.put(e.getKey(), TierEntry.from(v));
            }
        }

        private static String str(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
        }

        private static boolean bool(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
        }

        PlayerTier toPlayerTier(int rank, Title title) {
            return new PlayerTier(
                    name, uuid, region,
                    premium, tester, developer, subtester, house,
                    Collections.unmodifiableMap(tiers),
                    Collections.unmodifiableMap(subtiers),
                    total, rank, title
            );
        }
    }
}
