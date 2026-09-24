package dev.sdm.torque_foundry.physics;

/**
 * Единая библиотека физических формул движка. Всё, что считается по
 * формуле — здесь; дубликаты в машинах/группах запрещены.
 *
 * <p>Сетевые единицы: скорости в milli-RPM, моменты в milli-Nm
 * ({@link PhysicsConstants#SCALE}). Конверсии мощности — целочисленные
 * (355/3390 ≈ 2π/60) без потерь на округлении.
 */
public final class PhysicsMath {

    private PhysicsMath() {
    }

    // --- Динамика сети (разгон/торможение) ---

    /**
     * Один шаг интегрирования оборотов сети: dSpeed = (τ · 477) / (I · 1000).
     * Ускорение — до целевого предела, торможение — до нуля,
     * минимальный шаг 1 milli-RPM (сеть всегда останавливается).
     *
     * @param currentSpeedRaw   текущие обороты, milli-RPM
     * @param targetMaxSpeedRaw предел источника, milli-RPM
     * @param netTorqueRaw      суммарный момент (тяга − трение − нагрузка), milli-Nm
     * @param totalInertia      суммарная инерция сети (>= 1)
     */
    public static long tickSpeed(long currentSpeedRaw, long targetMaxSpeedRaw,
                                 long netTorqueRaw, long totalInertia) {
        if (totalInertia <= 0) {
            return targetMaxSpeedRaw; // без инерции разгон мгновенный
        }

        // Свободный момент и не достигнут предел мотора — разгон
        if (netTorqueRaw > 0 && currentSpeedRaw < targetMaxSpeedRaw) {
            long deltaSpeed = accelStep(netTorqueRaw, totalInertia);
            return Math.min(currentSpeedRaw + deltaSpeed, targetMaxSpeedRaw);
        }

        // Мотор выключен или нагрузка превысила тягу — торможение
        if (netTorqueRaw < 0 && currentSpeedRaw > 0) {
            long deltaSpeed = accelStep(-netTorqueRaw, totalInertia);
            return Math.max(0, currentSpeedRaw - deltaSpeed);
        }

        return currentSpeedRaw;
    }

    /**
     * Шаг изменения оборотов за тик: (τ · 477) / (I · 1000), минимум 1.
     */
    public static long accelStep(long torqueRaw, long inertia) {
        if (inertia <= 0) {
            // Нулевая/отрицательная инерция (машина без материала): сеть
            // меняет скорость мгновенно, но деление на ноль запрещено.
            return Math.max(1, Math.abs(torqueRaw));
        }
        long delta = (torqueRaw * PhysicsConstants.ACCEL_NUM)
                / (inertia * (PhysicsConstants.ACCEL_DEN / 1000));
        return delta == 0 ? 1 : delta;
    }

    // --- Мощность ---

    /**
     * Мощность из момента и оборотов: P = τ · ω, в ваттах (целочисленно).
     *
     * @param torqueRaw момент, milli-Nm
     * @param speedRaw  обороты, milli-RPM
     */
    public static long watts(long torqueRaw, long speedRaw) {
        return (torqueRaw * PhysicsConstants.PI2_60_NUM / PhysicsConstants.PI2_60_DEN)
                * speedRaw / PhysicsConstants.SCALE_2;
    }

    /**
     * Обратная задача: момент, дающий заданную мощность на данных оборотах.
     *
     * @param watts    мощность, Вт
     * @param speedRaw обороты, milli-RPM
     */
    public static long torqueForWatts(long watts, long speedRaw) {
        return watts * PhysicsConstants.SCALE_2
                / (speedRaw * PhysicsConstants.PI2_60_NUM / PhysicsConstants.PI2_60_DEN);
    }

    // --- Спрос через передачу (фазы B2/B3 решателя) ---

