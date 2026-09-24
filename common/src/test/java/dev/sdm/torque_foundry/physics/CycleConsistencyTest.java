package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GearboxMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.JunctionMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Замкнутые контуры (С1 п.7, раздел 11.2): произведение отношений по кольцу,
 * сокращённое на каждом ребре, должно быть ровно единицей — зубья целые,
 * допуска нет. Сошлось — ребро остаётся связью (клин и разрыв видны), мощность
 * по кольцу не суммируется. Не сошлось — перенапряжение: узел греется,
 * заклинивает, клин расходится по сети.
 */
public class CycleConsistencyTest {

    private static final long GEN_SPEED = 256_000;

    private static void put(MechanicalGroup group, MechanicalMachine m, int x, int z) {
        m.setBlockPos(new BlockPos(x, 0, z));
        group.addElement(m);
    }

    /** Машина на высоте y (ветка кольца вверх от крестовины). */
    private static void putY(MechanicalGroup group, MechanicalMachine m, int x, int y) {
        m.setBlockPos(new BlockPos(x, y, 0));
        group.addElement(m);
    }

    /**
     * Кольцо: генератор -> J1 -> [вал -> J2] и [J -> J -> J -> J2],
     * J2 -> потребитель. Оба пути 1:1 — контур согласован. Кольцо собрано
     * на крестовинах: валы не стыкуются с С/Ю гранями.
     */
    private static MechanicalGroup consistentRing(long genTorqueNm) {
        final MechanicalGroup g = new MechanicalGroup();
        put(g, new GeneratorMachine(GEN_SPEED, genTorqueNm, RotationDirection.FORWARD), 0, 0);
        put(g, new JunctionMachine(), 1, 0);
        put(g, new ShaftMachine(), 2, 0);
        put(g, new JunctionMachine(), 3, 0);
        put(g, new ConsumerMachine(64_000, 32_000, RotationDirection.FORWARD), 4, 0);
        put(g, new JunctionMachine(), 1, -1);
        put(g, new JunctionMachine(), 2, -1);
        put(g, new JunctionMachine(), 3, -1);
        return g;
    }

    /**
     * Тот же контур, но в кольце повышающая ступень x2: произведение
     * отношений 2/1 — не единица. Перенапряжение на J2.
     */
    private static MechanicalGroup inconsistentRing() {
        final MechanicalGroup g = consistentRing(96_000);
        // крестовина (2,-1) -> повышающая ступень x2 (запад-восток)
        g.removeElement(g.getMachine(6));
        final GearboxMachine stepUp = new GearboxMachine(2, true, false,
                Direction.WEST, Direction.EAST);
        put(g, stepUp, 2, -1);
        return g;
    }

    @Test
    void consistentRing_works_withoutOverstrain() {
        final MechanicalGroup g = consistentRing(96_000);
        TestRig.settle(g, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, g.getMachine(4).getWorkState(), "consumer");
        assertEquals(256.0, g.getCurrentSpeedRpm(), 1.0, "network at source speed");
        // Мощность по кольцу не удвоилась: потребитель получает свой спрос
        assertEquals(32_000, g.getMachine(4).getReceived().getTorqueRaw(), 100);
        // Узел встречи не заклинило: контур согласован (трение греет — это норма)
        assertNotEquals(WorkState.JAMMED, g.getMachine(3).getWorkState(),
                "consistent ring must not overstrain the meeting node");
    }

    @Test
    void inconsistentRing_overstrainsAndJams() {
        final MechanicalGroup g = inconsistentRing();
        final MechanicalMachine meeting = g.getMachine(3);

        // Клин мгновенный: перенапряжение не даёт сети проворачиваться
        for (int t = 0; t < 100; t++) {
            g.computeTick();
        }

        assertEquals(WorkState.JAMMED, meeting.getWorkState(),
                "ratio mismatch = overstrain at the meeting node");
        assertTrue(meeting.getSimulationState().getThermalEnergyJ() > 0,
                "overstrain heats the node");
        assertTrue(g.getCurrentSpeedRpm() < 1.0,
                "overstrained ring seizes the network");
        assertEquals(WorkState.JAMMED, g.getMachine(0).getWorkState(),
                "jam spreads to the source");
    }

    @Test
    void inconsistentRejoin_overstrainsAtMeetingNode() {
        // Две ветки с разными произведениями отношений сходятся в одном узле:
        // gen -> J1 -> [x2 -> J2] и J1 -> вверх [x3 -> J3 -> J4 -> J2]
        final MechanicalGroup g = new MechanicalGroup();
        put(g, new GeneratorMachine(GEN_SPEED, 64_000, RotationDirection.FORWARD), 0, 0);
        put(g, new JunctionMachine(), 1, 0);
        put(g, new GearboxMachine(2, true, false, Direction.WEST, Direction.EAST), 2, 0);
        put(g, new JunctionMachine(), 3, 0);                     // узел встречи
        put(g, new ConsumerMachine(512_000, 8_000, RotationDirection.FORWARD), 4, 0);
        // Ветка B вверх по Y: J1 -> ВВЕРХ -> x3 -> восток -> восток -> юг в J2
        putY(g, new GearboxMachine(3, true, false, Direction.DOWN, Direction.EAST), 1, 1);
        putY(g, new JunctionMachine(), 2, 1);
        putY(g, new JunctionMachine(), 3, 1);

        for (int t = 0; t < 100; t++) {
            g.computeTick();
        }

        final MechanicalMachine meeting = g.getMachine(3);
        assertEquals(WorkState.JAMMED, meeting.getWorkState(),
                "2/1 vs 3/1 rejoin = overstrain");
        assertTrue(meeting.getSimulationState().getThermalEnergyJ() > 0,
                "overstrain heats the meeting node");
        assertTrue(g.getCurrentSpeedRpm() < 1.0, "overstrain seizes the network");
    }

    @Test
    void plainNetwork_noFalseOverstrain() {
        // Регрессия: обычная линейная сеть без колец не должна ловить
        // перенапряжение от проверки (пасsthrough-узлы дают 1/1)
        final MechanicalGroup g = new MechanicalGroup();
        put(g, new GeneratorMachine(GEN_SPEED, 96_000, RotationDirection.FORWARD), 0, 0);
        put(g, new ShaftMachine(), 1, 0);
        put(g, new JunctionMachine(), 2, 0);
        put(g, new ShaftMachine(), 3, 0);
        put(g, new ConsumerMachine(64_000, 32_000, RotationDirection.FORWARD), 4, 0);

        TestRig.settle(g, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, g.getMachine(4).getWorkState());
        assertEquals(256.0, g.getCurrentSpeedRpm(), 1.0);
        for (int i = 0; i < g.getSize(); i++) {
            assertNotEquals(WorkState.JAMMED, g.getMachine(i).getWorkState(),
                    "machine " + i + " must not seize in a plain network");
        }
    }
}
