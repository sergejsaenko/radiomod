package com.radiomod.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches radio stations from radio-browser.info for any country.
 * Results are cached per country code. For "local" mode, uses IP geolocation first.
 */
@OnlyIn(Dist.CLIENT)
public class LocalStationFetcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_RESULTS = 15;   // unique stations returned to the UI
    private static final int FETCH_LIMIT = 60;   // how many to fetch from API before dedup
    private static final int TIMEOUT_MS = 8000;

    // Cache: countryCode -> fetched station list (null = not yet fetched, empty list = failed)
    private static final Map<String, List<RadioStation>> cache = new ConcurrentHashMap<>();
    // Track which country codes are currently being fetched
    private static final Map<String, Boolean> fetching = new ConcurrentHashMap<>();

    // Local geolocation
    private static final AtomicReference<String> detectedCountryCode = new AtomicReference<>(null);
    private static final AtomicReference<String> detectedState = new AtomicReference<>("");
    private static final AtomicReference<String> detectedCountryName = new AtomicReference<>("");

    // ─────────────────────────────────────────────────────────────────
    //  PUBLIC API
    // ─────────────────────────────────────────────────────────────────

    /**
     * Get stations for the player's detected local area.
     * Returns null if still loading, empty list if failed.
     */
    public static List<RadioStation> getLocalStations() {
        // First ensure we know the player's location
        String cc = detectedCountryCode.get();
        if (cc == null) {
            triggerGeoDetection();
            return null; // not ready
        }
        // Fetch stations for detected country + state
        return getStationsForCountry(cc);
    }

    /**
     * Get stations for a specific country code (e.g. "DE", "JP", "US").
     * Returns null if still loading, the list once available.
     */
    public static List<RadioStation> getStationsForCountry(String countryCode) {
        if (countryCode == null || countryCode.isEmpty()) return null;
        String cc = countryCode.toUpperCase();

        List<RadioStation> cached = cache.get(cc);
        if (cached != null) return cached;

        // Trigger async fetch if not already running
        if (!fetching.containsKey(cc)) {
            fetching.put(cc, true);
            final String state = cc.equals(detectedCountryCode.get()) ? detectedState.get() : "";
            CompletableFuture.runAsync(() -> fetchForCountry(cc, state));
        }
        return null; // not ready yet
    }

    /**
     * Check if stations for a country code have been loaded
     */
    public static boolean isReady(String countryCode) {
        if (countryCode == null) return false;
        return cache.containsKey(countryCode.toUpperCase());
    }

    /**
     * Check if local geolocation is ready
     */
    public static boolean isLocalReady() {
        String cc = detectedCountryCode.get();
        return cc != null && cache.containsKey(cc);
    }

    /**
     * Shortcut for old code compatibility
     */
    public static boolean isReady() {
        return isLocalReady();
    }

    public static String getDetectedCountry() {
        return detectedCountryName.get();
    }

    public static String getDetectedState() {
        return detectedState.get();
    }

    // ─────────────────────────────────────────────────────────────────
    //  INTERNAL
    // ─────────────────────────────────────────────────────────────────

    private static void triggerGeoDetection() {
        if (fetching.containsKey("__geo__")) return;
        fetching.put("__geo__", true);
        CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("RadioMod: Detecting location...");
                String geoJson = httpGet("http://ip-api.com/json/?fields=country,regionName,city,countryCode");
                JsonObject geo = JsonParser.parseString(geoJson).getAsJsonObject();

                String cc = geo.has("countryCode") ? geo.get("countryCode").getAsString() : "DE";
                String state = geo.has("regionName") ? geo.get("regionName").getAsString() : "";
                String country = geo.has("country") ? geo.get("country").getAsString() : "";
                String city = geo.has("city") ? geo.get("city").getAsString() : "";

                detectedCountryCode.set(cc);
                detectedState.set(state);
                detectedCountryName.set(country);
                LOGGER.info("RadioMod: Location: {}, {}, {} ({})", city, state, country, cc);

                // Immediately start fetching for this country
                fetchForCountry(cc, state);

            } catch (Exception e) {
                LOGGER.warn("RadioMod: Geolocation failed: {}", e.getMessage());
                detectedCountryCode.set("DE"); // fallback
                detectedState.set("");
            }
        });
    }

    private static void fetchForCountry(String countryCode, String state) {
        try {
            List<RadioStation> stations;

            // If we have a state, try local first for better results
            if (!state.isEmpty()) {
                stations = queryRadioBrowser(countryCode, state);
                if (stations.size() >= 6) {  // need at least 6 unique regional results to use them
                    cache.put(countryCode, Collections.unmodifiableList(stations));
                    LOGGER.info("RadioMod: Loaded {} stations for {} ({})", stations.size(), countryCode, state);
                    return;
                }
            }

            // Fetch country-wide
            stations = queryRadioBrowser(countryCode, "");
            cache.put(countryCode, Collections.unmodifiableList(stations));
            LOGGER.info("RadioMod: Loaded {} stations for {}", stations.size(), countryCode);

        } catch (Exception e) {
            LOGGER.warn("RadioMod: Fetch failed for {}: {}", countryCode, e.getMessage());
            cache.put(countryCode, Collections.emptyList());
        }
    }

    private static List<RadioStation> queryRadioBrowser(String countryCode, String state) {
        try {
            StringBuilder url = new StringBuilder();
            url.append("https://de1.api.radio-browser.info/json/stations/search?");
            url.append("countrycode=").append(URLEncoder.encode(countryCode, StandardCharsets.UTF_8.name()));
            url.append("&codec=MP3");
            url.append("&bitrateMin=64");          // lower threshold so more stations qualify
            url.append("&limit=").append(FETCH_LIMIT);  // fetch many, deduplicate down to MAX_RESULTS
            url.append("&order=clickcount");        // clickcount = actual usage, not just votes
            url.append("&reverse=true");
            url.append("&hidebroken=true");
            if (!state.isEmpty()) {
                url.append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8.name()));
            }

            String json = httpGet(url.toString());
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();

            List<RadioStation> result = new ArrayList<>();
            // Use a set of normalized base-names to catch regional variants
            // e.g. "WDR 2 Rhein", "WDR 2 Ostwestfalen", "WDR 2" all share base "wdr 2"
            java.util.Set<String> seenBases = new java.util.HashSet<>();
            for (JsonElement elem : arr) {
                JsonObject obj = elem.getAsJsonObject();
                String name = obj.has("name") ? obj.get("name").getAsString().trim() : "";
                String streamUrl = obj.has("url_resolved") ? obj.get("url_resolved").getAsString().trim() : "";
                String uuid = obj.has("stationuuid") ? obj.get("stationuuid").getAsString() : "";

                if (name.isEmpty() || streamUrl.isEmpty()) continue;
                if (name.length() > 32) name = name.substring(0, 30) + "..";

                // Derive a "base name": lowercase, strip trailing regional word(s)
                // A regional suffix is defined as: one or more words at the end that
                // are NOT found as a standalone name among already-accepted results,
                // i.e. we simply compare against all prefixes of the current name.
                String baseName = deriveBaseName(name.toLowerCase());

                // Check if this base already exists, or if an accepted base is a prefix of this one
                boolean duplicate = false;
                for (String seen : seenBases) {
                    if (seen.equals(baseName)
                            || baseName.startsWith(seen + " ")
                            || seen.startsWith(baseName + " ")) {
                        duplicate = true;
                        break;
                    }
                }
                if (duplicate) continue;
                seenBases.add(baseName);

                String id = "dyn_" + uuid.substring(0, Math.min(8, uuid.length()));
                result.add(new RadioStation(id, name, streamUrl));
                if (result.size() >= MAX_RESULTS) break;
            }
            return result;

        } catch (Exception e) {
            LOGGER.debug("RadioMod: radio-browser query failed for {}: {}", countryCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Derives a "base name" by stripping common trailing regional qualifiers.
     * Examples:
     * "wdr 2 rhein"       -> "wdr 2"
     * "wdr 2 ostwestfalen"-> "wdr 2"
     * "wdr 2"             -> "wdr 2"
     * "ndr 2"             -> "ndr 2"
     */
    private static String deriveBaseName(String lower) {
        // Split into words; strip trailing word(s) if they look like region names
        // (longer than 3 chars, not a number, not part of the "core" identifier)
        String[] words = lower.trim().split("\\s+");
        if (words.length <= 2) return lower.trim();
        // Keep stripping the last word if it is an alphabetic region qualifier
        // (more than 3 chars, all letters or hyphens, not a frequency like "97.8")
        int end = words.length;
        while (end > 2) {
            String last = words[end - 1];
            if (last.matches("[a-z][a-z\\-]{3,}")) {
                end--;
            } else {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "RadioMod/1.0 Minecraft");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }
}