    /**
     * Переводит требуемый момент ребёнка с выходной стороны узла на входную:
     * стоимость подачи этого спроса через механизм с передаточным числом
     * и КПД. Используется фазами B2 (приведение спроса) и B3 (делёж входа).
     *
     * <p>Реализует сохранение мощности через передачу по формуле:
     * <pre>
     *   t_in = t_out · (s_out / s_in) / η
     * </pre>
     *
     * <p>Где:
     * <ul>
     *   <li><b>t_in</b> — момент, который узел обязан снять со своего входа,
     *       чтобы ребёнок получил свой спрос (milli-Nm);</li>
     *   <li><b>t_out</b> — требуемый момент на стороне ребёнка (milli-Nm);</li>
     *   <li><b>s_out</b> — обороты на стороне ребёнка, то есть скорость ребра
     *       после transform узла (milli-RPM);</li>
     *   <li><b>s_in</b> — обороты на входе узла (milli-RPM);</li>
     *   <li><b>η</b> — КПД узла: потери обязан покрыть источник, поэтому
     *       спрос делится на η, а не умножается на него.</li>
     * </ul>
     *
     * <p>Сопоставление с аргументами: {@code t_out} &larr; {@code demandTorqueOutRaw},
     * {@code s_out} &larr; {@code outputSpeedRaw}, {@code s_in} &larr; {@code inputSpeedRaw},
     * {@code η} &larr; {@code efficiency}.
     *
     * <p>Переполнение: произведение милли-величин входит в long при моментах
     * до ~10^10 milli-Nm и скоростях до ~10^9 milli-RPM — рабочие диапазоны
     * движка (паспорт стали 266 000 Н·м = 2.66·10^8 milli-Nm) в запасе на
     * несколько порядков.
     *
     * @param demandTorqueOutRaw требуемый момент на стороне ребёнка, milli-Nm, &ge; 0
     * @param outputSpeedRaw     скорость ребра на стороне ребёнка, milli-RPM, &ge; 0
     * @param inputSpeedRaw      скорость на входе узла, milli-RPM, &ge; 0
     * @param efficiency         КПД узла (0, 1]; при &le; 0 принимается 0.01
     * @return стоимость спроса на входной стороне узла, milli-Nm, &ge; 0
     */
    public static long childDemandToInputCost(long demandTorqueOutRaw, long outputSpeedRaw,
                                              long inputSpeedRaw, double efficiency) {
        if (demandTorqueOutRaw <= 0) {
            return 0;
        }
        if (inputSpeedRaw <= 0) {
            // Сеть стоит: отношение скоростей не определено (0/0).
            // Отношение принимается единичным — остаётся деление на КПД.
            return Math.round(demandTorqueOutRaw / Math.max(efficiency, 0.01));
        }
        final long ideal = demandTorqueOutRaw * outputSpeedRaw / inputSpeedRaw;
        return Math.round(ideal / Math.max(efficiency, 0.01));
    }

    // --- Трение ---

    /**
     * Вязкий момент трения: τ = f · ω, минимум 1 milli-Nm —
     * чтобы сеть всегда останавливалась трением.
     *
     * @param viscousCoefficient вязкий коэффициент (milli-Nm на milli-RPM)
     * @param speedRaw           обороты, milli-RPM
     */
    public static long viscousFrictionTorque(double viscousCoefficient, long speedRaw) {
        return Math.max(1, Math.round(viscousCoefficient * speedRaw));
    }

    // --- Геометрия вала (сплошной круглый) ---

    /**
     * Полярный момент инерции сечения: J = π r⁴ / 2, m⁴.
     * Крутильная жёсткость и напряжения кручения.
     */
    public static double polarMomentOfInertia(double radiusM) {
        return Math.PI * Math.pow(radiusM, 4) / 2.0;
    }

    /**
     * Полярный момент сопротивления сечения: Wp = π r³ / 2, m³.
     * Предельный момент по допускаемому напряжению.
     */
    public static double polarSectionModulus(double radiusM) {
        return Math.PI * Math.pow(radiusM, 3) / 2.0;
    }

    /**
     * Касательное напряжение на поверхности вала: τ = T · r / J, Па.
     */
    public static double shearStressPa(double torqueNm, double radiusM) {
        return torqueNm * radiusM / polarMomentOfInertia(radiusM);
    }

    /**
     * Кинетическая энергия вращения: E = ½ I ω², Дж.
     *
     * @param inertiaKgM2 момент инерции, кг·м²
     * @param omegaRadS   угловая скорость, рад/с
     */
    public static double kineticEnergyJ(double inertiaKgM2, double omegaRadS) {
        return 0.5 * inertiaKgM2 * omegaRadS * omegaRadS;
    }

    /**
     * Момент трения детали при данной угловой скорости: τ = I · α, Н·м.
     */
    public static double torqueFromAngularAccel(double inertiaKgM2, double alphaRadS2) {
        return inertiaKgM2 * alphaRadS2;
    }

    // --- Конверсии единиц ---

    /**
     * Обороты -> угловая скорость: ω = RPM · 2π / 60, рад/с.
     */
    public static double rpmToOmega(double rpm) {
        return rpm * (Math.PI / 30.0);
    }

    /**
     * Угловая скорость -> обороты: RPM = ω · 60 / 2π.
     */
    public static double omegaToRpm(double omegaRadS) {
        return omegaRadS * (30.0 / Math.PI);
    }

    // --- Аэродинамика (запас: ветряки, быстрое вращение) ---
    // Формула зарезервирована под ветряки: сейчас движок её не вызывает
    // (потери только вязкие + КПД передач). Не удалять — точка расширения.

    /**
     * Плотность воздуха на уровне моря при 15 °C, кг/м³.
     */
    public static final double RHO_AIR = 1.225;

    /**
     * Эмпирический коэффициент формы вала (подбирается экспериментально).
     */
    public static final double K_DRAG = 0.05;

    /**
     * Момент аэродинамического торможения вала: T = k · ρ · ω² · r⁴ · L, Н·м.
     *
     * @param rhoAir  плотность среды, кг/м³
     * @param omega   угловая скорость, рад/с
     * @param radiusM радиус вала, м
     * @param lengthM длина вала, м
     */
    public static double airDragTorqueNm(double rhoAir, double omega, double radiusM, double lengthM) {
        return K_DRAG * rhoAir * omega * omega * Math.pow(radiusM, 4) * lengthM;
    }
}
