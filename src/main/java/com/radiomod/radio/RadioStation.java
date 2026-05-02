package com.radiomod.radio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RadioStation {

    // ------------------------------------------------------------------
    //  Client-side fetcher hook — registered by ClientSetup (client only).
    //  On the dedicated server this remains null → Country falls back to
    //  the static station lists without ever loading LocalStationFetcher.
    // ------------------------------------------------------------------

    public interface ClientFetcherHook {
        /**
         * Returns fetched stations for a country code, or null if still loading.
         */
        List<RadioStation> getForCountry(String countryCode);

        /**
         * Returns geolocation-based stations, or null if still loading.
         */
        List<RadioStation> getLocal();

        /**
         * True once stations for the given country code are cached.
         */
        boolean isCountryReady(String countryCode);

        /**
         * True once geolocation + local stations are ready.
         */
        boolean isLocalReady();
    }

    private static volatile ClientFetcherHook clientHook = null;

    /**
     * Called once from ClientSetup on the logical client.
     */
    public static void registerClientHook(ClientFetcherHook hook) {
        clientHook = hook;
    }

    private final String id;
    private final String displayName;
    private final String streamUrl;

    public RadioStation(String id, String displayName, String streamUrl) {
        this.id = id;
        this.displayName = displayName;
        this.streamUrl = streamUrl;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStreamUrl() {
        return streamUrl;
    }

    // =================================================================
    //  Country/Region system
    // =================================================================

    public static class Country {
        private final String id;
        private final String langKey;
        private final String countryCode; // ISO 2-letter code for API fetch, or "LOCAL" for geolocation
        private final List<RadioStation> fallbackStations;

        public Country(String id, String langKey, String countryCode, List<RadioStation> fallback) {
            this.id = id;
            this.langKey = langKey;
            this.countryCode = countryCode;
            List<RadioStation> list = new ArrayList<>(fallback);
            list.add(STATION_OFF);
            this.fallbackStations = Collections.unmodifiableList(list);
        }

        public String getId() {
            return id;
        }

        public String getLangKey() {
            return langKey;
        }

        public String getCountryCode() {
            return countryCode;
        }

        /**
         * Returns true if this is the dynamic local-detection entry
         */
        public boolean isLocal() {
            return "LOCAL".equals(countryCode);
        }

        /**
         * Returns the station list.
         * On the client: tries dynamically fetched stations via the registered hook;
         * falls back to the static list if fetch hasn't completed or failed.
         * On the dedicated server: always returns the static fallback list
         * (clientHook is null → LocalStationFetcher is never referenced/loaded).
         */
        public List<RadioStation> getStations() {
            ClientFetcherHook hook = clientHook;
            if (hook != null) {
                List<RadioStation> fetched = isLocal()
                        ? hook.getLocal()
                        : hook.getForCountry(countryCode);
                if (fetched != null && !fetched.isEmpty()) {
                    List<RadioStation> list = new ArrayList<>(fetched);
                    list.add(STATION_OFF);
                    return list;
                }
            }
            // Return fallback (static list) while loading, on server, or if API failed
            return fallbackStations;
        }

        /**
         * Check if dynamic stations have been loaded
         */
        public boolean isFetched() {
            ClientFetcherHook hook = clientHook;
            if (hook == null) return true; // server: consider always "ready" (uses fallback)
            return isLocal() ? hook.isLocalReady() : hook.isCountryReady(countryCode);
        }
    }

    public static final RadioStation STATION_OFF =
            new RadioStation("off", "--- OFF ---", "");

    // -----------------------------------------------------------------
    //  Fallback station lists (used while API loads or if offline)
    // -----------------------------------------------------------------

    private static final List<RadioStation> FALLBACK_LOCAL = Arrays.asList(
            new RadioStation("1live", "1LIVE (WDR)", "https://wdr-1live-live.icecastssl.wdr.de/wdr/1live/live/mp3/128/stream.mp3"),
            new RadioStation("wdr2", "WDR 2", "https://wdr-wdr2-aachenundregion.icecastssl.wdr.de/wdr/wdr2/aachenundregion/mp3/128/stream.mp3"),
            new RadioStation("bigfm", "bigFM", "https://streams.bigfm.de/bigfm-deutschland-128-mp3"),
            new RadioStation("Bayern3", "Bayern 3", "https://br-br3-live.cast.addradio.de/br/br3/live/mp3/128/stream.mp3"),
            new RadioStation("deutschlandradio", "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3")
    );

    private static final List<RadioStation> FALLBACK_DE = Arrays.asList(
            new RadioStation("de_dlf", "Deutschlandfunk", "https://st01.sslstream.dlf.de/dlf/01/128/mp3/stream.mp3"),
            new RadioStation("de_swr3", "SWR3", "https://liveradio.swr.de/sw282p3/swr3/play.mp3"),
            new RadioStation("de_fritz", "Fritz (rbb)", "https://rbb-fritz-live.cast.addradio.de/rbb/fritz/live/mp3/128/stream.mp3"),
            new RadioStation("de_fluxfm", "FluxFM", "https://streams.fluxfm.de/live/mp3-320/audio/")
    );

    private static final List<RadioStation> FALLBACK_JP = Arrays.asList(
            new RadioStation("jp_ottava", "OTTAVA", "http://ottava.out.airtime.pro:8000/ottava_a")
    );

    private static final List<RadioStation> FALLBACK_KR = Arrays.asList(
            new RadioStation("kr_arirang", "Arirang Radio", "http://arawurl.cloulix.com/arirangradio-onair.mp3")
    );

    private static final List<RadioStation> FALLBACK_US = Arrays.asList(
            new RadioStation("us_kexp", "KEXP 90.3", "https://kexp-mp3-128.streamguys1.com/kexp128.mp3"),
            new RadioStation("us_wfmu", "WFMU", "http://stream0.wfmu.org/freeform-128k")
    );

    private static final List<RadioStation> FALLBACK_AT = Arrays.asList(
            new RadioStation("at_kronehit", "Kronehit", "https://onair.krone.at/kronehit.mp3"),
            new RadioStation("at_fm4", "FM4", "https://orf-live.ors-shoutcast.at/fm4-q2a")
    );


    private static final List<RadioStation> FALLBACK_UK = Arrays.asList(
            new RadioStation("uk_bbc1", "BBC Radio 1", "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_one"),
            new RadioStation("uk_bbc2", "BBC Radio 2", "http://stream.live.vc.bbcmedia.co.uk/bbc_radio_two")
    );

    private static final List<RadioStation> FALLBACK_FR = Arrays.asList(
            new RadioStation("fr_inter", "France Inter", "https://icecast.radiofrance.fr/franceinter-midfi.mp3"),
            new RadioStation("fr_fip", "FIP", "https://icecast.radiofrance.fr/fip-midfi.mp3")
    );

    private static final List<RadioStation> FALLBACK_EG = Collections.emptyList();

    private static final List<RadioStation> FALLBACK_BR = Collections.emptyList();

    private static final List<RadioStation> FALLBACK_JM = Arrays.asList(
            new RadioStation("jm_irie", "Irie FM", "http://s4.viastreaming.net:7370/stream")
    );

    private static final List<RadioStation> FALLBACK_RU = Arrays.asList(
            new RadioStation("ru_europa", "Europa Plus", "http://ep128.hostingradio.ru:8030/europaplus128.mp3"),
            new RadioStation("ru_retro", "Retro FM", "http://retroserver.streamr.ru:8043/retro128.mp3")
    );

    // -----------------------------------------------------------------
    //  Public countries list
    // -----------------------------------------------------------------

    public static final List<Country> COUNTRIES = Arrays.asList(
            new Country("local", "gui.radiomod.country.local", "LOCAL", FALLBACK_LOCAL),
            new Country("germany", "gui.radiomod.country.germany", "DE", FALLBACK_DE),
            new Country("japan", "gui.radiomod.country.japan", "JP", FALLBACK_JP),
            new Country("southkorea", "gui.radiomod.country.southkorea", "KR", FALLBACK_KR),
            new Country("usa", "gui.radiomod.country.usa", "US", FALLBACK_US),
            new Country("austria", "gui.radiomod.country.austria", "AT", FALLBACK_AT),
            new Country("uk", "gui.radiomod.country.uk", "GB", FALLBACK_UK),
            new Country("france", "gui.radiomod.country.france", "FR", FALLBACK_FR),
            new Country("egypt", "gui.radiomod.country.egypt", "EG", FALLBACK_EG),
            new Country("brazil", "gui.radiomod.country.brazil", "BR", FALLBACK_BR),
            new Country("jamaica", "gui.radiomod.country.jamaica", "JM", FALLBACK_JM),
            new Country("russia", "gui.radiomod.country.russia", "RU", FALLBACK_RU)
    );

    // -----------------------------------------------------------------
    //  Lookup (searches dynamic + fallback stations)
    // -----------------------------------------------------------------

    public static final List<RadioStation> STATIONS = FALLBACK_LOCAL;

    public static RadioStation getById(String id) {
        if (id == null || id.isEmpty()) return STATION_OFF;
        if ("off".equals(id)) return STATION_OFF;

        // Search across all countries (dynamic + fallback)
        for (Country country : COUNTRIES) {
            for (RadioStation st : country.getStations()) {
                if (st.getId().equals(id)) return st;
            }
        }
        return STATION_OFF;
    }

    public static int getIndex(String id) {
        for (Country country : COUNTRIES) {
            List<RadioStation> list = country.getStations();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).getId().equals(id)) return i;
            }
        }
        return 0;
    }
}
