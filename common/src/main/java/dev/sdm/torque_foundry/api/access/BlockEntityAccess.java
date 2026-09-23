package dev.sdm.torque_foundry.api.access;

import net.minecraft.core.BlockPos;

public interface BlockEntityAccess {

    void tq$setPos(BlockPos pos);

    void tq$setPos(int x, int y, int z);
}
