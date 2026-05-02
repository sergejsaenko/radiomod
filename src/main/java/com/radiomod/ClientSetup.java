package com.radiomod;

import com.radiomod.radio.LocalStationFetcher;
import com.radiomod.radio.RadioStation;
import com.radiomod.screen.ModMenuTypes;
import com.radiomod.screen.RadioScreen;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

public class ClientSetup {

    public static void registerFetcherHook() {
        RadioStation.registerClientHook(new RadioStation.ClientFetcherHook() {
            @Override
            public java.util.List<RadioStation> getForCountry(String countryCode) {
                return LocalStationFetcher.getStationsForCountry(countryCode);
            }

            @Override
            public java.util.List<RadioStation> getLocal() {
                return LocalStationFetcher.getLocalStations();
            }

            @Override
            public boolean isCountryReady(String countryCode) {
                return LocalStationFetcher.isReady(countryCode);
            }

            @Override
            public boolean isLocalReady() {
                return LocalStationFetcher.isLocalReady();
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.RADIO_MENU.get(), RadioScreen::new);
    }
}
