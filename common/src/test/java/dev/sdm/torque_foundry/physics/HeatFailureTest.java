package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.BeltDriveMachine;
import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GearboxMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterials;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Предел температуры узла (С1 п.2): на пределе узел теряет ровно то
 * свойство, которым держит нагрузку (раздел 4 документа физики).
 * Зубчатая ступень заклинивает (сеть встаёт), ремень обугливается
 * (ребро разомкнуто, сеть крутится). Отказ персистентный — сам не чинится.
 *
 * <p>Лимит узла = min(материал, смазка); в тестах лимит занижается
 * переопределением, чтобы не греть сеть тысячи тиков до паспортных 400 C.
 */
public class HeatFailureTest {

    private static final long GEN_SPEED = 256_000;  // 256 RPM
    private static final long GEN_TORQUE = 64_000;  // 64 Nm

    /** Редуктор с катастрофическим КПД и заниженным лимитом: ломается быстро. */
    static class HotGearbox extends GearboxMachine {
        HotGearbox(BlockPos pos) {
            super(4, false, false, Direction.WEST, Direction.EAST);
            setBlockPos(pos);
        }

        @Override
        public double getEfficiency() {
            return 0.05;
        }

        @Override
        public double getMaxTemperatureC() {
            return 60.0;
        }
    }

    /** Ремень с катастрофическим КПД и заниженным лимитом. */
    static class HotBelt extends BeltDriveMachine {
        HotBelt(BlockPos pos) {
            super(1, 0.0, Direction.WEST, Direction.EAST);
            setBlockPos(pos);
        }

        @Override
        public double getEfficiency() {
            return 0.05;
        }

        @Override
        public double getMaxTemperatureC() {
            return 60.0;
        }
    }

    /**
     * Цепь генератор -> узел -> потребитель 32 Nm на 64 RPM; позиции вдоль X.
     * Генератор 256 N·m: даже при каппированном дележом потоке тепловой
     * баланс низкоэффективного узла уходит за лимит 60 C.
     */
    private static MechanicalGroup chain(MechanicalMachine middle) {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(
                GEN_SPEED, 256_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        middle.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(middle);
        final ConsumerMachine consumer = new ConsumerMachine(
                64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);
        return group;
    }

    /** Гоняет сеть фиксированно долго: нагрев до лимита занимает ~50–150 тиков,
     *  а {@code settle} может остановиться по стабильности оборотов раньше. */
    private static void runFixed(MechanicalGroup group, int ticks) {
        for (int i = 0; i < ticks; i++) {
            group.computeTick();
        }
    }

    @Test
    void gearbox_overheated_jamsNetwork() {
        final MechanicalGroup group = chain(new HotGearbox(new BlockPos(1, 0, 0)));
        final MechanicalMachine gearbox = group.getMachine(1);
        final MechanicalMachine consumer = group.getMachine(2);

        runFixed(group, 2000);

        assertTrue(gearbox.getSimulationState().isBrokenByHeat(),
                "hot gearbox must reach its temperature limit");
        assertEquals(WorkState.JAMMED, gearbox.getWorkState(),
                "broken gear stage seizes");
        assertEquals(WorkState.JAMMED, group.getMachine(0).getWorkState(),
                "seized stage jams the network up to the source");
        assertEquals(WorkState.IDLE, consumer.getWorkState(),
                "starved consumer behind the seized stage goes idle");
        assertEquals(0.0, group.getCurrentSpeedRpm(), 0.001,
                "seized stage stops the network");
    }

    @Test
    void heatFailure_isPersistent() {
        final MechanicalGroup group = chain(new HotGearbox(new BlockPos(1, 0, 0)));
        runFixed(group, 2000);
        assertTrue(group.getMachine(1).getSimulationState().isBrokenByHeat());

        // Сам не чинится: после остывания (сеть стоит, потерь нет) флаг живёт
        for (int i = 0; i < 300; i++) {
            group.computeTick();
        }
        assertTrue(group.getMachine(1).getSimulationState().isBrokenByHeat(),
                "broken stage must stay broken without player repair");
        assertEquals(WorkState.JAMMED, group.getMachine(1).getWorkState());
    }

    @Test
    void gearbox_belowLimit_neverBreaks() {
        // Штатный редуктор (eta 0.95): равновесная температура далеко от лимита
        final MechanicalGroup group = chain(new GearboxMachine(4, false, false,
                Direction.WEST, Direction.EAST));

        TestRig.settle(group, TestRig.SETTLE_TICKS);
        for (int i = 0; i < 600; i++) {
            group.computeTick();
        }

        assertFalse(group.getMachine(1).getSimulationState().isBrokenByHeat(),
                "healthy gearbox must never break");
        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState());
    }

