package dev.sdm.torque_foundry.debug.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sdm.torque_foundry.api.debug.DebugInfoCollector;
import dev.sdm.torque_foundry.api.debug.DebugInfoProvider;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.debug.physics.InspectorSnapshot.Metric;
import dev.sdm.torque_foundry.physics.TestRig;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * Механизм addDebugInfo: коллектор (секции/ключи/фабрики метрик), интеграция
 * с InspectorSnapshot (кастомные секции после стандартных, пин-stable id),
 * переиспользование коллектора.
 */
class DebugInfoTest {

    /** Тестовая машина со своим вкладом. */
    private static class CustomMachine extends MechanicalMachine {
        boolean called;

        CustomMachine() {
            super(dev.sdm.torque_foundry.physics.RotationalPower.fromRaw(0, 0), (byte) -1);
        }

        @Override
        public void addDebugInfo(DebugInfoCollector collector) {
            super.addDebugInfo(collector);
            called = true;
            collector.section("MyAddon")
                    .add("Charge", "42 J")
                    .bar("Fill", "42%", 0.42f)
                    .alert("Overheat", "yes", true)
                    .hint("Mode", "turbo", "ключом");
        }
    }

    @Test
    void collectorBuildsSectionsAndStableKeys() {
        final DebugInfoCollector collector = new DebugInfoCollector();
        collector.section("MyAddon")
                .add("Charge", "42 J")
                .add("Charge", "43 J"); // повтор label — key тот же

        final List<DebugInfoCollector.Section> sections = collector.sections();
        assertEquals(1, sections.size());
        final DebugInfoCollector.Section section = sections.get(0);
        assertEquals("MyAddon", section.name());
        assertEquals(2, section.entries().size());

        final DebugInfoCollector.Entry first = section.entries().get(0);
        assertEquals("myaddon.charge", first.key(), "стабильный id из section+label");
        assertEquals(section.entries().get(1).key(), first.key(),
                "повторный label — тот же key (пин жив)");
        assertFalse(first.alert());
        assertTrue(Float.isNaN(first.barFraction()));
    }

    @Test
    void collectorRepeatedSectionAppends() {
        final DebugInfoCollector collector = new DebugInfoCollector();
        collector.section("A").add("x", "1");
        collector.section("B").add("y", "2");
        collector.section("A").add("z", "3"); // возврат в существующую

        final List<DebugInfoCollector.Section> sections = collector.sections();
        assertEquals(2, sections.size(), "секция A не задублировалась");
        assertEquals(2, sections.get(0).entries().size());
        assertEquals(1, sections.get(1).entries().size());
    }

    @Test
    void collectorWithoutSectionGoesToDefault() {
        final DebugInfoCollector collector = new DebugInfoCollector();
        collector.add("Orphan", "1");
        assertEquals(1, collector.sections().size());
        assertEquals(DebugInfoCollector.DEFAULT_SECTION, collector.sections().get(0).name());
    }

    @Test
    void machineAddDebugInfoIsCalledAndMergedIntoSnapshot() {
        final CustomMachine machine = new CustomMachine();
        final MechanicalGroup group = new MechanicalGroup(machine);
        group.computeTick();

        final InspectorSnapshot snapshot = InspectorSnapshot.collect(
                "test:block", "1 2 3", "CustomMachine",
                "1", null, null, false, machine, null);
        assertTrue(machine.called, "collect() должен сам вызвать addDebugInfo");

        assertTrue(findSection(snapshot, "MyAddon") != null,
                "кастомная секция попала в снапшот");
        // Стандартные секции базы тоже на месте (super.addDebugInfo).
        assertTrue(snapshot.findById("Machine.state") != null,
                "базовая Machine-секция после super.addDebugInfo");
        final Metric charge = snapshot.findById("myaddon.charge");
        assertEquals("Charge", charge.label());
        assertEquals("42 J", charge.value());
        // Пин-stable: id метрики = id записи коллектора (без двойного префикса).
        assertEquals("myaddon.fill", snapshot.findById("myaddon.fill").id());
    }

