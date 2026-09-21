package dev.sdm.torque_foundry.fabric.client;

import dev.sdm.torque_foundry.api.render.MechanicalItemRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BuiltinItemRendererRegistry;

public final class TorqueFoundryFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        MechanicalItemRenderer.registerDefaults();
        for (var item : MechanicalItemRenderer.registeredItems()) {
            BuiltinItemRendererRegistry.INSTANCE.register(item,
                    (stack, mode, matrices, buffers, light, overlay) ->
                            MechanicalItemRenderer.get().renderByItem(
                                    stack, mode, matrices, buffers, light, overlay));
        }
    }
}
