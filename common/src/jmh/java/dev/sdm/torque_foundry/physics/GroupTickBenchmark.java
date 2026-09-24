package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.core.machine.TransferCaseMachine;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Бенчмарк физического тика группы (hot path солвера, фазы A–D).
 *
 * <p>Меряется среднее время одного {@code computeTick()} на двух топологиях:
 * линейная цепь из 10 машин и раздатка с двумя ветками (11 машин).
 * Возвращаемое значение оборотов не даёт JIT выкинуть тик как мёртвый код.
 * Прогрев в {@code @Setup} выводит сети на плато, чтобы тик шёл по
 * типовому пути без роста scratch-буферов.
 *
 * <p>Запуск: {@code gradle :common:jmh} (профиль gc включён в build.gradle).
 *
 * <p><b>Оговорка о gc-профайлере:</b> на операциях микросекундного масштаба
 * {@code gc.alloc.rate.norm} JMH включает аллокации harness-а между
 * операциями и завышает результат на два порядка (даёт ~100 KB/op на тике,
 * который реально аллоцирует ~1.5 KB — см. AllocProbeTest с
 * ThreadMXBean.getThreadAllocatedBytes). Времена ({@code us/op}) корректны;
 * байты на тик сверять по AllocProbeTest, а не по этому профилю.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GroupTickBenchmark {

    /** Линейная цепь: генератор + 8 валов + потребитель. */
    private MechanicalGroup linear;

    /** Раздатка: генератор + раздатка + 2 ветки по 4 вала + 2 потребителя. */
    private MechanicalGroup branch;

    @Setup(Level.Trial)
    public void setup() {
        linear = buildLinear(8);
        branch = buildTransferBranch(4);
        // Прогрев до плато: буферы выросли, сети на рабочей скорости
        for (int i = 0; i < 600; i++) {
            linear.computeTick();
            branch.computeTick();
        }
    }

    /** Тик линейной цепи из 10 машин. */
    @Benchmark
    public long linearChain_10Machines() {
        linear.computeTick();
        return linear.getNetSpeedRaw();
    }

    /** Тик раздаточной сети из 11 машин с дележом по спросу. */
    @Benchmark
    public long transferCaseBranch_11Machines() {
        branch.computeTick();
        return branch.getNetSpeedRaw();
    }

    /**
     * Линейная цепь вдоль X: генератор, {@code shafts} валов, потребитель.
     *
     * @param shafts число валов между источником и потребителем
     * @return готовая группа на плато оборотов источника
     */
    private static MechanicalGroup buildLinear(int shafts) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        for (int i = 1; i <= shafts; i++) {
            final MechanicalMachine shaft = new ShaftMachine();
            shaft.setBlockPos(new BlockPos(i, 0, 0));
            group.addElement(shaft);
        }
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(shafts + 1, 0, 0));
        group.addElement(consumer);
        return group;
    }

    /**
     * Раздатка с двумя одинаковыми ветками: генератор -> раздатка 1:1/1:1 ->
     * {@code perBranch} валов -> потребитель 32 Н·м на каждой ветке.
     *
     * @param perBranch число валов в каждой ветке
     * @return готовая группа на плато оборотов источника
     */
    private static MechanicalGroup buildTransferBranch(int perBranch) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final TransferCaseMachine tc = new TransferCaseMachine(
                Direction.WEST, Direction.EAST, 1, Direction.NORTH, 1);
        tc.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(tc);

        for (int i = 0; i < perBranch; i++) {
            final MechanicalMachine east = new ShaftMachine();
            east.setBlockPos(new BlockPos(2 + i, 0, 0));
            group.addElement(east);
            final MechanicalMachine north = new ShaftMachine();
            north.setBlockPos(new BlockPos(1, -1 - i, 0));
            group.addElement(north);
        }
        final ConsumerMachine east = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        east.setBlockPos(new BlockPos(2 + perBranch, 0, 0));
        group.addElement(east);
        final ConsumerMachine north = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        north.setBlockPos(new BlockPos(1, -1 - perBranch, 0));
        group.addElement(north);
        return group;
    }
}
