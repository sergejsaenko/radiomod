package com.radiomod.blockentity;

import com.radiomod.RadioMod;
import com.radiomod.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RadioMod.MODID);

    @SuppressWarnings("unchecked")
    public static final Supplier<BlockEntityType<RadioBlockEntity>> RADIO_BLOCK_ENTITY =
            (Supplier<BlockEntityType<RadioBlockEntity>>) (Supplier<?>) BLOCK_ENTITIES.register(
                    "radio_block_entity",
                    () -> BlockEntityType.Builder.of(RadioBlockEntity::new, ModBlocks.RADIO_BLOCK.get()).build(null)
            );

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
    }
}
