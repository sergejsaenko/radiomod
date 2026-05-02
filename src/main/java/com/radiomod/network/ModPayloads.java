package com.radiomod.network;

import com.mojang.logging.LogUtils;
import com.radiomod.RadioMod;
import com.radiomod.blockentity.RadioBlockEntity;
import com.radiomod.radio.RadioPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Network payloads for the Radio mod. Manages station selection (Client→Server)
 * and station broadcasts (Server→Client), including Sable SubLevel position resolution.
 */
public class ModPayloads {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Client -> Server: player selects a station.
     * Contains radioKey for SubLevel lookup and streamUrl so the server can forward it directly.
     */
    public record SelectStationPayload(BlockPos pos, String stationId, String radioKey, String streamUrl)
            implements CustomPacketPayload {

        public SelectStationPayload(BlockPos pos, String stationId, String radioKey) {
            this(pos, stationId, radioKey, "");
        }

        public static final Type<SelectStationPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "select_station"));

        public static final StreamCodec<FriendlyByteBuf, SelectStationPayload> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, SelectStationPayload::pos,
                        ByteBufCodecs.STRING_UTF8, SelectStationPayload::stationId,
                        ByteBufCodecs.STRING_UTF8, SelectStationPayload::radioKey,
                        ByteBufCodecs.STRING_UTF8, SelectStationPayload::streamUrl,
                        SelectStationPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server -> Client: informs players about an active station of a radio
     * including the resolved world position (used for distance attenuation).
     */
    public record StationBroadcastPayload(BlockPos pos, String stationId, String radioKey, String streamUrl)
            implements CustomPacketPayload {

        public static final Type<StationBroadcastPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "station_broadcast"));

        public static final StreamCodec<FriendlyByteBuf, StationBroadcastPayload> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, StationBroadcastPayload::pos,
                        ByteBufCodecs.STRING_UTF8, StationBroadcastPayload::stationId,
                        ByteBufCodecs.STRING_UTF8, StationBroadcastPayload::radioKey,
                        ByteBufCodecs.STRING_UTF8, StationBroadcastPayload::streamUrl,
                        StationBroadcastPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    @SubscribeEvent
    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar("1");
        reg.playToServer(
                SelectStationPayload.TYPE,
                SelectStationPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleSelectStation(payload, ctx))
        );
        reg.playToClient(
                StationBroadcastPayload.TYPE,
                StationBroadcastPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleBroadcast(payload))
        );
    }

    /**
     * Server handler: receives station selection, finds the RadioBlockEntity,
     * resolves the world position and broadcasts to all players.
     */
    private static void handleSelectStation(SelectStationPayload payload, IPayloadContext ctx) {
        ServerPlayer sender = (ServerPlayer) ctx.player();
        LOGGER.info("RadioMod [SERVER]: handleSelectStation from {} - station={}, key={}",
                sender.getName().getString(), payload.stationId(), payload.radioKey());

        RadioBlockEntity radio = null;
        BlockEntity be = sender.level().getBlockEntity(payload.pos());
        if (be instanceof RadioBlockEntity r) {
            radio = r;
        }

        if (radio == null && !payload.radioKey().isEmpty()) {
            radio = RadioBlockEntity.findByKey(payload.radioKey());
        }

        if (radio == null) {
            LOGGER.warn("RadioMod [SERVER]: RadioBlockEntity NOT FOUND! pos={}, key={}", payload.pos(), payload.radioKey());
            return;
        }

        Vec3 worldPos = getEffectiveWorldPos(radio, sender);
        String stationId = payload.stationId();
        String streamUrl = payload.streamUrl();
        radio.setCurrentStation(stationId);

        BlockPos bp = radio.getBlockPos();
        if (Math.abs(bp.getX()) > 1_000_000 || Math.abs(bp.getZ()) > 1_000_000) {
            if ("off".equals(stationId) || stationId.isBlank()) {
                com.radiomod.ServerTickHandler.untrackRadio(radio.getRadioKey());
            } else {
                com.radiomod.ServerTickHandler.trackSubLevelRadio(radio, stationId, streamUrl);
            }
        }

        StationBroadcastPayload broadcast = new StationBroadcastPayload(
                BlockPos.containing(worldPos), stationId, radio.getRadioKey(), streamUrl
        );
        for (ServerPlayer sp : sender.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(sp, broadcast);
        }
    }

    /**
     * Client handler: receives a broadcast, determines the audio position and starts/stops the stream.
     * For SubLevel coordinates (>1M) the player position is used as a fallback.
     */
    private static void handleBroadcast(StationBroadcastPayload payload) {
        Vec3 blockVec;
        BlockPos bpos = payload.pos();
        if (Math.abs(bpos.getX()) > 1_000_000 || Math.abs(bpos.getZ()) > 1_000_000) {
            Minecraft mc = Minecraft.getInstance();
            blockVec = mc.player != null ? mc.player.position() : Vec3.atCenterOf(bpos);
        } else {
            blockVec = Vec3.atCenterOf(bpos);
        }

        if (payload.stationId().equals("off") || payload.stationId().isBlank()) {
            RadioPlayer.stopBlock(payload.radioKey());
        } else {
            RadioPlayer.setStation(payload.radioKey(), payload.stationId(), payload.streamUrl(), blockVec);
        }
    }

    /**
     * Sends a SelectStation packet to the server.
     *
     * @param pos       BlockPos of the radio (may be SubLevel-local)
     * @param stationId ID of the chosen station or "off"
     * @param radioKey  Unique key "radiomod:<UUID>"
     * @param streamUrl Direct stream URL for broadcasting
     */
    public static void sendSelectStation(BlockPos pos, String stationId, String radioKey, String streamUrl) {
        PacketDistributor.sendToServer(new SelectStationPayload(pos, stationId, radioKey, streamUrl));
    }

    public static void sendSelectStation(BlockPos pos, String stationId, String radioKey) {
        sendSelectStation(pos, stationId, radioKey, "");
    }

    public static void sendSelectStation(BlockPos pos, String stationId) {
        sendSelectStation(pos, stationId, "", "");
    }

    /**
     * Resolves the world position of a RadioBlockEntity. For SubLevel blocks Sable is used,
     * otherwise the block center is returned.
     *
     * @param radio The RadioBlockEntity
     * @return Vec3 world position or null on error (SubLevel not resolvable)
     */
    public static Vec3 resolveWorldPosition(RadioBlockEntity radio) {
        BlockPos bp = radio.getBlockPos();
        if (Math.abs(bp.getX()) > 1_000_000 || Math.abs(bp.getZ()) > 1_000_000) {
            Vec3 sablePos = tryGetSableWorldPosition(radio);
            if (sablePos != null) return sablePos;
            return null;
        }
        return Vec3.atCenterOf(bp);
    }

    /**
     * Determines the best world position for a radio. For SubLevel blocks Sable is tried first,
     * then the sender's position is used as a fallback.
     *
     * @param radio  The RadioBlockEntity
     * @param sender The interacting player (fallback position)
     * @return Vec3 world position, never null
     */
    private static Vec3 getEffectiveWorldPos(RadioBlockEntity radio, ServerPlayer sender) {
        Level radioLevel = radio.getLevel();
        if (radioLevel == null) return sender.position();

        BlockPos bp = radio.getBlockPos();
        if (Math.abs(bp.getX()) > 1_000_000 || Math.abs(bp.getZ()) > 1_000_000) {
            Vec3 sablePos = tryGetSableWorldPosition(radio);
            if (sablePos != null) return sablePos;
            return sender.position();
        }

        return Vec3.atCenterOf(bp);
    }

    /**
     * Transforms a SubLevel-local block position into world coordinates using Sable reflection.
     * API chain: Sable.HELPER.getContaining(be) → logicalPose() → transformPosition(localVec3).
     *
     * @param be BlockEntity inside a SubLevel
     * @return Vec3 world position or null on failure
     */
    private static volatile boolean sableChecked = false;
    private static volatile boolean sableAvailable = false;

    private static Vec3 tryGetSableWorldPosition(BlockEntity be) {
        if (!sableChecked) {
            try {
                Class.forName("dev.ryanhcode.sable.Sable");
                sableAvailable = true;
            } catch (ClassNotFoundException ignored) {
                sableAvailable = false;
            }
            sableChecked = true;
        }
        if (!sableAvailable) return null;

        try {
            Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable");
            Object helper = sableClass.getField("HELPER").get(null);

            java.lang.reflect.Method getContaining = null;
            for (java.lang.reflect.Method m : helper.getClass().getMethods()) {
                if ("getContaining".equals(m.getName()) && m.getParameterCount() == 1) {
                    getContaining = m;
                    break;
                }
            }
            if (getContaining == null) return null;

            Object subLevel = getContaining.invoke(helper, be);
            if (subLevel == null) return null;

            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            if (pose == null) return null;

            Vec3 localPos = Vec3.atCenterOf(be.getBlockPos());
            java.lang.reflect.Method transformPos = null;
            for (java.lang.reflect.Method m : pose.getClass().getMethods()) {
                if ("transformPosition".equals(m.getName()) && m.getParameterCount() == 1) {
                    if (m.getReturnType() == Vec3.class || m.getParameterTypes()[0] == Vec3.class) {
                        transformPos = m;
                        break;
                    }
                }
            }
            if (transformPos == null) return null;

            Object worldPos = transformPos.invoke(pose, localPos);
            if (worldPos instanceof Vec3 vec) return vec;
            return null;

        } catch (Exception e) {
            return null;
        }
    }
}

