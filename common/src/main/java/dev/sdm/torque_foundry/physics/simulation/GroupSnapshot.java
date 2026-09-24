package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.api.physics.GroupSnapshotView;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.Bearing;
import dev.sdm.torque_foundry.physics.machine.LubricantKinds;
import dev.sdm.torque_foundry.physics.machine.LubricantState;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.SimulationState;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;

/**
 * Опубликованный срез состояния группы («front» из протокола снапшотов,
 * «Concurrency in Torque Foundry.md», §4).
 *
 * <p>Внутренняя сущность: пишет ТОЛЬКО владелец группы (воркер физики)
 * под {@code snapshotLock} группы одним куском ({@link #publishFrom}).
 * Читатели через {@link MechanicalGroup#copySnapshotTo} получают копию
 * в свой {@link GroupSnapshotView} — ссылки сюда наружу не уходят (I4).
 *
 * <p>Все буферы переиспользуются между публикациями: ноль аллокаций
 * в установившемся тике. Рост — только при росте группы (топология).
 */
public final class GroupSnapshot {

    private long groupId = -1;
    private long tickId = -1;
    private int machineCount;
    private long netSpeedRaw;
    private long checksum;

    private long[] posLong = new long[0];
    private WorkState[] states = new WorkState[0];
    private PhysicsMaterial[] materials = new PhysicsMaterial[0];
    private long[] receivedSpeedRaw = new long[0];
    private long[] receivedTorqueRaw = new long[0];
    private byte[] receivedDirection = new byte[0];
    private long[] requiredSpeedRaw = new long[0];
    private long[] requiredTorqueRaw = new long[0];
    private long[] freePowerWatts = new long[0];
    private long[] tickReceivedWatts = new long[0];
    private long[] tickChildrenWatts = new long[0];
    private long[] tickFreeWatts = new long[0];
    private double[] thermalEnergyJ = new double[0];
    private double[] temperatureC = new double[0];
    private long[] overloadTicks = new long[0];
    private long[] totalThroughputJ = new long[0];
    private boolean[] bearingSlots = new boolean[0];
    private byte[] bearing0Type = new byte[0];
    private byte[] bearing1Type = new byte[0];
    private double[] bearing0Wear = new double[0];
    private double[] bearing1Wear = new double[0];
    private double[] lubricantAmount = new double[0];
    private byte[] lubricantType = new byte[0];
    private double[] misalignmentDeg = new double[0];
    private double[] outputFactor = new double[0];
    private double[] efficiency = new double[0];

    /**
     * Публикация: скопировать живое состояние группы в front.
     * Вызывается владельцем группы в конце тика под snapshotLock.
     *
     * <p>Индексация СОХРАНЯЕТ СЛОТЫ: массив[i] = машина с индексом i
     * группы (клиентский синк мапит pos -> slot). Защита от гонки с
     * топологией (мерж/сплит на серверном треде): массив машин снимается
     * в локал ОДИН раз; null-дыры после split помечаются posLong = 0 —
     * читатели пропускают такие слоты.
     *
     * @param group группа-владелец
     */
    public void publishFrom(MechanicalGroup group) {
        final MechanicalMachine[] machines = group.getMachines();
        final int n = group.getSize();
        ensureCapacity(n);

        long receivedSumRaw = 0;
        for (int i = 0; i < n; i++) {
            final MechanicalMachine machine = machines[i];
            if (machine == null) {
                // Дыра топологии (split): слот помечаем пустым.
                posLong[i] = 0L;
                continue;
            }
            final var pos = machine.getBlockPos();
            posLong[i] = pos != null ? pos.asLong() : 0L;
            states[i] = machine.getWorkState();
            final PhysicsMaterial material = machine.getMaterial();
            materials[i] = material;

            receivedSpeedRaw[i] = machine.getReceived().getSpeedRaw();
            receivedTorqueRaw[i] = machine.getReceived().getTorqueRaw();
            receivedDirection[i] = machine.getReceived().getDirection();
            receivedSumRaw += receivedSpeedRaw[i];
            requiredSpeedRaw[i] = machine.getRequired().getSpeedRaw();
            requiredTorqueRaw[i] = machine.getRequired().getTorqueRaw();
            freePowerWatts[i] = machine.getFreePower();

            final SimulationState sim = machine.getSimulationState();
            tickReceivedWatts[i] = sim.getReceivedWatts();
            tickChildrenWatts[i] = sim.getChildrenWatts();
            tickFreeWatts[i] = sim.getFreeWatts();
            thermalEnergyJ[i] = sim.getThermalEnergyJ();
            final double massKg = material != null ? material.nominalMassKg() : 1.0;
            temperatureC[i] = material != null
                    ? sim.temperatureC(material, massKg) : 0.0;
            overloadTicks[i] = sim.getOverloadTicks();
            totalThroughputJ[i] = sim.getTotalThroughputJ();

            final boolean slots = machine.hasBearingSlots();
            bearingSlots[i] = slots;
            bearing0Type[i] = bearingTypeByte(machine.getBearing(0));
            bearing1Type[i] = bearingTypeByte(machine.getBearing(1));
            bearing0Wear[i] = machine.getBearing(0).wear();
            bearing1Wear[i] = machine.getBearing(1).wear();

            final LubricantState lube = machine.getLubricant();
            lubricantAmount[i] = lube != null ? lube.amount() : 0.0;
            lubricantType[i] = (byte) LubricantKinds.indexOf(lube != null ? lube.kind() : null);

            misalignmentDeg[i] = machine.getMisalignmentDeg();
            outputFactor[i] = machine.getOutputFactor();
            efficiency[i] = machine.getEfficiency();
        }

        this.groupId = group.getGroupId();
        this.tickId = group.getSimTick();
        this.machineCount = n;
        this.netSpeedRaw = group.getNetSpeedRaw();
        this.checksum = checksum(tickId, netSpeedRaw, machineCount, receivedSumRaw);
    }

