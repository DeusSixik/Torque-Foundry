package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Физический паспорт материала: валидация, согласованность упругих
 * констант (G = E / 2(1+ν)), производные величины (масса, жёсткость).
 */
public class MaterialTest {

    @Test
    void presets_arePhysicallyConsistent() {
        // Изотропные металлы: G согласован с E и ν с точностью 10%
        for (PhysicsMaterial m : new PhysicsMaterial[]{
                PhysicsMaterials.BRONZE, PhysicsMaterials.IRON, PhysicsMaterials.STEEL}) {
            assertTrue(m.shearModulusDeviation() < 0.10,
                    m + ": G deviates from E/(2(1+nu)) by "
                            + (m.shearModulusDeviation() * 100) + "%");
        }
        // Дерево анизотропно: G вдоль волокон (кручение) много меньше
        // изотропного вывода из E (растяжение вдоль волокон) — это фича данных
        assertTrue(PhysicsMaterials.WOOD.shearModulusDeviation() > 0.5,
                "wood must stay anisotropic");
        for (PhysicsMaterial m : new PhysicsMaterial[]{
                PhysicsMaterials.WOOD, PhysicsMaterials.BRONZE,
                PhysicsMaterials.IRON, PhysicsMaterials.STEEL}) {
            // Предел текучести на сдвиг ниже временного сопротивления
            assertTrue(m.yieldShearMpa() < m.tensileStrengthMpa(),
                    m + ": shear yield must be below tensile strength");
            // Усталостная прочность ниже временного сопротивления
            assertTrue(m.fatigueStrengthMpa() < m.tensileStrengthMpa(),
                    m + ": fatigue strength must be below tensile strength");
            // ν в физическом диапазоне
            assertTrue(m.poissonRatio() > 0 && m.poissonRatio() < 0.5,
                    m + ": poisson ratio out of range");
        }
    }

    @Test
    void densityOrdering_lightToHeavy() {
        assertTrue(PhysicsMaterials.WOOD.densityKgM3() < PhysicsMaterials.IRON.densityKgM3());
        assertTrue(PhysicsMaterials.IRON.densityKgM3() < PhysicsMaterials.BRONZE.densityKgM3());
    }

    @Test
    void stiffnessOrdering_woodToSteel() {
        assertTrue(PhysicsMaterials.WOOD.youngModulusGpa() < PhysicsMaterials.BRONZE.youngModulusGpa());
        assertTrue(PhysicsMaterials.BRONZE.youngModulusGpa() < PhysicsMaterials.IRON.youngModulusGpa());
        assertTrue(PhysicsMaterials.IRON.youngModulusGpa() < PhysicsMaterials.STEEL.youngModulusGpa());
    }

    @Test
    void mass_fromVolume() {
        // Стальной брус 1 m³ = 7850 kg
        assertEquals(7850, PhysicsMaterials.STEEL.mass(1.0), 1e-6);
        // Вал r=0.05 м, L=1 м: V = π·0.0025 ≈ 0.007854 m³
        final double volume = Math.PI * 0.05 * 0.05 * 1.0;
        assertEquals(7850 * volume, PhysicsMaterials.STEEL.mass(volume), 1e-6);
    }

    @Test
    void thermalExpansion_ppmToPerK() {
        // Сталь: 12 ppm/K = 12e-6 1/K; нагрев на 100 K -> ΔL/L = 0.0012
        assertEquals(12.0e-6, PhysicsMaterials.STEEL.thermalExpansionPerK(), 1e-12);
        assertEquals(0.0012, PhysicsMaterials.STEEL.thermalExpansionPerK() * 100, 1e-9);
    }

    @Test
    void torsionalStiffness_solidShaft() {
        // G стали выведен из E и ν: 210/(2·1.3) ≈ 80.77 GPa
        final double g = PhysicsMaterials.STEEL.shearModulusGpa();
        final double k = PhysicsMaterials.STEEL.torsionalStiffness(0.05, 1.0);
        assertEquals(g * 1.0e9 * Math.PI * Math.pow(0.05, 4) / 2.0, k, 1.0);

        // Деревянный вал того же сечения в ~100 раз податливее
        final double kWood = PhysicsMaterials.WOOD.torsionalStiffness(0.05, 1.0);
        assertTrue(kWood < k / 50, "wood must be far less stiff in torsion");
    }

    @Test
    void shaftInertia_halfMrSquared() {
        // Стальной вал r=0.05, L=1: m = 7850·π·0.0025 ≈ 61.69 kg
        // I = ½ m r² ≈ 0.0771 kg·m²
        final double expected = 0.5 * PhysicsMaterials.STEEL.mass(Math.PI * 0.0025 * 1.0) * 0.0025;
        assertEquals(expected, PhysicsMaterials.STEEL.shaftInertia(0.05, 1.0), 1e-9);
    }

