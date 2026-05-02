package com.radiomod;

import com.radiomod.blockentity.RadioBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.lwjgl.glfw.GLFW;

public class ClientUuidTooltip {

    private static String cachedUUID = null;
    private static String cachedStation = null;

    @SubscribeEvent
    public static void onHudRender(RenderGuiLayerEvent.Post event) {
        // Draw on crosshair layer (id path == "crosshair"), or fallback to any post event.
        if (!event.getName().getPath().equals("crosshair")) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long win = mc.getWindow().getWindow();
        boolean ctrlHeld = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS;

        if (!ctrlHeld) {
            cachedUUID = null;
            cachedStation = null;
            return;
        }

        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit)) {
            cachedUUID = null;
            return;
        }

        BlockEntity be = mc.level.getBlockEntity(blockHit.getBlockPos());
        if (!(be instanceof RadioBlockEntity radio)) {
            cachedUUID = null;
            return;
        }

        cachedUUID = radio.getRadioId().toString();
        cachedStation = radio.getCurrentStation();

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cx = sw / 2;
        int cy = sh / 2;

        var gfx = event.getGuiGraphics();
        int textW = mc.font.width("ID: " + cachedUUID);
        gfx.fill(cx - textW / 2 - 2, cy + 10, cx + textW / 2 + 2, cy + 20, 0x88000000);
        gfx.drawCenteredString(mc.font,
                Component.literal("ID: ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(cachedUUID).withStyle(ChatFormatting.WHITE)),
                cx, cy + 12, 0xFFFFFF);

        if (cachedStation != null && !cachedStation.equals("off")) {
            int stW = mc.font.width("▶ " + cachedStation);
            gfx.fill(cx - stW / 2 - 2, cy + 20, cx + stW / 2 + 2, cy + 30, 0x88000000);
            gfx.drawCenteredString(mc.font,
                    Component.literal("▶ " + cachedStation)
                            .withStyle(ChatFormatting.GOLD),
                    cx, cy + 22, 0xFFAA00);
        }
    }
}
