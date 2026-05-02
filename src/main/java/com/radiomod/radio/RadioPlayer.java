package com.radiomod.radio;

import com.mojang.logging.LogUtils;
import com.radiomod.blockentity.RadioBlockEntity;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Client-side MP3 stream player with distance attenuation. Decodes MP3 via JLayer
 * and outputs PCM through JavaSound. Supports position updates for moving sources.
 */
@OnlyIn(Dist.CLIENT)
public class RadioPlayer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float BASE_MAX_DISTANCE = 100f;

    private static final Map<String, RadioPlayer> ACTIVE = new HashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "RadioMod-Stream");
        t.setDaemon(true);
        return t;
    });

    private static int globalVolume = 50;
    private static volatile Boolean jlayerAvailable = null;

    private static boolean checkJLayerAvailable() {
        if (jlayerAvailable == null) {
            try {
                Class.forName("javazoom.jl.decoder.Bitstream");
                Class.forName("javazoom.jl.decoder.Decoder");
                Class.forName("javazoom.jl.decoder.Header");
                Class.forName("javazoom.jl.decoder.SampleBuffer");
                jlayerAvailable = true;
            } catch (ClassNotFoundException e) {
                jlayerAvailable = false;
                LOGGER.error("RadioMod: JLayer not found! MP3 playback disabled.");
            }
        }
        return jlayerAvailable;
    }

    /**
     * Starts or updates a station for a specific radio block.
     * If the same station is already playing, only the position is updated (no stream restart).
     *
     * @param blockKey  Unique radio key "radiomod:<UUID>"
     * @param stationId Station ID (or "off" to stop)
     * @param streamUrl Direct MP3 stream URL
     * @param blockPos  World position of the audio source
     */
    public static void setStation(String blockKey, String stationId, String streamUrl, Vec3 blockPos) {
        if (!checkJLayerAvailable()) return;

        RadioPlayer existing = ACTIVE.get(blockKey);
        if (existing != null && stationId.equals(existing.currentStationId)) {
            existing.blockPos = blockPos;
            return;
        }

        stopBlock(blockKey);
        if (stationId.equals("off") || stationId.isBlank()) return;

        String url = (streamUrl != null && !streamUrl.isBlank())
                ? streamUrl
                : RadioStation.getById(stationId).getStreamUrl();
        if (url.isBlank()) {
            LOGGER.warn("RadioMod: No URL for station '{}'", stationId);
            return;
        }

        LOGGER.info("RadioMod: Starting stream '{}' from: {}", stationId, url);
        RadioPlayer player = new RadioPlayer(stationId, blockKey, blockPos);
        ACTIVE.put(blockKey, player);
        player.start(url);
    }

    /**
     * Overload without streamUrl — looks up the URL from the local station registry.
     */
    public static void setStation(String blockKey, String stationId, Vec3 blockPos) {
        RadioStation station = RadioStation.getById(stationId);
        setStation(blockKey, stationId, station.getStreamUrl(), blockPos);
    }

    /**
     * Stops the stream for a specific radio block.
     */
    public static void stopBlock(String blockKey) {
        RadioPlayer p = ACTIVE.remove(blockKey);
        if (p != null) p.stop();
    }

    /**
     * Stops all active streams.
     */
    public static void stopAll() {
        ACTIVE.values().forEach(RadioPlayer::stop);
        ACTIVE.clear();
    }

    /**
     * Sets the global volume (0-100). Also affects the maximum hearing range.
     *
     * @param vol Volume 0-100
     */
    public static void setGlobalVolume(int vol) {
        globalVolume = Math.max(0, Math.min(100, vol));
        ACTIVE.values().forEach(RadioPlayer::updateVolume);
    }

    public static int getGlobalVolume() {
        return globalVolume;
    }

    /**
     * Called every client tick. Updates position and volume
     * of all active streams based on player distance.
     */
    public static void tickAll() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        ACTIVE.values().forEach(p -> {
            p.updateBlockPos();
            p.updateVolume();
        });
    }

    private final String currentStationId;
    private final String radioKey;
    private volatile Vec3 blockPos;

    private volatile boolean running = false;
    private volatile SourceDataLine line;
    private Future<?> future;

    private RadioPlayer(String stationId, String radioKey, Vec3 blockPos) {
        this.currentStationId = stationId;
        this.radioKey = radioKey;
        this.blockPos = blockPos;
    }

    /**
     * Updates the audio source position. For SubLevel blocks, uses Sable reflection
     * to resolve the real world position so audio follows the physics object.
     * Detects when the client-side Sable pose freezes (player far from SubLevel)
     * and falls back to server-side resolution in singleplayer.
     */
    private Vec3 lastSablePos = null;
    private int staleTicks = 0;
    private static final int STALE_THRESHOLD = 20; // ~5 seconds at 5-tick interval

    private void updateBlockPos() {
        RadioBlockEntity be = RadioBlockEntity.findByKey(radioKey);
        if (be == null) return;

        BlockPos bp = be.getBlockPos();
        boolean isSubLevel = Math.abs(bp.getX()) > 1_000_000 || Math.abs(bp.getZ()) > 1_000_000;

        if (isSubLevel) {
            // Try client-side Sable first (always accurate when nearby)
            Vec3 worldPos = tryGetClientSableWorldPos(be);
            if (worldPos != null) {
                // Detect staleness: if position hasn't changed, increment counter
                if (lastSablePos != null && worldPos.distanceToSqr(lastSablePos) < 0.0001) {
                    staleTicks++;
                } else {
                    staleTicks = 0;
                }
                lastSablePos = worldPos;

                // If position is stale (frozen by Sable at distance), try server resolution
                if (staleTicks >= STALE_THRESHOLD) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.getSingleplayerServer() != null) {
                        Vec3 serverResolved = tryResolveViaIntegratedServer(be);
                        if (serverResolved != null) {
                            blockPos = serverResolved;
                            return;
                        }
                    }
                }
                // Use client Sable result (either fresh, or best we have)
                blockPos = worldPos;
            } else {
                // Client Sable failed entirely — try server fallback
                Minecraft mc = Minecraft.getInstance();
                if (mc.getSingleplayerServer() != null) {
                    Vec3 serverResolved = tryResolveViaIntegratedServer(be);
                    if (serverResolved != null) {
                        blockPos = serverResolved;
                    }
                }
            }
        } else {
            blockPos = Vec3.atCenterOf(bp);
        }
    }

    /**
     * In singleplayer (integrated server), resolves the world position using
     * the Sable server-side HELPER singleton (which lives in the same JVM).
     * Only calls the HELPER directly — does NOT access server level/chunk data
     * from the render thread (which could cause threading issues).
     */
    private static Vec3 tryResolveViaIntegratedServer(RadioBlockEntity clientBe) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSingleplayerServer() == null) return null;

        try {
            // Use the server-side Sable resolution (HELPER is a thread-safe singleton)
            return com.radiomod.network.ModPayloads.resolveWorldPosition(clientBe);
        } catch (Exception e) {
            return null;
        }
    }

    private static volatile boolean sableClientChecked = false;
    private static volatile boolean sableClientAvailable = false;

    /**
     * Client-side Sable position resolution via reflection.
     * Tries getContaining(be) → logicalPose().transformPosition(), then Level.logicalPose() as fallback.
     *
     * @param be BlockEntity on a SubLevel
     * @return Vec3 world position, or null on failure
     */
    private static Vec3 tryGetClientSableWorldPos(BlockEntity be) {
        if (!sableClientChecked) {
            try {
                Class.forName("dev.ryanhcode.sable.Sable");
                sableClientAvailable = true;
            } catch (ClassNotFoundException ignored) {
                sableClientAvailable = false;
            }
            sableClientChecked = true;
        }
        if (!sableClientAvailable) return null;

        Vec3 localPos = Vec3.atCenterOf(be.getBlockPos());

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

            if (getContaining != null) {
                try {
                    Object subLevel = getContaining.invoke(helper, be);
                    if (subLevel != null) {
                        Vec3 result = callTransformPosition(subLevel, localPos);
                        if (result != null) return result;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        try {
            Object level = be.getLevel();
            if (level == null) return null;
            java.lang.reflect.Method logicalPoseMethod;
            try {
                logicalPoseMethod = level.getClass().getMethod("logicalPose");
            } catch (NoSuchMethodException ignored) {
                return null;
            }
            Object pose = logicalPoseMethod.invoke(level);
            if (pose == null) return null;
            return callTransformPositionOnPose(pose, localPos);
        } catch (Exception e) {
            return null;
        }
    }

    private static Vec3 callTransformPosition(Object subLevel, Vec3 localPos) {
        try {
            Object pose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            if (pose == null) return null;
            return callTransformPositionOnPose(pose, localPos);
        } catch (Exception e) {
            return null;
        }
    }

    private static Vec3 callTransformPositionOnPose(Object pose, Vec3 localPos) {
        try {
            for (java.lang.reflect.Method m : pose.getClass().getMethods()) {
                if ("transformPosition".equals(m.getName()) && m.getParameterCount() == 1) {
                    if (m.getReturnType() == Vec3.class || m.getParameterTypes()[0] == Vec3.class) {
                        Object result = m.invoke(pose, localPos);
                        if (result instanceof Vec3 vec) return vec;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void start(String url) {
        running = true;
        future = EXECUTOR.submit(() -> {
            while (running) {
                try {
                    URLConnection conn = URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(20000);
                    conn.setRequestProperty("User-Agent", "RadioMod/1.0 Minecraft");
                    conn.setRequestProperty("Icy-MetaData", "0");

                    try (InputStream raw = conn.getInputStream();
                         BufferedInputStream bis = new BufferedInputStream(raw, 128 * 1024)) {
                        streamDecode(bis);
                    }
                } catch (Exception e) {
                    if (running) {
                        LOGGER.warn("RadioMod: Stream error, retry in 3s: {}", e.getMessage());
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                    }
                }
            }
        });
    }

    /**
     * Decodes MP3 frames and outputs them as PCM. Applies the computed
     * gain (volume × distance attenuation) per sample.
     */
    private void streamDecode(InputStream in) throws Exception {
        Bitstream bitstream = new Bitstream(in);
        Decoder decoder = new Decoder();
        SourceDataLine localLine = null;

        try {
            while (running) {
                Header header = bitstream.readFrame();
                if (header == null) break;

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                if (localLine == null) {
                    int sampleRate = (int) header.frequency();
                    int channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    AudioFormat fmt = new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            sampleRate, 16, channels, channels * 2, sampleRate, false
                    );
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                    localLine = (SourceDataLine) AudioSystem.getLine(info);
                    localLine.open(fmt, 8192);
                    localLine.start();
                    line = localLine;
                    updateVolume();
                }

                short[] pcm = output.getBuffer();
                int len = output.getBufferLength();
                float gain = computeLinearGain();
                byte[] bytes = new byte[len * 2];
                for (int i = 0; i < len; i++) {
                    int sample = Math.round(pcm[i] * gain);
                    if (sample > 32767) sample = 32767;
                    if (sample < -32768) sample = -32768;
                    bytes[i * 2] = (byte) (sample & 0xFF);
                    bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                }

                localLine.write(bytes, 0, bytes.length);
                bitstream.closeFrame();
            }
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
            }
            if (localLine != null) {
                localLine.drain();
                localLine.stop();
                localLine.close();
            }
            line = null;
        }
    }

    private void stop() {
        running = false;
        SourceDataLine l = line;
        if (l != null) {
            l.stop();
            l.close();
            line = null;
        }
        if (future != null) {
            future.cancel(true);
            future = null;
        }
    }

    private void updateVolume() {
        SourceDataLine l = line;
        if (l == null || !l.isOpen()) return;
        try {
            FloatControl ctrl = (FloatControl) l.getControl(FloatControl.Type.MASTER_GAIN);
            float db = toDb(computeLinearGain());
            float clamped = Math.max(ctrl.getMinimum(), Math.min(ctrl.getMaximum(), db));
            ctrl.setValue(clamped);
        } catch (Exception ignored) {
        }
    }

    /**
     * Computes the linear gain multiplier [0..1] based on volume and distance.
     * Maximum range scales linearly with volume (vol=100 → 200 blocks).
     * Quadratic fadeout over distance.
     *
     * @return Gain value 0.0 (silent) to 1.0 (full volume)
     */
    private float computeLinearGain() {
        float userGain = globalVolume / 100f;
        if (userGain <= 0f) return 0f;

        float maxDist = BASE_MAX_DISTANCE * 2f * userGain;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return userGain;

        double dx = mc.player.getX() - blockPos.x;
        double dy = mc.player.getY() - blockPos.y;
        double dz = mc.player.getZ() - blockPos.z;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist >= maxDist) return 0f;

        float distGain = 1f - (dist / maxDist);
        distGain = distGain * distGain;

        return userGain * distGain;
    }

    private static float toDb(float linear) {
        if (linear <= 0f) return -80f;
        return (float) (Math.log10(linear) * 20.0);
    }
}
