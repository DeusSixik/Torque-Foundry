package dev.sdm.torque_foundry.neoforge;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.render.MechanicalItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

@Mod(TorqueFoundry.MOD_ID)
public final class TorqueFoundryNeoForge {
    public TorqueFoundryNeoForge(IEventBus modEventBus) {
        // Run our common setup.
        TorqueFoundry.init();
        modEventBus.addListener(this::onRegisterClientExtensions);
    }

    private void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        MechanicalItemRenderer.registerDefaults();
        event.registerItem(new IClientItemExtensions() {
            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                // Лениво: диспетчер рендера жив только после старта клиента.
                return MechanicalItemRenderer.get();
            }
        }, MechanicalItemRenderer.registeredItems().toArray(new Item[0]));
    }
}
