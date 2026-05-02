package com.radiomod.block;

import com.radiomod.RadioMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, RadioMod.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, RadioMod.MODID);

    public static final Supplier<Block> RADIO_BLOCK = BLOCKS.register("radio_block",
            () -> new RadioBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(1.0f, 3.0f)
                    .sound(SoundType.METAL)
                    .noOcclusion()
            )
    );


    public static final Supplier<Item> RADIO_BLOCK_ITEM = ITEMS.register("radio_block",
            () -> new BlockItem(RADIO_BLOCK.get(), new Item.Properties())
    );

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }
}
