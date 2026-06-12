package net.dc.msrtiertagger.data;

import com.google.gson.*;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
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
                conn.setRequestProperty("Cache-Control", "no-cache");

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

    // ── Icon font (gamemode + rank glyphs) ──────────────────────────────────────
    // A custom bitmap font ships 16×16 icons mapped to private-use-area codepoints
    // (see assets/msr-tier-tagger/font/icons.json). Glyphs are tinted by the text
    // colour, so we render them in white to preserve their original artwork.

    public static final StyleSpriteSource ICON_FONT =
            new StyleSpriteSource.Font(Identifier.of("msr-tier-tagger", "icons"));

    /** Main gamemode id -> glyph codepoint (matches the font's "modes" providers). */
    private static final Map<String, String> MODE_GLYPHS = new HashMap<>();
    /** Normalised title name -> glyph codepoint (matches the font's "ranks" providers). */
    private static final Map<String, String> RANK_GLYPHS = new HashMap<>();
    static {
        MODE_GLYPHS.put("sword",   "\uE000");
        MODE_GLYPHS.put("axe",     "\uE001");
        MODE_GLYPHS.put("uhc",     "\uE002");
        MODE_GLYPHS.put("mace",    "\uE003");
        MODE_GLYPHS.put("crystal", "\uE004");
        MODE_GLYPHS.put("dsmp",    "\uE005");
        MODE_GLYPHS.put("nsmp",    "\uE006");
        MODE_GLYPHS.put("dpot",    "\uE007");
        MODE_GLYPHS.put("npot",    "\uE008");
        MODE_GLYPHS.put("hsmp",    "\uE009");

        RANK_GLYPHS.put("combatgrandmaster", "\uE100");
        RANK_GLYPHS.put("combatmaster",      "\uE101");
        RANK_GLYPHS.put("combatace",         "\uE102");
        RANK_GLYPHS.put("combatspecialist",  "\uE103");
        RANK_GLYPHS.put("combatcadet",       "\uE104");
        RANK_GLYPHS.put("combatnovice",      "\uE105");
        RANK_GLYPHS.put("rookie",            "\uE106");
    }

    /** Renders a single icon glyph in the custom font, white so the artwork keeps its colours. */
    private static MutableText iconText(String glyph) {
        if (glyph == null) return null;
        return Text.literal(glyph)
                .setStyle(Style.EMPTY.withFont(ICON_FONT).withColor(0xFFFFFF).withBold(false));
    }

    /** Icon for a detected gamemode id, or null if it has none. */
    private static MutableText modeIcon(String gamemode) {
        return gamemode == null ? null : iconText(MODE_GLYPHS.get(gamemode));
    }

    /** Icon for a rank title (e.g. "Combat Ace" -> combatace), or null. */
    private static MutableText rankIcon(Title title) {
        if (title == null || title.name() == null) return null;
        String key = title.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return iconText(RANK_GLYPHS.get(key));
    }

    // ── Badge builder ─────────────────────────────────────────────────────────

    /**
     * Builds the coloured tier badge text shown before a player's name, with a
     * trailing separator (e.g. "HT1 🗡 | ") — used for the chat prefix.
     */
    public static MutableText buildBadge(PlayerTier player, String gamemode) {
        return buildBadgeCore(player, gamemode).append(separator());
    }

    /**
     * The badge without any separator — just "tier + icon" or "#rank + icon",
     * with the icon on the outer (right) edge.
     *
     * If a gamemode is supplied (the local player's detected mode) and the player
     * has a placement there, this is that gamemode's tier + icon (e.g. "HT1 🗡").
     * Otherwise it is the player's independently-computed overall standing — their
     * rank + rank icon, coloured by their title (e.g. "#3 🏅").
     *
     * The nametag composes this on the RIGHT of the name ("name | tier icon") so
     * the icon sits furthest right and never collides with icons other mods
     * (e.g. Essentials) draw to the left of the nametag.
     */
    public static MutableText buildBadgeCore(PlayerTier player, String gamemode) {
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
                return withIcon(tierPart, modeIcon(gamemode));
            }
        }

        // Default: overall standing (rank), coloured by the player's title.
        // (No developer gear here — the rank icon already conveys standing.)
        String suffix = player.isFullyRetired() ? " †" : ""; // dagger = retired
        boolean bold = player.rank() <= 3;
        int colour = player.title() != null ? player.title().colorRgb() : 0xFFFFFF;

        MutableText rankPart = Text.literal("#" + player.rank() + suffix)
                .setStyle(Style.EMPTY.withColor(colour).withBold(bold));
        return withIcon(rankPart, rankIcon(player.title()));
    }

    /** The grey " | " separator between the badge and the name. */
    public static MutableText separatorText() {
        return separator();
    }

    /**
     * Appends an icon glyph (after a thin space) to a badge part, if the icon
     * exists — so the badge reads "tier icon" / "#rank icon", with the icon on
     * the outer (right) edge.
     *
     * The pieces are appended to a neutral empty root — NOT to the part — so the
     * icon font stays confined to the glyph. Siblings inherit unset style from the
     * parent, so reusing the part as the root could leak its colour onto the icon
     * (and an icon-rooted tree would force the tier/rank text into the icon font,
     * which has no letters/digits and renders them as missing-glyph rectangles).
     */
    private static MutableText withIcon(MutableText part, MutableText icon) {
        if (icon == null) return part;
        return Text.empty()
                .append(part)
                .append(Text.literal(" ")
                        .setStyle(Style.EMPTY.withColor(Formatting.WHITE).withBold(false)))
                .append(icon);
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
