package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import net.minecraft.core.Direction;

/**
 * Машина корпуса (Case): пустой корпус мощность не проводит (портов нет),
 * со вставленным валом (shaft_part) становится проходным сегментом вала
 * IN_OUT по оси блока — трение/инерция/износ из материала вставки
 * (ShaftWearHook обрабатывает и корпуса с валом).
 *
 * <p>Опорные точки активны всегда (конструктивно торцы корпуса), но трение
 * и износ опор считаются только для вращающейся механики — пустой корпус
 * без портов никогда не вращается и сеть не нагружает.
 */
public class CaseMachine extends MechanicalMachine {

    /**
     * Материал вставленного вала (null — корпус пуст).
     */
    private PhysicsMaterial shaftMaterial = null;

    public CaseMachine() {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        setPassive(false);
        enableBearingSlots();
    }

    /**
     * Вставлен ли вал.
     */
    public boolean hasShaft() {
        return shaftMaterial != null;
    }

    /**
     * Материал вставки (null — пусто).
     */
    public PhysicsMaterial getShaftMaterial() {
        return shaftMaterial;
    }

    /**
     * Установить вал-вставку: материал, порты по оси, пассивный режим.
     */
    public void setShaft(PhysicsMaterial material) {
        if (material == null) {
            clearShaft();
            return;
        }
        shaftMaterial = material;
        setMaterial(material);
        setPassive(true);
        applyAxisPorts();
    }

    /**
     * Извлечь вал: порты гаснут, материал сбрасывается на корпусной.
     */
    public void clearShaft() {
        shaftMaterial = null;
        setPassive(false);
        clearWorldPorts();
        setMaterial(PhysicsMaterials.DEFAULT);
    }

    /**
     * Вал-вставка: износ по оборотам/моменту применяется (ShaftWearHook).
     */
    @Override
    public boolean isShaftSegment() {
        return hasShaft();
    }

    @Override
    public void setAxis(Direction.Axis axis) {
        super.setAxis(axis);
        if (hasShaft()) {
            applyAxisPorts();
        }
    }

    private void applyAxisPorts() {
        // Мировые порты напрямую: ось берётся из blockstate блока,
        // локальный поворот (facing) для корпуса не применяется
        clearWorldPorts();
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == getAxis()) {
                worldPort(dir, PortRole.IN_OUT);
            }
        }
    }
}
