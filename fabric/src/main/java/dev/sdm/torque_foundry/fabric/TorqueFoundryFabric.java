package dev.sdm.torque_foundry.fabric;

import dev.sdm.torque_foundry.TorqueFoundry;
import net.fabricmc.api.ModInitializer;

public final class TorqueFoundryFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        // Run our common setup.
        TorqueFoundry.init();
    }
}
