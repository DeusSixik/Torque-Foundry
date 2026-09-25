package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Источник механической энергии: не имеет входов,
 * выдаёт мощность на все горизонтальные грани.
 *
 * <p>ДВА РЕЖИМА (раздел 7.18), отличаются органом управления, а не предметом:
 * <ul>
 *   <li><b>Низкий (по умолчанию)</b> — обороты ниже предела дерева: стартовая
 *       деревянная линия живёт без износа. Момент тот же — меньше мощность,
 *       но на стартового потребителя хватает.</li>
 *   <li><b>Высокий</b> — паспортные обороты; включается ТОЛЬКО явным
 *       переключением (клик по блоку). Игрок, ничего не переключавший, не
 *       ломает ничего; включивший высокий на деревянном валу ломает его сам
 *       и видит, почему.</li>
 * </ul>
 *
 * <p>Тепловая модель (режим работы S1-S8 возникает сам, без таймеров):
 * потери преобразования (P_loss = P_out·(1-η)/η) греют ротор; при росте
 * температуры к пределу момент линейно derate'ится до пола. Перегруженный
 * генератор: греется -> теряет момент -> сеть клинит -> стоит (потерь нет)
 * -> остывает -> момент вернулся -> снова крутит. Duty cycle — эмерджентный.
 */
public class GeneratorMachine extends MechanicalMachine {

    /**
     * Обороты низкого режима, milli-RPM: ниже предела дерева (107 RPM) —
     * деревянные валы не изнашиваются, момент при этом паспортный.
     */
    public static final long LOW_MODE_SPEED_RAW = 96_000;

    private final RotationalPower output;
    /** Низкий режим: тот же момент, обороты ниже предела дерева. */
    private final RotationalPower lowOutput;

    /** КПД преобразования в механику: потери греют ротор (0..1]. */
    private final double efficiency;

    /** Предельная температура ротора, °C — здесь момент у пола. */
    private final double maxTemperatureC;

    /** Диапазон derate: от (max − span) до max момент линейно падает. */
    private static final double DERATE_SPAN_K = 50.0;

    /** Минимальный момент при перегреве (доля паспортного). */
    private static final double DERATE_FLOOR = 0.3;

    /** Текущий множитель паспортного момента (считается в onSourceTick). */
    private double outputFactor = 1.0;

    /**
     * Режим выдачи: false — низкий (по умолчанию, ниже предела дерева),
     * true — высокий (паспорт). Включается только явным переключением.
     */
    private boolean highMode;

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction) {
        this(outputSpeedRaw, outputTorqueRaw, direction, 0.9, 110.0);
    }

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction,
                            double efficiency, double maxTemperatureC) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.output = RotationalPower.fromRaw(outputSpeedRaw, outputTorqueRaw, direction);
        this.efficiency = Math.max(0.05, Math.min(1.0, efficiency));
        this.maxTemperatureC = maxTemperatureC;
        this.lowOutput = RotationalPower.fromRaw(
                Math.min(outputSpeedRaw, LOW_MODE_SPEED_RAW), outputTorqueRaw, direction);
    }

    /**
     * Явное переключение режима (клик по блоку). Умолчание — низкий.
     *
     * @param high true — высокий (паспорт), false — низкий
     */
    public void setHighMode(boolean high) {
        this.highMode = high;
    }

    /** Текущий режим выдачи: false — низкий, true — высокий (паспорт). */
    public boolean isHighMode() {
        return highMode;
    }

    @Override
    public RotationalPower getOutput() {
        return highMode ? output : lowOutput;
    }

    @Override
    protected void createDirections() {
        port(Direction.NORTH, PortRole.OUTPUT);
        port(Direction.SOUTH, PortRole.OUTPUT);
        port(Direction.WEST, PortRole.OUTPUT);
        port(Direction.EAST, PortRole.OUTPUT);
    }

    /**
     * Тик источника: потери греют ротор, температура диктует derate.
     * На нулевых оборотах потерь нет (P = τ·ω = 0) — генератор остывает.
     *
     * @param outputWatts паспортная мощность источника на текущих оборотах, Вт
     */
    @Override
    public void onSourceTick(long outputWatts) {
        if (outputWatts > 0) {
            final double lossWatts = outputWatts * (1.0 - efficiency) / efficiency;
            // НОЛЬ на холодном ходу: нулевая выдача — нулевые потери
            if (lossWatts > 0.0) {
                getSimulationState().addHeatJ(lossWatts / 20.0);
            }
        }
        final double t = getSimulationState().temperatureC(
                getMaterial(), getMaterial().nominalMassKg());
        outputFactor = Math.max(DERATE_FLOOR,
                Math.min(1.0, (maxTemperatureC - t) / DERATE_SPAN_K));
    }

    @Override
    public double getOutputFactor() {
        return outputFactor;
    }

    /** Секция Source: паспортная выдача и тепловой derate ротора. */
    @Override
    public void addDebugInfo(dev.sdm.torque_foundry.api.debug.DebugInfoCollector collector) {
        super.addDebugInfo(collector);
        final double factor = getOutputFactor();
        collector.section("Source")
                .addKey("Source.rated", "Rated output", fmtPower(getOutput()))
                .entryKey("Source.derate", "Thermal derate",
                        String.format(java.util.Locale.ROOT, "%.0f%%", factor * 100)
                                + (factor < 1.0 ? " DERATED (overheated)" : ""),
                        factor < 1.0, (float) factor,
                        "Перегрев режет паспортный момент: потери P(1-eta)/eta греют ротор");
    }
}
