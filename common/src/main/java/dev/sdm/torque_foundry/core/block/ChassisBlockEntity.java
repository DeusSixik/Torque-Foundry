package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.item.GearItem;
import dev.sdm.torque_foundry.core.item.ShaftPartItem;
import dev.sdm.torque_foundry.core.machine.ChassisMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockEntity шасси: хранит ядро-вставку (шестерня ИЛИ вал-предмет),
 * синхронизирует его с машиной. Ядро сохраняется между перезапусками.
 */
public class ChassisBlockEntity extends MechanicalBlockEntity {

    private static final String TAG_CORE = "Core";

    /** Установленное ядро: шестерня или вал-предмет (или EMPTY). */
    private ItemStack core = ItemStack.EMPTY;

    public ChassisBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.CHASSIS.get(), blockPos, blockState);
        applyCoreToMachine();
    }

    @Override
    protected MechanicalMachine createMachine(RotationalPower power) {
        return new ChassisMachine();
    }

    public ChassisMachine getChassis() {
        return (ChassisMachine) this.machine;
    }

    public ItemStack getCore() {
        return core;
    }

    /** Вставить ядро (шестерню или вал; заменяет старое — старое возвращается). */
    public void setCore(ItemStack stack) {
        this.core = stack.copy();
        setChanged();
        applyCoreToMachine();
    }

    /** Извлечь ядро (игроку), шасси остаётся пустым. */
    public ItemStack takeCore() {
        final ItemStack out = core.copy();
        core = ItemStack.EMPTY;
        setChanged();
        applyCoreToMachine();
        return out;
    }

    /** Синхронизация слота -> машина (пересборка портов/режима). */
    private void applyCoreToMachine() {
        final ChassisMachine chassis = getChassis();
        if (core.isEmpty()) {
            chassis.clearCore();
        } else if (core.getItem() instanceof ShaftPartItem) {
            chassis.setShaft(ShaftPartItem.materialOf(core));
        } else if (core.getItem() instanceof GearItem) {
            chassis.setGear(core);
        } else {
            chassis.clearCore();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!core.isEmpty()) {
            tag.put(TAG_CORE, core.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_CORE)) {
            core = ItemStack.parse(registries, tag.getCompound(TAG_CORE)).orElse(ItemStack.EMPTY);
        } else {
            core = ItemStack.EMPTY;
        }
        applyCoreToMachine();
    }
}
