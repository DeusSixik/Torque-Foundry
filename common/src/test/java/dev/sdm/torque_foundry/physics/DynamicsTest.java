package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.basic.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static dev.sdm.torque_foundry.physics.TestRig.SETTLE_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Динамика сети: раскрутка (инерция+тяга), выбег (трение),
 * мгновенный стоп при перегрузе.
 */
public class DynamicsTest {

    private static MechanicalMachine generator(long speed, long torque, BlockPos pos) {
        final MechanicalMachine m = new GeneratorMachine(speed, torque, RotationDirection.FORWARD);
        m.setBlockPos(pos);
        return m;
    }

    private static MechanicalMachine shaft(BlockPos pos) {
        final MechanicalMachine m = new ShaftMachine();
        m.setBlockPos(pos);
        return m;
    }

    private static MechanicalMachine consumer(long speedReq, long torqueReq, BlockPos pos) {
        final MechanicalMachine m = new ConsumerMachine(speedReq, torqueReq, RotationDirection.FORWARD);
        m.setBlockPos(pos);
        return m;
    }

    private static MechanicalGroup group(MechanicalMachine... machines) {
        final MechanicalGroup group = new MechanicalGroup();
        for (MechanicalMachine m : machines) {
            group.addElement(m);
        }
        return group;
    }

    @Test
    void networkSpinsUpSmoothly() {
        // Раскрутка: обороты растут монотонно к цели источника
        final MechanicalGroup group = group(
                generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shaft(new BlockPos(1, 0, 0)),
                shaft(new BlockPos(2, 0, 0)));

        double prev = 0;
        for (int t = 0; t < 100; t++) {
            group.computeTick();
            final double speed = group.getCurrentSpeedRpm();
            assertTrue(speed >= prev - 0.001, "speed must not drop while spinning up: tick " + t);
            prev = speed;
        }

        assertTrue(prev > 200, "network must reach high RPM after spin-up, got " + prev);
    }

    @Test
    void networkReachesPlateau() {
        // Плато: трение и тяга уравновешиваются ниже цели источника
        final MechanicalGroup group = group(
                generator(256_000, 64_000, new BlockPos(0, 0, 0)),
                shaft(new BlockPos(1, 0, 0)));

        for (int t = 0; t < SETTLE_TICKS; t++) {
            group.computeTick();
        }

        final double speed = group.getCurrentSpeedRpm();
        assertTrue(speed > 128, "network must exceed half of source RPM, got " + speed);
        assertTrue(speed <= 256, "network must not exceed source RPM");
    }

    @Test
    void coastDownAfterSourceRemoved() {
        // Выбег: после удаления генератора сеть плавно замедляется трением
        final MechanicalMachine gen = generator(256_000, 64_000, new BlockPos(0, 0, 0));
        final MechanicalMachine shaft1 = shaft(new BlockPos(1, 0, 0));
        final MechanicalMachine shaft2 = shaft(new BlockPos(2, 0, 0));
        final MechanicalGroup group = group(gen, shaft1, shaft2);

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }
        final double before = group.getCurrentSpeedRpm();
        assertTrue(before > 100, "must spin before removal");

        group.removeElement(gen); // убираем генератор

        // Выбег: скорость падает монотонно
        double prev = group.getCurrentSpeedRpm();
        assertTrue(prev > 0, "network must coast after source removal");

        for (int t = 0; t < 500; t++) {
            group.computeTick();
            final double speed = group.getCurrentSpeedRpm();
            assertTrue(speed <= prev + 0.001, "coast-down must not accelerate: tick " + t);
            prev = speed;
            if (speed == 0) {
                break;
            }
        }

        assertTrue(prev < before, "network must slow down: " + before + " -> " + prev);

        // В конце концов трение остановит сеть — и сеть, и coast машин
        for (int t = 0; t < 10_000 && (group.getCurrentSpeedRpm() > 0
                || shaft1.getWorkState() != WorkState.IDLE
                || shaft2.getWorkState() != WorkState.IDLE); t++) {
            group.computeTick();
        }
        assertTrue(group.getCurrentSpeedRpm() < 1.0, "friction must stop the network");

        // Остановившиеся машины — IDLE
        assertEquals(WorkState.IDLE, shaft1.getWorkState());
        assertEquals(WorkState.IDLE, shaft2.getWorkState());
    }

    @Test
    void overloadStallsNetworkInstantly() {
        // Потребитель требует больше, чем даёт генератор:
        // сеть не должна раскрутиться — мгновенный стоп
        final MechanicalGroup group = group(
                generator(256_000, 10_000, new BlockPos(0, 0, 0)),  // слабый: 10 Nm
                shaft(new BlockPos(1, 0, 0)),
                consumer(1_000, 32_000, new BlockPos(2, 0, 0)));    // требует 32 Nm

        // Потребитель грузит с первого тика (его обороты 1 RPM < любые)
        group.computeTick();

        assertTrue(group.getCurrentSpeedRpm() < 1.0,
                "overloaded network must not spin up, got " + group.getCurrentSpeedRpm());
    }

    @Test
    void marginalLoad_doesNotZeroTheNetwork() {
        // Маргинальная нагрузка: момент потребителя ПОСИЛЬНЫЙ источнику
        // (60 Nm < 64 Nm), но трение на пороге включения съедает запас.
        // Сеть не должна обнуляться (вечный цикл разгон->порог->ноль),
        // а должна пилообразно держаться у порога потребителя.
        final MechanicalMachine gen = generator(256_000, 64_000, new BlockPos(0, 0, 0));
        final MechanicalMachine[] shafts = new MechanicalMachine[6];
        final MechanicalMachine consumer = consumer(203_230, 60_000, new BlockPos(7, 0, 0));
        final MechanicalGroup group = group(gen, shafts[0] = shaft(new BlockPos(1, 0, 0)),
                shafts[1] = shaft(new BlockPos(2, 0, 0)), shafts[2] = shaft(new BlockPos(3, 0, 0)),
                shafts[3] = shaft(new BlockPos(4, 0, 0)), shafts[4] = shaft(new BlockPos(5, 0, 0)),
                shafts[5] = shaft(new BlockPos(6, 0, 0)), consumer);

        int zeroings = 0;
        double prev = 0;
        for (int t = 0; t < 3000; t++) {
            group.computeTick();
            final double speed = group.getCurrentSpeedRpm();
            if (prev > 100 && speed < 1.0) {
                zeroings++;
            }
            prev = speed;
        }

        assertEquals(0, zeroings, "marginal load must not zero the network");
        // Сеть держится у порога включения потребителя, а не висит на нуле
        assertTrue(prev > 150, "network must keep spinning near threshold, got " + prev);
    }

    @Test
    void noSources_networkStaysIdle() {
        // Группа без источников не должна самопроизвольно крутиться
        final MechanicalGroup group = group(
                shaft(new BlockPos(1, 0, 0)),
                shaft(new BlockPos(2, 0, 0)));

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }

        assertEquals(0, group.getCurrentSpeedRpm());
        assertEquals(WorkState.IDLE, group.getMachine(0).getWorkState());
        assertEquals(WorkState.IDLE, group.getMachine(1).getWorkState());
    }
}
