package com.radiomod;

import com.radiomod.radio.RadioPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public class ClientTickHandler {

    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        tickCounter++;
        if (tickCounter >= 5) {
            tickCounter = 0;
            RadioPlayer.tickAll();
        }
    }
}