    @Test
    void builder_producesConsistentDerived() {
        final PhysicsMaterial m = PhysicsMaterial.Builder.steel("Test")
                .tensileStrengthMpa(100).yieldStrengthMpa(50).hardnessHb(50)
                .build();
        assertEquals("Test", m.name());
        // G выведен из E и ν
        assertEquals(m.youngModulusGpa() / (2 * (1 + m.poissonRatio())),
                m.shearModulusGpa(), 1e-9);
        // τ_y выведен по фон Мизесу
        assertEquals(m.yieldTensileMpa() / Math.sqrt(3.0), m.yieldShearMpa(), 1e-6);
        // σ₋₁ выведен из σ_u
        assertEquals(m.tensileStrengthMpa() * 0.45, m.fatigueStrengthMpa(), 1e-6);
    }

    @Test
    void overrides_takePrecedenceOverDerived() {
        final PhysicsMaterial m = PhysicsMaterial.Builder.steel("Ovr")
                .shearModulusGpa(10).fatigueStrengthMpa(111).yieldShearMpa(22)
                .build();
        assertEquals(10, m.shearModulusGpa(), 1e-9);
        assertEquals(111, m.fatigueStrengthMpa(), 1e-6);
        assertEquals(22, m.yieldShearMpa(), 1e-9);
    }

    @Test
    void validation_rejectsBadPoisson() {
        assertThrows(IllegalArgumentException.class,
                () -> PhysicsMaterial.Builder.steel("Bad").poissonRatio(0.7).build());
        assertThrows(IllegalArgumentException.class,
                () -> PhysicsMaterial.Builder.steel("Bad").poissonRatio(-0.1).build());
    }

    @Test
    void validation_rejectsBadValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsMaterial.Builder(" ").build());
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsMaterial.Builder("X").densityKgM3(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsMaterial.Builder("X").tensileStrengthMpa(0).build());
        // σ_y выше σ_u — физически невозможный материал
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicsMaterial.Builder("X").yieldStrengthMpa(500)
                        .tensileStrengthMpa(400).build());
    }

    @Test
    void maxSafeTorque_scalesWithRadiusAndMaterial() {
        // Сталь: τ_y = 300/√3 ≈ 173.2 МПа; Wp(0.125) ≈ 0.003068; T = Wp·τ_y/2
        final double expected = Math.PI * Math.pow(0.125, 3) / 2.0
                * (300.0 / Math.sqrt(3.0)) * 1e6 / 2.0;
        assertEquals(expected, PhysicsMaterials.STEEL.defaultMaxSafeTorqueNm(), 1.0);

        // Куб радиуса: вал r=0.05 выдерживает в (0.05/0.125)³ ≈ 1/15.6 меньше
        final double small = PhysicsMaterials.STEEL.maxSafeTorqueNm(0.05, 2.0);
        assertEquals(small, PhysicsMaterials.STEEL.maxSafeTorqueNm(0.125, 2.0)
                * Math.pow(0.05 / 0.125, 3), 1.0);

        // Дерево слабее стали пропорционально τ_y
        assertTrue(PhysicsMaterials.WOOD.defaultMaxSafeTorqueNm()
                < PhysicsMaterials.STEEL.defaultMaxSafeTorqueNm() / 10);
    }

    @Test
    void derivedGameProxies_trackPhysics() {
        // Инерция сети — нормировка от стали: бронза тяжелее стали
        assertTrue(PhysicsMaterials.BRONZE.relativeDensity() > PhysicsMaterials.STEEL.relativeDensity());
        // Дерево намного легче
        assertTrue(PhysicsMaterials.WOOD.relativeDensity() < PhysicsMaterials.IRON.relativeDensity() / 2);
        // Трение сети — μ/15: бронза (μ=0.16) скользкая, чугун (μ=0.45) нет
        assertTrue(PhysicsMaterials.BRONZE.viscousFriction() < PhysicsMaterials.IRON.viscousFriction());
        // Лимит оборотов ∝ √σ_y: сталь (σ_y=300) обгоняет дерево (σ_y=30) в ~3.2 раза
        assertTrue(PhysicsMaterials.STEEL.maxSafeSpeedRpm() > PhysicsMaterials.WOOD.maxSafeSpeedRpm() * 3);
        // Базовая калибровка: сталь с σ_y=300/√3 τ_y ≈ 256 RPM
        assertTrue(PhysicsMaterials.STEEL.maxSafeSpeedRpm() > 300,
                "steel σ_y=300 must exceed reference, got " + PhysicsMaterials.STEEL.maxSafeSpeedRpm());
    }
}
