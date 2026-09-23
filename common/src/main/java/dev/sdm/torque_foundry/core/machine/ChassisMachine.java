package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.api.debug.DebugInfoCollector;
import dev.sdm.torque_foundry.core.item.GearItem;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * Шасси коробки: корпус с ядром-вставкой. Ядро — шестерня ИЛИ вал
 * (взаимоисключаемо). Пустое шасси мощность не проводит (коробка открыта).
 *
 * <p>Шестерня: зацепление с приводной шестернёй входного вала
 * ({@link GearItem#DRIVE_TEETH} зубьев), редукция + реверс, порты
 * вход(NORTH)/выход(SOUTH) локально — поворачиваются с FACING.
 *
 * <p>Вал: шасси становится проходным сегментом вала IN_OUT по оси FACING
 * (материал вала — трение/инерция/лимит износа через ShaftWearHook).
 *
 * <p>Рейтинги шестерни (момент/обороты из материала и размера) при
 * превышении дают потери КПД — шестерня «жует» мощность.
 */
public class ChassisMachine extends MechanicalMachine {

    private Direction inputSide = Direction.NORTH;
    private Direction outputSide = Direction.SOUTH;

    /**
     * Зубья установленной шестерни (0 — не шестерня).
     */
    private int teeth = 0;

    /**
     * Режим вала: шасси = проходной вал из материала.
     */
    private PhysicsMaterial shaftMaterial = null;

    public ChassisMachine() {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        rebuildPorts();
    }

    @Override
    protected void createDirections() {
        // Порты пересобираются в rebuildPorts()
    }

    /**
     * Есть ли вставка (шестерня или вал).
     */
    public boolean hasCore() {
        return teeth > 0 || shaftMaterial != null;
    }

    /**
     * Режим вала (шасси = проходной вал).
     */
    public boolean isShaftMode() {
        return teeth == 0 && shaftMaterial != null;
    }

    public int getTeeth() {
        return teeth;
    }

    /**
     * Материал вала-вставки (null — не вал).
     */
    public PhysicsMaterial getShaftMaterial() {
        return shaftMaterial;
    }

    /**
     * Вал-вставка: износ по оборотам/моменту применяется (ShaftWearHook).
     */
    @Override
    public boolean isShaftSegment() {
        return isShaftMode();
    }

    /**
     * Установить/сменить шестерню (предмет). Пересобирает порты:
     * пустое шасси — глухое, с шестернёй — вход/выход.
     */
    public void setGear(ItemStack gearStack) {
        if (gearStack == null || gearStack.isEmpty()
                || !(gearStack.getItem() instanceof GearItem)) {
            clearCore();
            return;
        }
        setGear(GearItem.teethOf(gearStack), GearItem.materialOf(gearStack));
    }

    /**
     * Установить шестерню по данным (зубья + материал). teeth = 0 —
     * опустошить шасси. Пересобирает порты.
     */
    public void setGear(int newTeeth, PhysicsMaterial material) {
        if (newTeeth <= 0 || material == null) {
            clearCore();
            return;
        }

        teeth = newTeeth;
        shaftMaterial = null;
        setMaterial(material);
        setPassive(false);
        rebuildPorts();
    }

    /**
     * Установить вал-вставку: шасси = проходной вал из материала.
     */
    public void setShaft(PhysicsMaterial material) {
        if (material == null) {
            clearCore();
            return;
        }
        teeth = 0;
        shaftMaterial = material;
        setMaterial(material);
        setPassive(true);
        rebuildPorts();
    }

    /**
     * Извлечь ядро (шасси пусто).
     */
    public void clearCore() {
        teeth = 0;
        shaftMaterial = null;
        setPassive(false);
        clearPorts();
    }

    /**
     * Поворот блока: мировые стороны входа/выхода следуют за FACING.
     */
    @Override
    public void setFacing(Direction facing) {
        super.setFacing(facing);
        this.outputSide = facing;
        this.inputSide = facing.getOpposite();
        if (isShaftMode()) {
            // Ось вала = ось FACING: порты могли смениться
            rebuildPorts();
        }
    }

    private void rebuildPorts() {
        clearPorts();
        if (teeth > 0) {
            // Редуктор: вход/выход локально (поворачиваются с FACING)
            port(Direction.NORTH, PortRole.INPUT);
            port(Direction.SOUTH, PortRole.OUTPUT);
        } else if (isShaftMode()) {
            // Проходной вал: IN_OUT на обоих торцах оси FACING (мировые)
            final Direction.Axis axis = getFacing().getAxis();
            for (Direction dir : Direction.values()) {
                if (dir.getAxis() == axis) {
                    worldPort(dir, PortRole.IN_OUT);
                }
            }
        }
    }

    @Override
    public RotationalPower transform(RotationalPower input, Direction outputSide) {
        if (teeth <= 0 || outputSide != this.outputSide) {
            // Вал-режим: passthrough (износ применит ShaftWearHook по ребру)
            return input;
        }

        // Зацепление с приводной шестернёй (8 зубьев):
        // больше зубьев шестерни — ниже обороты, выше момент
        final long outSpeedRaw = input.getSpeedRaw() * GearItem.DRIVE_TEETH / teeth;
        long outTorqueRaw = input.getTorqueRaw() * teeth / GearItem.DRIVE_TEETH;

        // Рейтинг момента: превышение — шестерня «жует» мощность
        final PhysicsMaterial material = getMaterial();
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

    /**
     * Секция Chassis: вставка (шестерня/вал) и её рейтинги.
     */
    @Override
    public void addDebugInfo(DebugInfoCollector collector) {
        super.addDebugInfo(collector);
        collector.section("Chassis");
        if (!hasCore()) {
            collector.alertKey("Chassis.core", "Core", "empty (open box, no power)", true);
            return;
        }
        if (isShaftMode()) {
            collector.addKey("Chassis.core", "Core",
                    "shaft insert: " + getShaftMaterial().name());
        } else {
            collector.addKey("Chassis.core", "Core",
                    "gear: " + teeth + " teeth, " + getMaterial().name());
            collector.hintKey("Chassis.ratio", "Ratio",
                    GearItem.DRIVE_TEETH + "/" + teeth + " (drive pinion "
                            + GearItem.DRIVE_TEETH + "T, external mesh reverses)",
                    "Больше зубьев — ниже обороты, выше момент");
        }
    }
}
