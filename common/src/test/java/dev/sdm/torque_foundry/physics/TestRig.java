package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;

/**
 * Хелперы построения тестовых цепей.
 */
public final class TestRig {

    /** Сколько тиков даём сети на раскрутку/стабилизацию. */
    public static final int SETTLE_TICKS = 400;

    private TestRig() {
    }

    public static MechanicalMachine generator(long speedRaw, long torqueRaw, BlockPos pos) {
        final MechanicalMachine machine = highGen(speedRaw, torqueRaw);
        machine.setBlockPos(pos);
        return machine;
    }

    /**
     * Генератор в ВЫСОКОМ (паспортном) режиме: для тестов, где важны
     * паспортные обороты. Умолчание движка — низкий режим (ниже предела
     * дерева), явное переключение — как у игрока.
     */
    public static GeneratorMachine highGen(long speedRaw, long torqueRaw) {
        return highGen(speedRaw, torqueRaw, RotationDirection.FORWARD);
    }

    /** Генератор в высоком режиме с направлением (встречные источники). */
    public static GeneratorMachine highGen(long speedRaw, long torqueRaw, RotationDirection dir) {
        final GeneratorMachine gen = new GeneratorMachine(speedRaw, torqueRaw, dir);
        gen.setHighMode(true);
        return gen;
    }

    public static MechanicalMachine shaft(BlockPos pos) {
        final MechanicalMachine machine = new ShaftMachine();
        machine.setBlockPos(pos);
        return machine;
    }

    public static MechanicalMachine consumer(long speedReq, long torqueReq, BlockPos pos) {
        final MechanicalMachine machine = new ConsumerMachine(speedReq, torqueReq, RotationDirection.FORWARD);
        machine.setBlockPos(pos);
        return machine;
    }

    public static MechanicalGroup group(MechanicalMachine... machines) {
        final MechanicalGroup group = new MechanicalGroup();
        for (MechanicalMachine m : machines) {
            group.addElement(m);
        }
        return group;
    }

    /**
     * Линейная цепь: генератор -> n валов -> потребитель.
     * Все машины вдоль оси X.
     */
    public static MechanicalGroup line(MechanicalMachine gen, MechanicalMachine[] shafts,
                                       MechanicalMachine consumer) {
        final MechanicalGroup group = new MechanicalGroup();
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        for (int i = 0; i < shafts.length; i++) {
            shafts[i].setBlockPos(new BlockPos(1 + i, 0, 0));
            group.addElement(shafts[i]);
        }
        consumer.setBlockPos(new BlockPos(1 + shafts.length, 0, 0));
        group.addElement(consumer);
        return group;
    }

    /**
     * Прогрев: тикаем сеть, пока скорость не выйдет на плато
     * (не меняется несколько тиков подряд) или не кончатся тики.
     */
    public static void settle(MechanicalGroup group, int maxTicks) {
        long prev = -1;
        int stable = 0;
        for (int i = 0; i < maxTicks && stable < 20; i++) {
            group.computeTick();
            final long speed = group.currentSpeedForTest();
            if (speed == prev) {
                stable++;
            } else {
                stable = 0;
                prev = speed;
            }
        }
    }
}
