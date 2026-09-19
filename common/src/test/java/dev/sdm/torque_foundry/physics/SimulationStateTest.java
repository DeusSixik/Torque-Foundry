package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.machine.SimulationState;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Показатели симуляции машины: нагрев трением, охлаждение,
 * мгновенная мощность, счётчики ресурса.
 */
public class SimulationStateTest {

    @Test
    void frictionHeat_accumulatesWithSpeed() {
        final SimulationState state = new SimulationState();
        final double cold = state.getThermalEnergyJ();
        assertEquals(0, cold, 1e-9);

        // Чугунный вал на 256 RPM: трение = 0.03*256000 = 7680 milli-Nm
        state.addFrictionHeat(7680, 256_000);
        // P = 7.68 Nm * 26.8 рад/с ≈ 206 Вт -> за тик ~10.3 Дж
        assertTrue(state.getThermalEnergyJ() > 5,
                "friction at 256 RPM must heat, got " + state.getThermalEnergyJ());
        assertTrue(state.getThermalEnergyJ() < 20);

        // Ноль оборотов/нулевое трение — нагрева нет
        final double warm = state.getThermalEnergyJ();
        state.addFrictionHeat(7680, 0);
        state.addFrictionHeat(0, 256_000);
        assertEquals(warm, state.getThermalEnergyJ(), 1e-9);
    }

    @Test
    void temperature_derivedFromMassAndCapacity() {
        final SimulationState state = new SimulationState();
        // Чугунная деталь 1000 см³: m ≈ 7.2 kg, c = 460
        final double mass = PhysicsMaterials.IRON.nominalMassKg();
        assertEquals(7.2, mass, 0.01);

        // 1000 K · kg · (J/kg/K) -> перегрев
        state.addHeatJ(1000 * mass * PhysicsMaterials.IRON.heatCapacityJPerKgK());
        assertEquals(20 + 1000, state.temperatureC(PhysicsMaterials.IRON, mass), 1.0);
        assertEquals(1000, state.overheatingK(PhysicsMaterials.IRON, mass), 1.0);
    }

    @Test
    void cooling_pullsBackToAmbient() {
        final SimulationState state = new SimulationState();
        final double mass = PhysicsMaterials.IRON.nominalMassKg();
        state.addHeatJ(mass * PhysicsMaterials.IRON.heatCapacityJPerKgK() * 100); // +100 K

        // Охлаждение монотонно тянет к окружающей среде
        double prev = state.overheatingK(PhysicsMaterials.IRON, mass);
        for (int t = 0; t < 4000; t++) {
            state.coolTick(PhysicsMaterials.IRON, mass);
            final double now = state.overheatingK(PhysicsMaterials.IRON, mass);
            assertTrue(now <= prev + 1e-9, "cooling must be monotonic");
            prev = now;
        }
        // τ = c/8 ≈ 57.5 c (1150 тиков); за 4000 тиков (200 c) e^-3.5 → < 5 K
        assertTrue(prev < 10, "must cool significantly, got " + prev);

        // Ниже окружающей не остывает
        for (int t = 0; t < 100_000 && state.overheatingK(PhysicsMaterials.IRON, mass) > 0; t++) {
            state.coolTick(PhysicsMaterials.IRON, mass);
        }
        assertEquals(0.0, state.getThermalEnergyJ(), 1e-6);
    }

    @Test
    void heatNeverNegative() {
        final SimulationState state = new SimulationState();
        state.addHeatJ(-1000);
        assertEquals(0, state.getThermalEnergyJ(), 1e-9);
    }

    @Test
    void tickPower_andThroughput() {
        final SimulationState state = new SimulationState();
        state.setTickPower(2000, 800, 1200);
        assertEquals(2000, state.getReceivedWatts());
        assertEquals(800, state.getChildrenWatts());
        assertEquals(1200, state.getFreeWatts());
        // 2000 Вт / 20 тиков = 100 Дж за тик
        assertEquals(100, state.getTotalThroughputJ());

        state.setTickPower(0, 0, 0);
        assertEquals(0, state.getReceivedWatts());
        assertEquals(100, state.getTotalThroughputJ(), "throughput accumulates");
    }

    @Test
    void liveChain_shaftsHeatUp_whileConsumerTailStaysCold() {
        // Вращающаяся цепь греется, мёртвый хвост (за consumer) — холодный
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine gen = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final ShaftMachine[] shafts = new ShaftMachine[3];
        for (int i = 0; i < 3; i++) {
            shafts[i] = new ShaftMachine();
            shafts[i].setBlockPos(new BlockPos(1 + i, 0, 0));
            group.addElement(shafts[i]);
        }

        // Хвост за потребителем
        final dev.sdm.torque_foundry.core.machine.ConsumerMachine consumer =
                new dev.sdm.torque_foundry.core.machine.ConsumerMachine(500_000, 10_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(4, 0, 0));
        group.addElement(consumer);
        final ShaftMachine dead = new ShaftMachine();
        dead.setBlockPos(new BlockPos(5, 0, 0));
        group.addElement(dead);

        for (int t = 0; t < 500; t++) {
            group.computeTick();
        }

        // Валы в цепи прогрелись (256 RPM, трение железа)
        assertTrue(shafts[0].getSimulationState().getThermalEnergyJ() > 100,
                "driven shaft must heat up");
        // Мёртвый вал не вращается — холодный
        assertEquals(0.0, dead.getSimulationState().getThermalEnergyJ(), 1e-9);
        // Потребитель никогда не включался — счётчик перегруза пуст
        assertEquals(0, consumer.getSimulationState().getOverloadTicks());
    }
}
