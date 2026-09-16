package dev.sdm.torque_foundry;

import com.mojang.logging.LogUtils;
import dev.architectury.platform.Platform;
import dev.sdm.torque_foundry.core.block.TFBlockEntities;
import dev.sdm.torque_foundry.core.block.TFBlocks;
import dev.sdm.torque_foundry.debug.physics.DebugRegisters;
import net.fabricmc.api.EnvType;
import org.slf4j.Logger;

public final class TorqueFoundry {

    public static Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "torque_foundry";

    public static void init() {
        // Write common init code here.

        TFBlocks.register();
        TFBlockEntities.register();

        if(Platform.getEnv() == EnvType.CLIENT) {
            DebugRegisters.tryInstall();
        }
    }
}