    private static byte bearingTypeByte(Bearing bearing) {
        return bearing != null && bearing.present()
                ? (byte) bearing.type().ordinal() : -1;
    }

    /**
     * Контрольная сумма среза: читатель пересчитывает её по полученным
     * данным и сверяет — рваное копирование невозможно (копия под замком),
     * проверка остаётся как детектор ошибок протокола в тестах.
     */
    private static long checksum(long tick, long netSpeed, int count, long receivedSum) {
        return tick * 1_000_003L + netSpeed * 31L + count * 7L + receivedSum;
    }

    private static long receivedSumOf(GroupSnapshotView view) {
        long sum = 0;
        for (int i = 0; i < view.machineCount; i++) {
            sum += view.receivedSpeedRaw[i];
        }
        return sum;
    }

    /**
     * Копия front → view читателя. Вызывается ТОЛЬКО под snapshotLock
     * группы ({@link MechanicalGroup#copySnapshotTo}).
     *
     * @param into буфер читателя (растёт при необходимости)
     */
    public void copyTo(GroupSnapshotView into) {
        into.ensureCapacity(machineCount);
        into.groupId = groupId;
        into.tickId = tickId;
        into.machineCount = machineCount;
        into.netSpeedRaw = netSpeedRaw;
        into.checksum = checksum;
        System.arraycopy(posLong, 0, into.posLong, 0, machineCount);
        System.arraycopy(states, 0, into.states, 0, machineCount);
        System.arraycopy(materials, 0, into.materials, 0, machineCount);
        System.arraycopy(receivedSpeedRaw, 0, into.receivedSpeedRaw, 0, machineCount);
        System.arraycopy(receivedTorqueRaw, 0, into.receivedTorqueRaw, 0, machineCount);
        System.arraycopy(receivedDirection, 0, into.receivedDirection, 0, machineCount);
        System.arraycopy(requiredSpeedRaw, 0, into.requiredSpeedRaw, 0, machineCount);
        System.arraycopy(requiredTorqueRaw, 0, into.requiredTorqueRaw, 0, machineCount);
        System.arraycopy(freePowerWatts, 0, into.freePowerWatts, 0, machineCount);
        System.arraycopy(tickReceivedWatts, 0, into.tickReceivedWatts, 0, machineCount);
        System.arraycopy(tickChildrenWatts, 0, into.tickChildrenWatts, 0, machineCount);
        System.arraycopy(tickFreeWatts, 0, into.tickFreeWatts, 0, machineCount);
        System.arraycopy(thermalEnergyJ, 0, into.thermalEnergyJ, 0, machineCount);
        System.arraycopy(temperatureC, 0, into.temperatureC, 0, machineCount);
        System.arraycopy(overloadTicks, 0, into.overloadTicks, 0, machineCount);
        System.arraycopy(totalThroughputJ, 0, into.totalThroughputJ, 0, machineCount);
        System.arraycopy(bearingSlots, 0, into.bearingSlots, 0, machineCount);
        System.arraycopy(bearing0Type, 0, into.bearing0Type, 0, machineCount);
        System.arraycopy(bearing1Type, 0, into.bearing1Type, 0, machineCount);
        System.arraycopy(bearing0Wear, 0, into.bearing0Wear, 0, machineCount);
        System.arraycopy(bearing1Wear, 0, into.bearing1Wear, 0, machineCount);
        System.arraycopy(lubricantAmount, 0, into.lubricantAmount, 0, machineCount);
        System.arraycopy(lubricantType, 0, into.lubricantType, 0, machineCount);
        System.arraycopy(misalignmentDeg, 0, into.misalignmentDeg, 0, machineCount);
        System.arraycopy(outputFactor, 0, into.outputFactor, 0, machineCount);
        System.arraycopy(efficiency, 0, into.efficiency, 0, machineCount);
    }

    /**
     * Сверка контрольной суммы view после копирования. Тестам и отладке;
     * в проде читатель может не звать (копия под замком согласована).
     *
     * @param view заполненный {@link #copyTo} буфер
     * @return true — срез самосогласован
     */
    public static boolean verifyChecksum(GroupSnapshotView view) {
        return checksum(view.tickId, view.netSpeedRaw, view.machineCount,
                receivedSumOf(view)) == view.checksum;
    }

    private void ensureCapacity(int n) {
        if (posLong.length >= n) {
            return;
        }
        posLong = new long[n];
        states = new WorkState[n];
        materials = new PhysicsMaterial[n];
        receivedSpeedRaw = new long[n];
        receivedTorqueRaw = new long[n];
        receivedDirection = new byte[n];
        requiredSpeedRaw = new long[n];
        requiredTorqueRaw = new long[n];
        freePowerWatts = new long[n];
        tickReceivedWatts = new long[n];
        tickChildrenWatts = new long[n];
        tickFreeWatts = new long[n];
        thermalEnergyJ = new double[n];
        temperatureC = new double[n];
        overloadTicks = new long[n];
        totalThroughputJ = new long[n];
        bearingSlots = new boolean[n];
        bearing0Type = new byte[n];
        bearing1Type = new byte[n];
        bearing0Wear = new double[n];
        bearing1Wear = new double[n];
        lubricantAmount = new double[n];
        lubricantType = new byte[n];
        misalignmentDeg = new double[n];
        outputFactor = new double[n];
        efficiency = new double[n];
    }
}
