package dev.sdm.torque_foundry.neoforge;

import dev.sdm.torque_foundry.TorqueFoundry;
import net.neoforged.fml.common.Mod;

@Mod(TorqueFoundry.MOD_ID)
public final class TorqueFoundryNeoForge {
    public TorqueFoundryNeoForge() {
        // Run our common setup.
        TorqueFoundry.init();
    }
}
