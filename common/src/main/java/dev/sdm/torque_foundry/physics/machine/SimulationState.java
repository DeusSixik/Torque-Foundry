package dev.sdm.torque_foundry.physics.machine;

import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;

/**
 * Мутабельные показатели симуляции машины: мощность за тик, накопленное
 * тепло, счётчики ресурса. Живёт внутри машины всё время её существования,
 * мутируется в потоке физики — новых объектов на тик не создаётся.
 *
 * <p>Два типа данных:
 * <ul>
 *   <li><b>Мгновенные</b> (мощность за тик) — перезаписываются конвейером
 *       каждый физический тик ({@link #setTickPower}).</li>
 *   <li><b>Накапливаемые</b> (тепло, счётчики) — живут между тиками:
 *       трение греет ({@link #addFrictionHeat}), конвекция остужает
 *       ({@link #coolTick}), деградация от перегрева — будущий потребитель.</li>
 * </ul>
 *
 * <p>Будущие расширения (передача тепла между машинами, нагрев помещений,
 * износ от температуры) добавляются полями/методами здесь — без PhysicsHook.
 */
public final class SimulationState {

    /** Температура окружающей среды (пещеры/незер — в будущем из биома). */
    public static final double AMBIENT_TEMPERATURE_C = 20.0;

    /** Конвекция: ватт охлаждения на кельвин разницы на килограмм массы.
     *  Подобрана играбельно: постоянная времени = c/8 (сталь ~58 c, дерево ~212 c —
     *  теплоёмкие материалы остывают дольше, как и в жизни). */
    private static final double COOLING_W_PER_KG_K = 8.0;

    /** Физических тиков в секунду (для перевода мощности в энергию). */
    private static final double TICKS_PER_SECOND = 20.0;

    // --- Мгновенные (за тик, ватты) ---
    private long receivedWatts;
    private long childrenWatts;
    private long freeWatts;

    // --- Накапливаемые ---
    /** Накопленное тепло сверх окружающей среды, J. */
    private double thermalEnergyJ;

    /** Суммарная энергия, пропущенная через машину за жизнь, J. */
    private long totalThroughputJ;

    /** Тиков перегруза (INSUFFICIENT/JAMMED) за жизнь — ресурсный счётчик. */
    private long overloadTicks;

    /** Записать мгновенную мощность тика (вызывает конвейер). */
    public void setTickPower(long receivedWatts, long childrenWatts, long freeWatts) {
        this.receivedWatts = receivedWatts;
        this.childrenWatts = childrenWatts;
        this.freeWatts = freeWatts;
        // Пропущенная энергия: за тик мощность/20
        this.totalThroughputJ += receivedWatts / (long) TICKS_PER_SECOND;
    }

    public long getReceivedWatts() {
        return receivedWatts;
    }

    public long getChildrenWatts() {
        return childrenWatts;
    }

    public long getFreeWatts() {
        return freeWatts;
    }

    // --- Тепло ---

    /**
     * Нагрев от трения: P = τ·ω, за тик E += P/20.
     *
     * @param frictionTorqueMilliNm момент трения, milli-Nm
     * @param speedMilliRpm         обороты, milli-RPM
     */
    public void addFrictionHeat(long frictionTorqueMilliNm, long speedMilliRpm) {
        if (frictionTorqueMilliNm <= 0 || speedMilliRpm <= 0) {
            return;
        }
        // τ [Н·м] = milliNm/1000; ω [рад/с] = RPM·2π/60 = milliRPM·2π/60000
        final double powerW = (frictionTorqueMilliNm / 1000.0)
                * (speedMilliRpm * 2.0 * Math.PI / 60000.0);
        thermalEnergyJ += powerW / TICKS_PER_SECOND;
    }

    /** Прямой подвод/отвод тепла (передача между машинами, нагрев среды). */
    public void addHeatJ(double joules) {
        thermalEnergyJ += joules;
        if (thermalEnergyJ < 0) {
            thermalEnergyJ = 0;
        }
    }

    /**
     * Пассивное охлаждение: конвекция пропорциональна перегреву
     * (ньютоновское приближение). Вызывается каждый тик.
     *
     * @param material материал (теплоёмкость не нужна для энергии,
     *                 конвекция — от массы и разницы температур)
     * @param massKg   масса детали, kg
     */
    public void coolTick(PhysicsMaterial material, double massKg) {
        final double excessK = temperatureC(material, massKg) - AMBIENT_TEMPERATURE_C;
        if (excessK <= 0) {
            return;
        }
        // Мощность охлаждения, Вт -> энергия за тик
        final double coolingW = COOLING_W_PER_KG_K * massKg * excessK;
        thermalEnergyJ = Math.max(0, thermalEnergyJ - coolingW / TICKS_PER_SECOND);
    }

    /**
     * Текущая температура: T = окружающая + E / (m·c).
     *
     * @param material материал (удельная теплоёмкость)
     * @param massKg   масса детали, kg
     */
    public double temperatureC(PhysicsMaterial material, double massKg) {
        return AMBIENT_TEMPERATURE_C + thermalEnergyJ / (massKg * material.heatCapacityJPerKgK());
    }

    /** Накопленное тепло сверх окружающей среды, J. */
    public double getThermalEnergyJ() {
        return thermalEnergyJ;
    }

    /** Перегрев над окружающей средой, K. */
    public double overheatingK(PhysicsMaterial material, double massKg) {
        return temperatureC(material, massKg) - AMBIENT_TEMPERATURE_C;
    }

    /** Сброс тепла (ремонт/замена детали). */
    public void resetThermal() {
        thermalEnergyJ = 0;
    }

    // --- Счётчики жизни ---

    public long getTotalThroughputJ() {
        return totalThroughputJ;
    }

    public void countOverloadTick() {
        overloadTicks++;
    }

    public long getOverloadTicks() {
        return overloadTicks;
    }

    /** Полный сброс (машина заменена/отремонтирована). */
    public void reset() {
        receivedWatts = 0;
        childrenWatts = 0;
        freeWatts = 0;
        thermalEnergyJ = 0;
        totalThroughputJ = 0;
        overloadTicks = 0;
    }
}
