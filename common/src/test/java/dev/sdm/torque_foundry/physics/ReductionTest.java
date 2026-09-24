package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.GearboxMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приведение к базовому уровню сети через скоростные отношения (С1 п.3):
 * инерция — квадрат отношения, моменты и пороги потребителей — отношение.
 *
 * <p>До приведения нагрузка потребителя за понижающей ступенью считалась
 * сети полным моментом его стороны, инерция — наивной суммой: редуктор
 * ничего не весил и ничего не разгружал.
 */
public class ReductionTest {

    /** Тяжёлая деталь: вал с большой дополнительной инерцией ротора. */
    static class FlywheelStub extends ShaftMachine {
        private final double inertia;

        FlywheelStub(double inertia, BlockPos pos) {
            setBlockPos(pos);
            this.inertia = inertia;
        }

        @Override
        public double getExtraInertia() {
            return inertia;
        }
    }

    /** Сеть раскрутки: генератор -> редуктор (или вал) -> тяжёлая деталь.
     *  Потребителя нет: чистый разгон инерцией и трением. */
    private static MechanicalGroup spinRig(boolean throughReducer, double flywheelInertia) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        if (throughReducer) {
            final GearboxMachine box = new GearboxMachine(4, false, false,
                    Direction.WEST, Direction.EAST);
            box.setBlockPos(new BlockPos(1, 0, 0));
            group.addElement(box);
            group.addElement(new FlywheelStub(flywheelInertia, new BlockPos(2, 0, 0)));
        } else {
            group.addElement(new ShaftMachine() {{
                setBlockPos(new BlockPos(1, 0, 0));
            }});
            group.addElement(new FlywheelStub(flywheelInertia, new BlockPos(2, 0, 0)));
        }
        return group;
    }

    @Test
    void inertiaBehindReducer_reducedToBaseLevel() {
        // Маховик за понижающей ступенью u=4 вкладывает в сеть J/16:
        // сеть с редуктором разгоняется заметно быстрее такой же сети,
        // где тот же маховик стоит на базовом уровне
        final MechanicalGroup plain = spinRig(false, 160.0);
        final MechanicalGroup reduced = spinRig(true, 160.0);

        for (int t = 0; t < 10; t++) {
            plain.computeTick();
            reduced.computeTick();
        }

        final double plainSpeed = plain.getCurrentSpeedRpm();
        final double reducedSpeed = reduced.getCurrentSpeedRpm();
        assertTrue(reducedSpeed > plainSpeed * 3.0,
                "flywheel behind u=4 reducer must count as J/16: reduced "
                        + reducedSpeed + " vs plain " + plainSpeed + " RPM after 10 ticks");
    }

    @Test
    void inertiaBehindStepUp_amplifiedToBaseLevel() {
        // Зеркальный случай: маховик за ПОВЫШАЮЩЕЙ (скорость x4) грузит
        // сеть x16 — разгон медленнее, чем с маховиком на базовом уровне
        final MechanicalGroup plain = spinRig(false, 160.0);
        final MechanicalGroup boosted = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        boosted.addElement(gen);
        final GearboxMachine stepUp = new GearboxMachine(4, true, false,
                Direction.WEST, Direction.EAST);
        stepUp.setBlockPos(new BlockPos(1, 0, 0));
        boosted.addElement(stepUp);
        boosted.addElement(new FlywheelStub(160.0, new BlockPos(2, 0, 0)));

        for (int t = 0; t < 10; t++) {
            plain.computeTick();
            boosted.computeTick();
        }

        assertTrue(boosted.getCurrentSpeedRpm() < plain.getCurrentSpeedRpm(),
                "flywheel behind step-up must count as x16: boosted "
                        + boosted.getCurrentSpeedRpm() + " vs plain "
                        + plain.getCurrentSpeedRpm() + " RPM");
    }

    @Test
    void consumerBehindReducer_loadsNetworkWithReducedTorque() {
        // Регрессия: потребитель 64 Nm за понижающей u=4 грузит сеть 16 Nm.
        // До приведения нагрузка шла полным моментом: генератор 48 Nm вставал
        // в жёсткий клин (64 > 48), хотя приведённая нагрузка 16 Nm + трение
        // оставляют запас
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 48_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final GearboxMachine box = new GearboxMachine(4, false, false,
                Direction.WEST, Direction.EAST);
        box.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(box);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 64_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState(),
                "48 Nm source feeds 64 Nm consumer through u=4: load is 16 Nm");
        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0,
                "network reaches source speed");
    }

    @Test
    void consumerBehindReducer_thresholdRisesWithRatio() {
        // Порог оборотов потребителя тоже на его стороне: сеть 128 RPM через
        // u=4 даёт на выходе 32 RPM — потребитель 64 RPM не включается,
        // хотя раньше сеть формально «прошла» его порог 64 RPM
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                128_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        final GearboxMachine box = new GearboxMachine(4, false, false,
                Direction.WEST, Direction.EAST);
        box.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(box);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 16_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.IDLE, consumer.getWorkState(),
                "u=4 from 128 RPM gives 32 RPM: below the 64 RPM requirement");
        assertTrue(group.getCurrentSpeedRpm() < 129.0,
                "network plateaus at source speed");
    }
}
