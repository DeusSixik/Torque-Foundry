package dev.sdm.torque_foundry.core.block;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.item.ShaftPartItem;
import dev.sdm.torque_foundry.core.machine.CaseMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * BlockEntity корпуса: хранит вал-вставку (shaft_part), синхронизирует
 * её с машиной. Вставка сохраняется между перезапусками и досылается
 * клиенту (рендер Val в CaseRenderer читает её напрямую).
 */
public class CaseBlockEntity extends MechanicalBlockEntity {

    private static final String TAG_SHAFT = "Shaft";

    /** Вставленный вал (или EMPTY — корпус пуст, только Casing). */
    private ItemStack shaft = ItemStack.EMPTY;

    public CaseBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(TFBlockEntities.CASE.get(), blockPos, blockState);
        applyShaftToMachine();
    }

    @Override
    protected MechanicalMachine createMachine(RotationalPower power) {
        return new CaseMachine();
    }

    public CaseMachine getCase() {
        return (CaseMachine) this.machine;
    }

    public ItemStack getShaft() {
        return shaft;
    }

    /** Вставить вал (заменяет старый — старый возвращается вызывающим). */
    public void setShaft(ItemStack stack) {
        this.shaft = stack.copy();
        setChanged();
        applyShaftToMachine();
    }

    /** Извлечь вал (игроку), корпус остаётся пустым. */
    public ItemStack takeShaft() {
        final ItemStack out = shaft.copy();
        shaft = ItemStack.EMPTY;
        setChanged();
        applyShaftToMachine();
        return out;
    }

    /** Синхронизация слота -> машина (материал, порты, износ). */
    private void applyShaftToMachine() {
        final CaseMachine casing = getCase();
        if (shaft.isEmpty()) {
            casing.clearShaft();
        } else {
            casing.setShaft(ShaftPartItem.materialOf(shaft));
        }
    }

    // --- Синк на клиент (рендер Val + оверлей портов) ---

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!shaft.isEmpty()) {
            tag.put(TAG_SHAFT, shaft.save(registries, new CompoundTag()));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(TAG_SHAFT)) {
            shaft = ItemStack.parse(registries, tag.getCompound(TAG_SHAFT)).orElse(ItemStack.EMPTY);
        } else {
            shaft = ItemStack.EMPTY;
        }
        applyShaftToMachine();
    }
}
