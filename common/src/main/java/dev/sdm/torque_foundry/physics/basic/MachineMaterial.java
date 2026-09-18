package dev.sdm.torque_foundry.physics.basic;

/**
 * Физический паспорт материала механических деталей — только реальные
 * физические величины, по 4 группам:
 * <ol>
 *   <li>Масса и инерция: плотность (kg/m³) — для массы детали по её объёму
 *       (например, вал: m = ρ · π r² L) и момента инерции.</li>
 *   <li>Упругость и жёсткость: модуль сдвига G, модуль Юнга E, коэффициент
 *       Пуассона ν — для крутильной жёсткости валов (кручение, вибрации).</li>
 *   <li>Пределы прочности: текучести (растяжение и сдвиг), временное
 *       сопротивление, усталостная прочность, твёрдость по Бринеллю — для
 *       разрушения, износа поверхностей и усталостных трещин.</li>
 *   <li>Трение и теплофизика: коэффициент трения, тепловое расширение,
 *       теплоёмкость, теплопроводность — для нагрева, заклинивания от
 *       теплового расширения, отвода тепла.</li>
 * </ol>
 *
 * <p>Все величины в СИ-подобных единицах: давление в МПа/GPa, плотность kg/m³,
 * теплоёмкость J/(kg·K), теплопроводность W/(m·K), расширение в ppm/K.
 *
 * <p>Игровая динамика потребляет физику через производные методы:
 * {@link #relativeDensity()} (инерция сети), {@link #viscousFriction()}
 * (момент трения), {@link #maxSafeSpeedRpm()} (лимит износа) — никаких
 * дублирующих «игровых» полей.
 *
 * <p>Создание — через {@link Builder}: независимые константы задаются явно,
 * зависимые (G, τ_y, σ₋₁) выводятся по формулам теории упругости и сопромата.
 */
