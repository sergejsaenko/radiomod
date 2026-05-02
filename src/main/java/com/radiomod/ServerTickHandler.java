package com.radiomod;

import com.radiomod.blockentity.RadioBlockEntity;
import com.radiomod.network.ModPayloads;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broadcastet alle 10 Ticks die aktuelle Weltposition von SubLevel-Radios an alle Spieler.
 * Ermöglicht es, dass die Audio-Quelle sich mit dem Physik-Objekt mitbewegt.
 */
public class ServerTickHandler {

    private static final int UPDATE_INTERVAL = 10;
    private static final Map<String, ActiveRadio> ACTIVE_SUBLEVEL_RADIOS = new ConcurrentHashMap<>();
    private static int tickCounter = 0;

    /**
     * Registriert ein Radio zur periodischen Position-Übertragung.
     *
     * @param radio     Das RadioBlockEntity auf einem SubLevel
     * @param stationId Aktuelle Stations-ID
     * @param streamUrl Stream-URL für den Broadcast
     */
    public static void trackSubLevelRadio(RadioBlockEntity radio, String stationId, String streamUrl) {
        ACTIVE_SUBLEVEL_RADIOS.put(radio.getRadioKey(), new ActiveRadio(radio, stationId, streamUrl));
    }

    /**
     * Entfernt ein Radio aus dem Tracking (bei "off" oder Block-Zerstörung).
     *
     * @param radioKey Der eindeutige Key des Radios
     */
    public static void untrackRadio(String radioKey) {
        ACTIVE_SUBLEVEL_RADIOS.remove(radioKey);
    }

    /**
     * Wird jeden Server-Tick aufgerufen. Alle 10 Ticks werden die Weltpositionen
     * der getrackten SubLevel-Radios per Sable aufgelöst und an alle Spieler gesendet.
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_SUBLEVEL_RADIOS.isEmpty()) return;

        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        Iterator<Map.Entry<String, ActiveRadio>> it = ACTIVE_SUBLEVEL_RADIOS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ActiveRadio> entry = it.next();
            ActiveRadio ar = entry.getValue();
            RadioBlockEntity radio = ar.ref.get();

            if (radio == null || radio.isRemoved()) {
                it.remove();
                continue;
            }

            if ("off".equals(radio.getCurrentStation()) || radio.getCurrentStation().isBlank()) {
                it.remove();
                continue;
            }

            Vec3 worldPos = ModPayloads.resolveWorldPosition(radio);
            if (worldPos == null) continue;

            ModPayloads.StationBroadcastPayload update = new ModPayloads.StationBroadcastPayload(
                    BlockPos.containing(worldPos), ar.stationId, radio.getRadioKey(), ar.streamUrl
            );

            if (radio.getLevel() != null && radio.getLevel().getServer() != null) {
                for (ServerPlayer sp : radio.getLevel().getServer().getPlayerList().getPlayers()) {
                    PacketDistributor.sendToPlayer(sp, update);
                }
            }
        }
    }

    private record ActiveRadio(WeakReference<RadioBlockEntity> ref, String stationId, String streamUrl) {
        ActiveRadio(RadioBlockEntity radio, String stationId, String streamUrl) {
            this(new WeakReference<>(radio), stationId, streamUrl);
        }
    }
}
