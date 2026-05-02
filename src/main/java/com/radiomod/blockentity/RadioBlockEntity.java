package com.radiomod.blockentity;

import com.radiomod.screen.RadioMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BlockEntity for the radio block. Stores the current station and a UUID
 * for unique identification across level boundaries (including Sable SubLevels).
 */
public class RadioBlockEntity extends BlockEntity implements MenuProvider {

    private String currentStation = "off";
    private UUID radioId = UUID.randomUUID();

    private static final ConcurrentHashMap<String, WeakReference<RadioBlockEntity>>
            REGISTRY = new ConcurrentHashMap<>();

    /**
     * Finds a RadioBlockEntity by its radioKey across loaded levels.
     *
     * @param radioKey Format "radiomod:<UUID>"
     * @return The found BlockEntity or null if not loaded/removed
     */
    @Nullable
    public static RadioBlockEntity findByKey(String radioKey) {
        WeakReference<RadioBlockEntity> ref = REGISTRY.get(radioKey);
        if (ref == null) return null;
        RadioBlockEntity be = ref.get();
        if (be == null || be.isRemoved()) {
            REGISTRY.remove(radioKey);
            return null;
        }
        return be;
    }

    public RadioBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RADIO_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        REGISTRY.put(getRadioKey(), new WeakReference<>(this));
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        REGISTRY.remove(getRadioKey());
    }

    public UUID getRadioId() {
        return radioId;
    }

    /**
     * Returns the unique string key: "radiomod:<UUID>".
     * Independent of position — remains the same when the block is moved.
     */
    public String getRadioKey() {
        return "radiomod:" + radioId.toString();
    }

    public String getCurrentStation() {
        return currentStation;
    }

    /**
     * Sets the current station and synchronizes the block to clients.
     *
     * @param stationId Station ID or "off" to disable
     */
    public void setCurrentStation(String stationId) {
        this.currentStation = stationId;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("CurrentStation", currentStation);
        tag.putUUID("RadioId", radioId);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        currentStation = tag.getString("CurrentStation");
        if (tag.hasUUID("RadioId")) {
            radioId = tag.getUUID("RadioId");
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putString("CurrentStation", currentStation);
        tag.putUUID("RadioId", radioId);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.radiomod.radio");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new RadioMenu(id, inv, this);
    }
}
