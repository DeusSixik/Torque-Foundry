package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.RotationDirection;
import net.minecraft.core.Direction;

/**
 * Источник механической энергии: не имеет входов,
 * выдаёт мощность на все горизонтальные грани.
 *
 * <p>Тепловая модель (режим работы S1-S8 возникает сам, без таймеров):
 * потери преобразования (P_loss = P_out·(1-η)/η) греют ротор; при росте
 * температуры к пределу момент линейно derate'ится до пола. Перегруженный
 * генератор: греется -> теряет момент -> сеть клинит -> стоит (потерь нет)
 * -> остывает -> момент вернулся -> снова крутит. Duty cycle — эмерджентный.
 */
public class GeneratorMachine extends MechanicalMachine {

    private final RotationalPower output;

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

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction) {
        this(outputSpeedRaw, outputTorqueRaw, direction, 0.9, 110.0);
    }

    public GeneratorMachine(long outputSpeedRaw, long outputTorqueRaw, RotationDirection direction,
                            double efficiency, double maxTemperatureC) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.output = RotationalPower.fromRaw(outputSpeedRaw, outputTorqueRaw, direction);
        this.efficiency = Math.max(0.05, Math.min(1.0, efficiency));
        this.maxTemperatureC = maxTemperatureC;
    }

    @Override
    public RotationalPower getOutput() {
        return output;
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
     */
    @Override
    public void onSourceTick(long outputWatts) {
        if (outputWatts > 0) {
            final double lossWatts = outputWatts * (1.0 - efficiency) / efficiency;
            getSimulationState().addHeatJ(lossWatts / 20.0);
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
}
