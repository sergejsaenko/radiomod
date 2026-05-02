package com.radiomod.jei;

import com.radiomod.RadioMod;
import com.radiomod.block.ModBlocks;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public class RadioJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath(RadioMod.MODID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration reg) {
        reg.addIngredientInfo(
                new ItemStack(ModBlocks.RADIO_BLOCK_ITEM.get()),
                VanillaTypes.ITEM_STACK,
                Component.translatable("jei.radiomod.radio_block.desc")
        );
    }
}
