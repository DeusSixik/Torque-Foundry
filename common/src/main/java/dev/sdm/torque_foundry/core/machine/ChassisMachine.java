package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.core.item.GearItem;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * Шасси коробки: корпус с входом и выходом, внутри — одна шестерня
 * (предмет). Пустое шасси мощность не проводит (коробка открыта).
 *
 * <p>Зацепление: шестерня сцеплена с приводной шестернёй входного вала
 * ({@link GearItem#DRIVE_TEETH} зубьев). Передаточное число =
 * DRIVE_TEETH / зубья шестерни: больше зубьев — ниже обороты, выше момент.
 * Внешнее зацепление реверсирует вращение.
 *
 * <p>Рейтинги шестерни (момент/обороты из материала и размера) при
 * превышении дают потери КПД — шестерня «жует» мощность.
 *
 * <p>Порты локальные: вход = NORTH (задняя грань), выход = SOUTH (FACING).
 * Поворот блока через setFacing перестраивает мировые стороны.
 */
public class ChassisMachine extends MechanicalMachine {

    private Direction inputSide = Direction.NORTH;
    private Direction outputSide = Direction.SOUTH;

    /** Зубья установленной шестерни (0 — шасси пусто). */
    private int teeth = 0;

    public ChassisMachine() {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        rebuildPorts();
    }

    @Override
    protected void createDirections() {
        // Порты пересобираются в rebuildPorts()
    }

    /** Есть ли шестерня. */
    public boolean hasGear() {
        return teeth > 0;
    }

    public int getTeeth() {
        return teeth;
    }

    /**
     * Установить/сменить шестерню (предмет). Пересобирает порты:
     * пустое шасси — глухое, с шестернёй — вход/выход.
     */
    public void setGear(ItemStack gearStack) {
        if (gearStack == null || gearStack.isEmpty()
                || !(gearStack.getItem() instanceof GearItem)) {
            setGear(0, null);
            return;
        }
        setGear(GearItem.teethOf(gearStack), GearItem.materialOf(gearStack));
    }

    /**
     * Установить шестерню по данным (зубья + материал). teeth = 0 —
     * опустошить шасси. Пересобирает порты.
     */
    public void setGear(int newTeeth, dev.sdm.torque_foundry.physics.material.PhysicsMaterial material) {
        if (newTeeth <= 0 || material == null) {
            teeth = 0;
            setPassive(false);
            clearPorts();
            return;
        }

        teeth = newTeeth;
        setMaterial(material);
        setPassive(false);
        rebuildPorts();
    }

    /** Поворот блока: мировые стороны входа/выхода следуют за FACING. */
    @Override
    public void setFacing(Direction facing) {
        super.setFacing(facing);
        this.outputSide = facing;
        this.inputSide = facing.getOpposite();
    }

    private void rebuildPorts() {
        clearPorts();
        if (teeth > 0) {
            port(Direction.NORTH, PortRole.INPUT);
            port(Direction.SOUTH, PortRole.OUTPUT);
        }
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (teeth <= 0 || outputSide != this.outputSide) {
            return input;
        }

        // Зацепление с приводной шестернёй (8 зубьев):
        // больше зубьев шестерни — ниже обороты, выше момент
        final long outSpeedRaw = input.getSpeedRaw() * GearItem.DRIVE_TEETH / teeth;
        long outTorqueRaw = input.getTorqueRaw() * teeth / GearItem.DRIVE_TEETH;

        // Рейтинг момента: превышение — шестерня «жует» мощность
        final dev.sdm.torque_foundry.physics.material.PhysicsMaterial material = getMaterial();
        final double ratedTorqueNm = GearItem.ratedTorqueNm(material, teeth);
        final long ratedTorqueRaw = Math.round(ratedTorqueNm * 1000.0);
        if (outTorqueRaw > ratedTorqueRaw) {
            final double excess = (outTorqueRaw - ratedTorqueRaw) / (double) ratedTorqueRaw;
            outTorqueRaw = Math.round(outTorqueRaw - outTorqueRaw * excess * 0.01);
        }

        // Рейтинг оборотов: перегруз по скорости — потери на зуб
        final long ratedSpeedRaw = GearItem.ratedSpeedRpm(material) * 1000L;
        if (input.getSpeedRaw() > ratedSpeedRaw) {
            final double excess = (input.getSpeedRaw() - ratedSpeedRaw) / (double) ratedSpeedRaw;
            outTorqueRaw = Math.round(outTorqueRaw - outTorqueRaw * excess * 0.01);
        }

        // Внешнее зацепление реверсирует вращение
        return RotationalPower.fromRaw(outSpeedRaw, Math.max(0, outTorqueRaw),
                RotationDirection.opposite(input.getDirection()));
    }
}
