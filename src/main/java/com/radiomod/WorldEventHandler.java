package com.radiomod;

import com.radiomod.blockentity.RadioBlockEntity;
import com.radiomod.network.ModPayloads;
import com.radiomod.radio.RadioPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class WorldEventHandler {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        BlockPos pos = event.getPos();
        BlockEntity be = event.getLevel().getBlockEntity(pos);
        if (be instanceof RadioBlockEntity radio) {
            ModPayloads.StationBroadcastPayload pkt =
                    new ModPayloads.StationBroadcastPayload(pos, "off", radio.getRadioKey(), "");
            for (ServerPlayer sp : ((ServerLevel) event.getLevel()).getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(sp, pkt);
            }
        }
    }

    // Registered separately in RadioMod when on CLIENT only
    public static class ClientEvents {
        @SubscribeEvent
        public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            RadioPlayer.stopAll();
        }
    }
}