    @Test
    void blockEntityAndMachineExtrasOrderAfterStandard() {
        final CustomMachine machine = new CustomMachine();
        new MechanicalGroup(machine).computeTick();

        // Порядок вкладов проверяем через appendExtras (package-private):
        // машина добавляет свою секцию, BE — свою; секции appended в порядке
        // вызова addDebugInfo, после стандартных.
        final DebugInfoCollector extras = new DebugInfoCollector();
        machine.addDebugInfo(extras);
        extras.section("CaseBE").add("Shaft insert", "empty");

        final List<InspectorSnapshot.Section> sections = new java.util.ArrayList<>();
        sections.add(InspectorSnapshot.collect(
                "test:block", "1 2 3", "CustomMachine",
                "1", null, null, false, machine, null).sections().get(0)); // стандартная Group
        InspectorSnapshot.appendExtras(sections, extras);

        assertFalse(sections.isEmpty());
        assertEquals("Group", sections.get(0).name());
        assertEquals("MyAddon", sections.get(sections.size() - 2).name());
        assertEquals("CaseBE", sections.get(sections.size() - 1).name());
    }

    @Test
    void alertAndBarAndHintSurviveConversion() {
        final CustomMachine machine = new CustomMachine() {
            @Override
            public void addDebugInfo(DebugInfoCollector collector) {
                collector.section("S")
                        .alert("Hot", "yes", true)
                        .bar("Fill", "50%", 0.5f)
                        .hint("Mode", "x", "tooltip");
            }
        };

        final InspectorSnapshot snapshot = InspectorSnapshot.collect(
                "test:block", "1 2 3", "M", "1", null, null, false, machine, null);

        final Metric hot = snapshot.findById("s.hot");
        assertTrue(hot != null, "id = ключ коллектора (без двойного префикса)");
        assertTrue(hot.alert(), "alert сохранился");
        final Metric fill = snapshot.findById("s.fill");
        assertTrue(fill.hasBar());
        assertEquals(0.5f, fill.barFraction(), 1e-6f);
        final Metric mode = snapshot.findById("s.mode");
        assertEquals("tooltip", mode.hint());
    }

    @Test
    void baseMachineEmitsStandardSectionsWithHistoricIds() {
        // Стандартные секции теперь отдаёт база MechanicalMachine через
        // addDebugInfo. Id должны совпадать с историческими — иначе слетят
        // сохранённые пины HUD.
        final MechanicalMachine plain = TestRig.shaft(new BlockPos(0, 0, 0));
        final DebugInfoCollector collector = new DebugInfoCollector();
        plain.addDebugInfo(collector);

        final java.util.List<InspectorSnapshot.Section> sections = new java.util.ArrayList<>();
        InspectorSnapshot.appendExtras(sections, collector);

        assertEquals(5, sections.size(), "Machine/Material/Thermal/Bearings/Transmission");
        assertEquals("Machine", sections.get(0).name());
        assertEquals("Transmission", sections.get(4).name());

        final InspectorSnapshot snapshot = InspectorSnapshot.collect(
                "test:block", "1 2 3", "M", "1", null, null, false, plain, null);
        // Исторические id (пины HUD из прошлых сессий остаются живыми).
        assertTrue(snapshot.findById("Machine.state") != null, "Machine.state");
        assertTrue(snapshot.findById("Machine.leafpower") != null, "Machine.leafpower");
        assertTrue(snapshot.findById("Material.derived") != null, "Material.derived");
        assertTrue(snapshot.findById("Thermal.temp") != null, "Thermal.temp");
        assertTrue(snapshot.findById("Bearings.slot0") != null, "Bearings.slot0");
        assertTrue(snapshot.findById("Transmission.efficiency") != null,
                "Transmission.efficiency");
    }

    private static InspectorSnapshot.Section findSection(InspectorSnapshot snapshot, String name) {
        for (InspectorSnapshot.Section section : snapshot.sections()) {
            if (section.name().equals(name)) {
                return section;
            }
        }
        return null;
    }
}
