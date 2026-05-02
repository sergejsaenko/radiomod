package com.radiomod.screen;

import com.radiomod.blockentity.RadioBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class RadioMenu extends AbstractContainerMenu {

    public final RadioBlockEntity blockEntity;

    // Called server-side from RadioBlockEntity#createMenu
    public RadioMenu(int id, Inventory inv, RadioBlockEntity be) {
        super(ModMenuTypes.RADIO_MENU.get(), id);
        this.blockEntity = be;
    }

    // Called client-side via the registered factory
    public static RadioMenu fromNetwork(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        // Primary lookup: block exists in the current client level (normal / main-world case).
        net.minecraft.world.level.block.entity.BlockEntity raw = inv.player.level().getBlockEntity(pos);
        RadioBlockEntity be = raw instanceof RadioBlockEntity r ? r : null;

        // If null the block is either in a Sable SubLevel or the chunk is not yet loaded.
        // We still proceed – the menu will render, and station-selection packets include
        // the radioKey so the server can locate the entity via its global registry.
        // A null blockEntity is handled defensively in the rest of the menu / screen.
        return new RadioMenu(id, inv, be);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (blockEntity == null) return false;

        // Normal proximity check for plain-world radios.
        double distSq = player.distanceToSqr(
                blockEntity.getBlockPos().getX() + .5,
                blockEntity.getBlockPos().getY() + .5,
                blockEntity.getBlockPos().getZ() + .5);

        if (distSq <= 64) return true;

        // If the blockPos is a local SubLevel position the distance to the player's world
        // position may be arbitrarily large.  Detect this by checking whether the radio's
        // level is one of the server's registered levels.
        // On the client side we cannot do that check, so we allow the menu to stay open
        // whenever the block entity appears to be in a non-standard (SubLevel) context.
        // The server will enforce station-selection validity via its own registry lookup.
        try {
            if (blockEntity.getLevel() instanceof net.minecraft.server.level.ServerLevel sl) {
                // Server context: the level IS registered → it's a normal world → enforce distance
                sl.getServer(); // just confirm it works without exception
                return false;   // distSq > 64 and we're on the server → close menu
            }
        } catch (Exception ignored) { /* not a ServerLevel or no server ref */ }

        // Client side or SubLevel (physics object) — keep the menu open
        return true;
    }
}
