package com.radiomod.screen;

import com.radiomod.RadioMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, RadioMod.MODID);

    @SuppressWarnings("unchecked")
    public static final Supplier<MenuType<RadioMenu>> RADIO_MENU =
            (Supplier<MenuType<RadioMenu>>) (Supplier<?>) MENU_TYPES.register("radio_menu",
                    () -> IMenuTypeExtension.create(RadioMenu::fromNetwork)
            );

    public static void register(IEventBus bus) {
        MENU_TYPES.register(bus);
    }
}
