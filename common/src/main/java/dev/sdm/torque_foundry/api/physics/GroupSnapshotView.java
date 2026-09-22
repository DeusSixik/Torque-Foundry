package dev.sdm.torque_foundry.api.physics;

import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;

/**
 * Переиспользуемый буфер читателя: согласованный срез состояния группы
 * на момент одного физического тика.
 *
 * <p>Заполняется через {@link PhysicsReads#copySnapshot} /
 * {@link PhysicsReads#tryCopySnapshot}: под замком копируются ВСЕ поля
 * одним куском, поэтому после копирования срез консистентен — все данные
 * из одного тика ({@link #tickId}).
 *
 * <p>Тредовый контракт: view принадлежит ОДНОМУ треду (рендер — поле
 * рендера, серверный синк — локальная переменная цикла). Правило I4
 * из «Concurrency in Torque Foundry.md»: хранить ссылки на внутренние
 * массивы снапшота группы запрещено — только копия в своём view.
 *
 * <p>Все массивы растут через {@link #ensureCapacity} при росте группы;
 * аллокация возможна только в момент роста (топология изменилась) —
 * в установившемся режиме копирование без аллокаций.
 */
public final class GroupSnapshotView {

    /** Id группы-источника. */
    public long groupId;
    /** Id тика, данные которого здесь лежат. -1 — ещё ничего не публиковали. */
    public long tickId = -1;
    /**
     * Число слотов группы (валидные индексы 0..machineCount-1).
     * Индекс = слот машины в группе (стабилен при неизменной топологии).
     */
    public int machineCount;
    /** Обороты сети, milli-RPM (на уровне источников). */
    public long netSpeedRaw;
    /** Контрольная сумма среза (см. GroupSnapshot.verifyChecksum). */
    public long checksum;

    // --- Per-machine массивы (SoA) ---

    /**
     * BlockPos.asLong машины; 0 = пустой слот (headless-машина или дыра
     * топологии после split) — такие слоты читатели пропускают.
     */
    public long[] posLong = new long[0];
    /** Состояние работы (справочник-enum, ссылки безопасны). */
    public WorkState[] states = new WorkState[0];
    /** Материал машины (иммутабельный record, ссылки безопасны). */
    public PhysicsMaterial[] materials = new PhysicsMaterial[0];

    /** Received: обороты/момент (milli) и направление на входе машины. */
    public long[] receivedSpeedRaw = new long[0];
    public long[] receivedTorqueRaw = new long[0];
    public byte[] receivedDirection = new byte[0];

    /** Требования машины (milli-RPM / milli-Nm). */
    public long[] requiredSpeedRaw = new long[0];
    public long[] requiredTorqueRaw = new long[0];

    /** Свободная мощность узла (received − требования детей), Вт. */
    public long[] freePowerWatts = new long[0];

    /** Мгновенная мощность тика: пришло / требуют дети / свободно, Вт. */
    public long[] tickReceivedWatts = new long[0];
    public long[] tickChildrenWatts = new long[0];
    public long[] tickFreeWatts = new long[0];

    /** Накопленное тепло сверх среды, Дж; температура, °C. */
    public double[] thermalEnergyJ = new double[0];
    public double[] temperatureC = new double[0];

    /** Ресурсные счётчики: тики перегруза, пропущенная энергия, Дж. */
    public long[] overloadTicks = new long[0];
    public long[] totalThroughputJ = new long[0];

    /** Есть ли опорные слоты (осевые машины). */
    public boolean[] bearingSlots = new boolean[0];
    /** Тип подшипника слота — ordinal BearingType (-1 = пусто). */
    public byte[] bearing0Type = new byte[0];
    public byte[] bearing1Type = new byte[0];
    /** Износ подшипников слота 0..1. */
    public double[] bearing0Wear = new double[0];
    public double[] bearing1Wear = new double[0];

    /** Смазка: количество (0..CAPACITY) и ordinal типа LubricantState.Type. */
    public double[] lubricantAmount = new double[0];
    public byte[] lubricantType = new byte[0];

    /** Перекос вала, градусы. */
    public double[] misalignmentDeg = new double[0];
    /** Тепловой derate источника (0..1], 1 = без derate. */
    public double[] outputFactor = new double[0];
    /** КПД передачи (0..1]. */
    public double[] efficiency = new double[0];

    /**
     * Гарантирует ёмкость всех per-machine массивов. Вызывается движком
     * при копировании; аддонам звать не нужно (но безопасно: рост —
     * редкое событие смены топологии).
     *
     * @param n требуемое число машин
     */
    public void ensureCapacity(int n) {
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

    /** Обороты сети в RPM (для отображения). */
    public double netSpeedRpm() {
        return netSpeedRaw / 1000.0;
    }

    /** Направление received машины как enum (byte — для zero-alloc копии). */
    public RotationDirection receivedDirection(int index) {
        return RotationDirection.from(receivedDirection[index]);
    }
}
