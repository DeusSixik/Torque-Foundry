package dev.sdm.torque_foundry.core.machine;

import dev.sdm.torque_foundry.api.debug.DebugInfoCollector;
import dev.sdm.torque_foundry.physics.PhysicsMath;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine.PortRole;
import net.minecraft.core.Direction;

import java.util.Locale;

/**
 * Маховик (раздел 7.11): накапливает энергию вращения ТОЙ скорости, на
 * которой реально стоит сеть. В движке это просто добавка к приведённой
 * инерции сети ({@link #getExtraInertia()}): большой J растягивает разгон,
 * гасит короткие пики просадкой оборотов и тянет выбег. Никакого отдельного
 * буфера джоулей нет — энергия считается из фактической скорости, её нельзя
 * ни «зарядить» от излишка, ни «разрядить» под дефицит: что вошло при
 * разгоне, выйдет только при замедлении. Среднюю мощность маховик не
 * повышает.
 *
 * <p>Регрессия: прежняя версия хранила джоули отдельно и подпирала момент
 * без падения оборотов — это аккумулятор, создающий энергию из ничего.
 */
public class FlywheelMachine extends MechanicalMachine {

    /** Момент инерции ротора (единицы инерции сети), &gt; 0. */
    private final double rotorInertia;

    /**
     * Маховик — проходной узел на оси: обе грани IN_OUT.
     *
     * @param rotorInertia момент инерции ротора (единицы инерции сети);
     *                     стальная болванка 385 кг, r = 0.125 м ~ 3
     * @param inputSide    грань входа (локальная)
     * @param outputSide   грань выхода (локальная)
     */
    public FlywheelMachine(double rotorInertia, Direction inputSide, Direction outputSide) {
        super(RotationalPower.fromRaw(0, 0), (byte) -1);
        this.rotorInertia = Math.max(0.5, rotorInertia);
        port(inputSide, PortRole.IN_OUT);
        port(outputSide, PortRole.IN_OUT);
    }

    /**
     * Весь вклад маховика в сеть — инерция. Приводится к базовому уровню
     * квадратом отношения (фаза B): маховик за редуктором «легче».
     */
    @Override
    public double getExtraInertia() {
        return rotorInertia;
    }

    /**
     * Запасённая энергия — ПРОИЗВОДНАЯ величина из фактической скорости
     * узла, не состояние: E = ½·J·ω². Хранить её негде и не нужно.
     *
     * @return энергия вращения, Дж
     */
    public double storedEnergyJ() {
        return PhysicsMath.kineticEnergyNetworkJ(
                rotorInertia, getReceived().getSpeedRaw());
    }

    /** Секция Flywheel: инерция и энергия по фактической скорости. */
    @Override
    public void addDebugInfo(DebugInfoCollector collector) {
        super.addDebugInfo(collector);
        final double e = storedEnergyJ();
        collector.section("Flywheel")
                .addKey("Flywheel.inertia", "Rotor inertia",
                        String.format(Locale.ROOT, "J = +%.1f", rotorInertia))
                .entryKey("Flywheel.energy", "Stored energy",
                        String.format(Locale.ROOT, "%.0f J (E = J·ω²/2, actual speed)", e),
                        e > 10_000, (float) Math.min(1.0, e / 50_000.0),
                        "Энергия — производная скорости, не буфер: пик гасится "
                                + "просадкой оборотов, среднюю мощность маховик не повышает");
    }
}
