package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.machine.ChassisMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * BlockEntity шасси: хранит установленную шестерню (ItemStack),
 * синхронизирует её с машиной. Шестерня сохраняется между перезапусками.
 */
public class ChassisBlockEntity extends MechanicalBlockEntity {

    private static final String TAG_GEAR = "Gear";

    /** Установленная шестерня (или EMPTY). */
    private ItemStack gear = ItemStack.EMPTY;

    public ChassisBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.CHASSIS.get(), blockPos, blockState);
        applyGearToMachine();
    }

    @Override
    protected MechanicalMachine createMachine(RotationalPower power) {
        return new ChassisMachine();
    }

    public ChassisMachine getChassis() {
        return (ChassisMachine) this.machine;
    }

    public ItemStack getGear() {
        return gear;
    }

    /** Вставить шестерню (заменяет старую — старая возвращается игроку). */
    public void setGear(ItemStack stack) {
        this.gear = stack.copy();
        setChanged();
        applyGearToMachine();
    }

    /** Извлечь шестерню (игроку), шасси остаётся пустым. */
    public ItemStack takeGear() {
        final ItemStack out = gear.copy();
        gear = ItemStack.EMPTY;
        setChanged();
        applyGearToMachine();
        return out;
    }

    /** Синхронизация слота -> машина (пересборка портов/передаточного числа). */
    private void applyGearToMachine() {
        getChassis().setGear(gear);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!gear.isEmpty()) {
            tag.put(TAG_GEAR, gear.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_GEAR)) {
            gear = ItemStack.parse(registries, tag.getCompound(TAG_GEAR)).orElse(ItemStack.EMPTY);
        } else {
            gear = ItemStack.EMPTY;
        }
        applyGearToMachine();
    }
}
