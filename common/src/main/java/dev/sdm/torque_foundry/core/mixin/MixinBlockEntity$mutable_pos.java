package dev.sdm.torque_foundry.core.mixin;

import dev.sdm.torque_foundry.api.access.BlockEntityAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockEntity.class)
public class MixinBlockEntity$mutable_pos implements BlockEntityAccess {

    @Mutable
    @Shadow
    @Final
    protected BlockPos worldPosition;

    @Override
    public void tq$setPos(BlockPos in) {
        if(this.worldPosition instanceof BlockPos.MutableBlockPos pos) {
            pos.set(in);
        } else {
            this.worldPosition = in.mutable();
        }
    }

    @Override
    public void tq$setPos(int x, int y, int z) {
        if(this.worldPosition instanceof BlockPos.MutableBlockPos pos) {
            pos.set(x, y, z);
        } else {
            this.worldPosition = new BlockPos.MutableBlockPos(x, y, z);
        }
    }
}
