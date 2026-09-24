package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.hook.impl.ShaftWearHook;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Износ валов на оборотах выше безопасного лимита материала.
 *
 * <p>Семантика после фазы B3 (делёж входа по спросу): ненагруженная цепь
 * не передаёт момента вовсе (закон раздела 4, P_in = Σ P_out + P_loss),
 * поэтому «полный момент на последнем валу без потребителя» — признак
 * копирования мощности, а не износа. Износ режет ПОТОЛОК ребра
 * (ShaftWearHook в фазе A), и проявляется он при насыщении ветви спросом.
 * Формула хука проверяется напрямую, поверх — интеграционный дым:
 * нагруженная цепь через деревянный вал продолжает работать.
 */
public class MaterialWearTest {

    private static final long GEN_SPEED = 256_000;  // 256 RPM
    private static final long GEN_TORQUE = 64_000;  // 64 Nm

    /** Допуск на износ: округление лимита материала и доли превышения. */
    private static final long WEAR_EPSILON = 5;

    @Test
    void wearHook_ironShaftAtSafeSpeed_noTorqueLoss() {
        // Чугун: лимит ~301 RPM > 256 RPM сети — износа нет
        final RotationalPower power = RotationalPower.fromRaw(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        final RotationalPower after = runWearHook(PhysicsMaterials.IRON, power);

        assertEquals(GEN_TORQUE, after.getTorqueRaw(),
                "iron shafts at safe speed must not lose torque");
        assertEquals(GEN_SPEED, after.getSpeedRaw(),
                "wear cuts torque, not network speed");
    }

    @Test
    void wearHook_woodShaftAtHighSpeed_cutsTorqueByExcessShare() {
        // Дерево: лимит ~107 RPM < 256 RPM -> потеря ~1% за единицу превышения:
        // excess/safe = (256-107)/107 = 1.3925, loss = 64000 * 1.3925 * 0.01 = 891
        final RotationalPower power = RotationalPower.fromRaw(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        final RotationalPower after = runWearHook(PhysicsMaterials.WOOD, power);

        assertEquals(GEN_TORQUE - 891, after.getTorqueRaw(), WEAR_EPSILON,
                "wood shaft at 256 RPM must lose ~1.4% of transmitted torque");
        assertTrue(after.getTorqueRaw() > 0, "but not lose everything");
        assertEquals(GEN_SPEED, after.getSpeedRaw(),
                "wear cuts torque, not network speed");
    }

    @Test
    void wearHook_steelShaftAtSafeSpeed_noTorqueLoss() {
        // Сталь: лимит ~337 RPM — запас огромный
        final RotationalPower power = RotationalPower.fromRaw(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        final RotationalPower after = runWearHook(PhysicsMaterials.STEEL, power);

        assertEquals(GEN_TORQUE, after.getTorqueRaw(),
                "steel shafts must not lose torque at 256 RPM");
    }

    @Test
    void wearHook_zeroTorque_noLoss() {
        // Нулевой момент не может потерять отрицательную величину
        final RotationalPower power = RotationalPower.fromRaw(
                GEN_SPEED, 0, RotationDirection.FORWARD);
        final RotationalPower after = runWearHook(PhysicsMaterials.WOOD, power);

        assertEquals(0, after.getTorqueRaw());
    }

    @Test
    void loadedChainThroughWoodShaft_consumerStillFed() {
        // Интеграционный дым: потребитель 32 Nm через деревянный вал
        // на 256 RPM получает свой спрос целиком (32k << потолка ~63k),
        // сеть крутится, износ не рвёт рабочую линию
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine gen = new GeneratorMachine(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(1, 0, 0));
        shaft.setMaterial(PhysicsMaterials.WOOD);
        group.addElement(shaft);

        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState(),
                "wood shaft must still feed a consumer well under the wear cap");
        assertEquals(32_000, consumer.getReceived().getTorqueRaw(), 100,
                "consumer receives its demand, not a full copy of input");
        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0,
                "network plateaus at source speed");
    }

    // --- хелперы ---

    /**
     * Цепь насыщения: генератор 64 Н·м -> вал из материала -> потребитель,
     * требующий все 64 Н·м. Позиции вдоль X: генератор отдаёт на EAST,
     * потребитель принимает со всех горизонтальных граней.
     */
    private RotationalPower runWearHook(dev.sdm.torque_foundry.physics.material.PhysicsMaterial material,
                                        RotationalPower power) {
        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setMaterial(material);
        return new ShaftWearHook().onTransmit(shaft, null, power, null);
    }
}