public record MachineMaterial(
        /** Отображаемое имя. */
        String name,

        // --- 1. Масса и инерция ---
        /** Плотность, kg/m³. Реальная масса детали: m = ρ · V. */
        double densityKgM3,

        // --- 2. Упругость и жёсткость ---
        /** Модуль сдвига G, GPa. Крутильная жёсткость вала: k = G·J/L. */
        double shearModulusGpa,
        /** Модуль Юнга (продольной упругости) E, GPa. Изгиб, растяжение. */
        double youngModulusGpa,
        /** Коэффициент Пуассона ν (0..0.5). Связь E и G. */
        double poissonRatio,

        // --- 3. Пределы прочности ---
        /** Предел текучести при растяжении σ_y, MPa. Начало пластической деформации. */
        double yieldTensileMpa,
        /** Предел текучести на сдвиг τ_y, MPa. Пластическая деформация при кручении. */
        double yieldShearMpa,
        /** Предел прочности (временное сопротивление) σ_u, MPa. Разрушение. */
        double tensileStrengthMpa,
        /** Предел выносливости (усталостная прочность) σ₋₁, MPa. Циклическая нагрузка. */
        double fatigueStrengthMpa,
        /** Твёрдость поверхности по Бринеллю, HB. Износ поверхностей. */
        double hardnessHb,

        // --- 4. Трение и теплофизика ---
        /** Реальный коэффициент трения (пар со сталью, сухое/смазанное — усреднённое). */
        double frictionCoefficient,
        /** Коэффициент линейного теплового расширения, ppm/K (×10⁻⁶ 1/K). */
        double thermalExpansionPpmPerK,
        /** Удельная теплоёмкость, J/(kg·K). Нагрев: ΔT = Q/(m·c). */
        double heatCapacityJPerKgK,
        /** Теплопроводность, W/(m·K). Отвод тепла. */
        double thermalConductivityWPerMK
) {

    // --- Нормировочные константы игровой динамики ---

    /**
     * Плотность конструкционной стали — база нормировки инерции сети.
     */
    private static final double REF_STEEL_DENSITY = 7850.0;
    /**
     * Инерция стальной машины в сетевой модели.
     */
    private static final double INERTIA_AT_REF = 2.0;
    /**
     * μ (реальный) → вязкий коэффициент трения сети (milli-Nm/milli-RPM).
     */
    private static final double VISCOUS_FRICTION_DIVISOR = 15.0;
    /**
     * Предел текучести базовой стали для калибровки лимита оборотов (300/√3).
     */
    private static final double REF_YIELD_SHEAR_MPA = 300.0 / Math.sqrt(3.0);
    /**
     * Лимит оборотов базовой стали, RPM.
     */
    private static final double REF_SAFE_RPM = 256.0;

    public MachineMaterial {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("material name must not be blank");
        }
        if (densityKgM3 <= 0) {
            throw new IllegalArgumentException("densityKgM3 must be positive: " + name);
        }
        if (shearModulusGpa <= 0 || youngModulusGpa <= 0) {
            throw new IllegalArgumentException("elastic moduli must be positive: " + name);
        }
        if (poissonRatio < 0.0 || poissonRatio > 0.5) {
            throw new IllegalArgumentException(
                    "poissonRatio must be in [0, 0.5], got " + poissonRatio + ": " + name);
        }
        if (yieldTensileMpa <= 0
                || yieldShearMpa <= 0 || tensileStrengthMpa <= 0 || fatigueStrengthMpa <= 0 || hardnessHb <= 0) {
            throw new IllegalArgumentException("strength limits must be positive: " + name);
        }
        if (yieldTensileMpa > tensileStrengthMpa) {
            throw new IllegalArgumentException(
                    "yield (" + yieldTensileMpa + ") must not exceed tensile strength: " + name);
        }
        if (frictionCoefficient < 0) {
            throw new IllegalArgumentException("frictionCoefficient must not be negative: " + name);
        }
        if (heatCapacityJPerKgK <= 0 || thermalConductivityWPerMK <= 0) {
            throw new IllegalArgumentException("thermal properties must be positive: " + name);
        }
    }

    // --- Производные: упругость, масса, геометрия ---

    /**
     * Модуль сдвига из E и ν: G = E / (2(1+ν)).
     * Для изотропных материалов совпадает с {@link #shearModulusGpa()}.
     */
    public double shearModulusConsistencyGpa() {
        return youngModulusGpa / (2.0 * (1.0 + poissonRatio));
    }

    /**
     * Расхождение заданного G с выведенным из E и ν (в долях, 0 = идеально).
     */
    public double shearModulusDeviation() {
        final double derived = shearModulusConsistencyGpa();
        return Math.abs(shearModulusGpa - derived) / derived;
    }

    /**
     * Масса детали по объёму: m = ρ · V.
     *
     * @param volumeM3 объём детали, m³
     * @return масса, kg
     */
    public double mass(double volumeM3) {
        return densityKgM3 * volumeM3;
    }

    /**
     * Тепловое расширение в 1/K (ppm/K → 1/K).
     * Удлинение вала: ΔL = α · L · ΔT.
     */
    public double thermalExpansionPerK() {
        return thermalExpansionPpmPerK * 1.0e-6;
    }

    /**
     * Крутильная жёсткость сплошного круглого вала: k = G·J/L,
     * где J = π r⁴/2 — полярный момент инерции сечения.
     *
     * @param radiusM радиус вала, м
     * @param lengthM длина вала, м
     * @return жёсткость, N·m/rad
     */
    public double torsionalStiffness(double radiusM, double lengthM) {
        final double j = Math.PI * Math.pow(radiusM, 4) / 2.0;
        return shearModulusGpa * 1.0e9 * j / lengthM;
    }

    /**
     * Момент инерции сплошного круглого вала относительно оси: I = ½ m r².
     *
     * @param radiusM радиус вала, м
     * @param lengthM длина вала, м
     * @return момент инерции, kg·m²
     */
    public double shaftInertia(double radiusM, double lengthM) {
        final double m = mass(Math.PI * radiusM * radiusM * lengthM);
        return 0.5 * m * radiusM * radiusM;
    }

    /**
     * Максимальный крутящий момент (Н·м), который выдерживает сплошной
     * круглый вал без пластической деформации: T = Wp · [τ],
     * где Wp = π r³/2 — полярный момент сопротивления,
     * [τ] = τ_y / safetyFactor — допускаемое напряжение сдвига.
     *
     * @param radiusM      радиус вала, м
     * @param safetyFactor коэффициент запаса (обычно 1.5–2.5)
     * @return предельный момент, Н·м
     */
    public double maxSafeTorqueNm(double radiusM, double safetyFactor) {
        final double wp = Math.PI * Math.pow(radiusM, 3) / 2.0;
        final double allowable = yieldShearMpa * 1.0e6 / safetyFactor;
        return wp * allowable;
    }

    /**
     * Предельный момент стандартного блока вала (r = 0.125 м, запас 2.0).
     */
    public double defaultMaxSafeTorqueNm() {
        return maxSafeTorqueNm(0.125, 2.0);
    }

    // --- Производные: прокси игровой динамики (из физики, без полей) ---

    /**
     * Приведённая инерция машины в сетевой модели: нормировка плотности
     * от конструкционной стали (7850 kg/m³ → {@value #INERTIA_AT_REF}).
     * Лёгкое дерево разгоняется быстро, бронза — медленно.
     */
    public double relativeDensity() {
        return densityKgM3 / REF_STEEL_DENSITY * INERTIA_AT_REF;
    }

    /**
     * Вязкий коэффициент трения сети (milli-Nm на milli-RPM):
     * масштабированный реальный коэффициент трения μ.
     */
    public double viscousFriction() {
        return frictionCoefficient / VISCOUS_FRICTION_DIVISOR;
    }

    /**
     * Безопасные обороты, RPM: выше — износ (ShaftWearHook).
     * Эвристика из прочности: усталостные повреждения растут с σ_y,
     * лимит масштабируется квадратично (∝ √σ_y) от базовой стали.
     */
    public int maxSafeSpeedRpm() {
        return (int) Math.max(1, Math.round(
                REF_SAFE_RPM * Math.sqrt(yieldTensileMpa / REF_YIELD_SHEAR_MPA)));
    }

    @Override
    public String toString() {
        return name;
    }

    /**
     * Builder с автоматическим выводом зависимых величин.
     *
     * <p>Задаются только независимые физические константы; производные
     * считаются сами:
     * <ul>
     *   <li>G = E / (2(1+ν)) — закон Гука для изотропного тела
     *       ({@code shearModulusGpa()} — оверрайд для анизотропных, дерево);</li>
     *   <li>τ_y = σ_y / √3 — критерий текучести фон Мизеса;</li>
     *   <li>σ₋₁ = 0.45 · σ_u — эмпирическая оценка предела вынослиости.</li>
     * </ul>
     *
     * <p>Архетипы ({@code steel()}, {@code wood()}, ...) задают типовые
     * константы семейства — новый материал = 3–4 цифры.
     */
    public static final class Builder {

        // Архетипные дефолты (конструкционная сталь)
        private double densityKgM3 = 7850.0;
        private double youngModulusGpa = 210.0;
        private double poissonRatio = 0.3;
        private double yieldStrengthMpa = 300.0;
        private double tensileStrengthMpa = 600.0;
        private double hardnessHb = 180.0;
        private double frictionCoefficient = 0.40;
        private double thermalExpansionPpmPerK = 12.0;
        private double heatCapacityJPerKgK = 490.0;
        private double thermalConductivityWPerMK = 45.0;

        // Оверрайды производных (null — считать автоматически)
        private Double customShearModulusGpa = null;
        private Double customYieldShearMpa = null;
        private Double customFatigueStrengthMpa = null;

        private String name = "?";

        /**
         * Архетип: конструкционная сталь.
         */
        public static Builder steel(String name) {
            return new Builder(name);
        }

        /**
         * Архетип: древесина (дуб, вдоль волокон). Анизотропна — G задаётся явно.
         */
        public static Builder wood(String name) {
            return new Builder(name)
                    .densityKgM3(700)
                    .youngModulusGpa(11.0)
                    .poissonRatio(0.30)
                    .shearModulusGpa(0.7)
                    .yieldStrengthMpa(30)
                    .tensileStrengthMpa(100)
                    .hardnessHb(5)
                    .frictionCoefficient(0.40)
                    .thermalExpansionPpmPerK(5.0)
                    .heatCapacityJPerKgK(1700)
                    .thermalConductivityWPerMK(0.17);
        }

        /**
         * Архетип: бронза (оловянистая, подшипниковая).
         */
        public static Builder bronze(String name) {
            return new Builder(name)
                    .densityKgM3(8800)
                    .youngModulusGpa(100.0)
                    .poissonRatio(0.34)
                    .yieldStrengthMpa(260)
                    .tensileStrengthMpa(350)
                    .hardnessHb(90)
                    .frictionCoefficient(0.16)
                    .thermalExpansionPpmPerK(18.0)
                    .heatCapacityJPerKgK(380)
                    .thermalConductivityWPerMK(70);
        }

        /**
         * Архетип: серый чугун (хрупкий, демпфирующий).
         */
        public static Builder castIron(String name) {
            return new Builder(name)
                    .densityKgM3(7200)
                    .youngModulusGpa(110.0)
                    .poissonRatio(0.25)
                    .yieldStrengthMpa(240)
                    .tensileStrengthMpa(250)
                    .hardnessHb(200)
                    .frictionCoefficient(0.45)
                    .thermalExpansionPpmPerK(11.0)
                    .heatCapacityJPerKgK(460)
                    .thermalConductivityWPerMK(52);
        }

        public Builder(String name) {
            this.name = name;
        }

        // --- Независимые физические параметры ---

        public Builder densityKgM3(double v) {
            this.densityKgM3 = v;
            return this;
        }

        public Builder youngModulusGpa(double v) {
            this.youngModulusGpa = v;
            return this;
        }

        public Builder poissonRatio(double v) {
            this.poissonRatio = v;
            return this;
        }

        /**
         * Предел текучести при РАСТЯЖЕНИИ σ_y (τ_y выводится по фон Мизесу).
         */
        public Builder yieldStrengthMpa(double v) {
            this.yieldStrengthMpa = v;
            return this;
        }

        public Builder tensileStrengthMpa(double v) {
            this.tensileStrengthMpa = v;
            return this;
        }

        public Builder hardnessHb(double v) {
            this.hardnessHb = v;
            return this;
        }

        public Builder frictionCoefficient(double v) {
            this.frictionCoefficient = v;
            return this;
        }

        public Builder thermalExpansionPpmPerK(double v) {
            this.thermalExpansionPpmPerK = v;
            return this;
        }

        public Builder heatCapacityJPerKgK(double v) {
            this.heatCapacityJPerKgK = v;
            return this;
        }

        public Builder thermalConductivityWPerMK(double v) {
            this.thermalConductivityWPerMK = v;
            return this;
        }

        // --- Оверрайды производных ---

        /**
         * Для анизотропных материалов (дерево): G не выводится из E.
         */
        public Builder shearModulusGpa(double v) {
            this.customShearModulusGpa = v;
            return this;
        }

        /**
         * Оверрайд τ_y напрямую (иначе σ_y/√3).
         */
        public Builder yieldShearMpa(double v) {
            this.customYieldShearMpa = v;
            return this;
        }

        /**
         * Оверрайд σ₋₁ напрямую (иначе 0.45·σ_u).
         */
        public Builder fatigueStrengthMpa(double v) {
            this.customFatigueStrengthMpa = v;
            return this;
        }

        public MachineMaterial build() {
            // 1. Упругость: G = E / (2(1+ν))
            final double shearGpa = customShearModulusGpa != null
                    ? customShearModulusGpa
                    : youngModulusGpa / (2.0 * (1.0 + poissonRatio));

            // 2. Прочности: τ_y = σ_y/√3 (фон Мизес), σ₋₁ = 0.45·σ_u
            final double yieldShear = customYieldShearMpa != null
                    ? customYieldShearMpa
                    : yieldStrengthMpa / Math.sqrt(3.0);
            final double fatigue = customFatigueStrengthMpa != null
                    ? customFatigueStrengthMpa
                    : tensileStrengthMpa * 0.45;

            return new MachineMaterial(name,
                    densityKgM3, shearGpa, youngModulusGpa, poissonRatio,
                    yieldStrengthMpa, yieldShear, tensileStrengthMpa, fatigue, hardnessHb,
                    frictionCoefficient, thermalExpansionPpmPerK,
                    heatCapacityJPerKgK, thermalConductivityWPerMK);
        }
    }
}