    @Test
    void belt_overheated_opensCircuit_networkKeepsSpinning() {
        final MechanicalGroup group = chain(new HotBelt(new BlockPos(1, 0, 0)));
        final MechanicalMachine belt = group.getMachine(1);
        final MechanicalMachine consumer = group.getMachine(2);

        runFixed(group, 2000);

        assertTrue(belt.getSimulationState().isBrokenByHeat(),
                "hot belt must reach its temperature limit");
        assertNotEqualsBurnt(belt);
        assertEquals(WorkState.IDLE, consumer.getWorkState(),
                "open circuit starves the consumer");
        assertTrue(group.getCurrentSpeedRpm() > 100,
                "burnt belt does not seize the network: source keeps spinning");
    }

    /** Обугленный ремень не заклинивает: шкив жив, порвано само ребро. */
    private static void assertNotEqualsBurnt(MechanicalMachine belt) {
        org.junit.jupiter.api.Assertions.assertNotEquals(
                WorkState.JAMMED, belt.getWorkState(),
                "burnt belt is an open circuit, not a jam");
    }

    @Test
    void nodeLimit_isMinOfMaterialAndLubricant() {
        // Чугун 400 C; заправка минеральной смазкой (90 C) роняет лимит узла
        final GearboxMachine box = new GearboxMachine(4, false, false,
                Direction.WEST, Direction.EAST);
        assertEquals(400.0, box.getMaxTemperatureC(), 0.001,
                "dry node limited by material only");

        box.getLubricant().fill(dev.sdm.torque_foundry.physics.machine.LubricantKinds.OIL, 100);
        assertEquals(90.0, box.getMaxTemperatureC(), 0.001,
                "oil caps the node limit (min of material and lubricant)");

        // Сменить сорт нельзя, пока старый не выработан: подача отвергается
        final double rejected = box.getLubricant().fill(
                dev.sdm.torque_foundry.physics.machine.LubricantKinds.GREASE, 100);
        assertEquals(0.0, rejected, 0.001, "mixed grades are rejected");
        assertEquals(90.0, box.getMaxTemperatureC(), 0.001,
                "old oil still governs after a rejected refill");

        box.getLubricant().drain();
        assertEquals(400.0, box.getMaxTemperatureC(), 0.001,
                "empty reservoir does not limit the node");

        box.getLubricant().fill(dev.sdm.torque_foundry.physics.machine.LubricantKinds.GREASE, 100);
        assertEquals(120.0, box.getMaxTemperatureC(), 0.001,
                "grease caps at 120 C");
    }

    @Test
    void addonKind_registersWithoutEngineChanges() {
        // Поток аддона: новый сорт — одна регистрация паспорта, движок
        // ничего не знает о его имени; свойства работают через реестр
        final var synthetic = new dev.sdm.torque_foundry.physics.machine.LubricantKind(
                "testaddon:synthetic", "Synthetic", 220.0, 0.90, 0.70);
        final var registered = dev.sdm.torque_foundry.physics.machine.LubricantKinds.register(synthetic);
        org.junit.jupiter.api.Assertions.assertSame(synthetic, registered,
                "fresh id registers as-is");

        final GearboxMachine box = new GearboxMachine(4, false, false,
                Direction.WEST, Direction.EAST);
        box.getLubricant().fill(registered, 100);
        assertEquals(220.0, box.getMaxTemperatureC(), 0.001,
                "addon lubricant caps the node at its own limit");

        // Факторы сорта действуют на смазанные опоры (у вала они есть)
        final ShaftMachine shaft = new ShaftMachine();
        shaft.installBearing(0, BearingType.SLEEVE);
        shaft.getLubricant().fill(registered, 100);
        assertEquals(0.85 * 0.90, shaft.frictionMultiplier(0), 1e-9,
                "addon friction factor applies to lubricated bearing slots");
        assertEquals(0.70, shaft.wearFactor(), 1e-9,
                "addon wear factor applies to lubricated points");

        // Восстановление из сериализации по стабильному id
        box.getLubricant().drain();
        box.getLubricant().restore("testaddon:synthetic", 500);
        assertEquals(220.0, box.getMaxTemperatureC(), 0.001,
                "restore by stable id brings the addon kind back");

        // Незнакомый id (сорт удалён из сборки) — сухой резервуар
        box.getLubricant().restore("testaddon:gone", 500);
        assertFalse(box.getLubricant().available(),
                "unknown kind degrades to NONE: node runs dry");
    }

    @Test
    void passport_limits_byArchetype() {
        assertEquals(500.0, maxOf(PhysicsMaterials.STEEL), 0.001, "steel");
        assertEquals(400.0, maxOf(PhysicsMaterials.IRON), 0.001, "cast iron");
        assertEquals(250.0, maxOf(PhysicsMaterials.BRONZE), 0.001, "bronze");
        assertEquals(120.0, maxOf(PhysicsMaterials.WOOD), 0.001, "wood");
    }

    private static double maxOf(PhysicsMaterial m) {
        return m.maxServiceTemperatureC();
    }
}
