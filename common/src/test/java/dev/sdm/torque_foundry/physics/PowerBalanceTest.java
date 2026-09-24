package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.DifferentialMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.GearboxMachine;
import dev.sdm.torque_foundry.core.machine.JunctionMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.core.machine.TransferCaseMachine;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Регрессии фазы B3: момент входа делится по спросу выходов, а не копируется
 * на каждый выход. До фазы B3 раздатка и дифференциал удваивали энергию сети:
 * каждый выход получал полную мощность входа (раздел 4 документа физики).
 *
 * <p>Топологии: генератор в (0,0,0) отдаёт на EAST; развилка в (1,0,0)
 * принимает WEST и отдаёт EAST (потребитель в (2,0,0)) и NORTH
 * (потребитель в (1,0,-1)).
 */
public class PowerBalanceTest {

    private static final long GEN_SPEED = 256_000;  // 256 RPM
    private static final long GEN_TORQUE = 64_000;  // 64 Nm

    /** Допуск на выданный момент: округление дележа и дробных КПД. */
    private static final long GRANT_EPSILON = 200;

    @Test
    void transferCase_grantsDemand_notFullCopy() {
        // Спрос 16k на ветку: суммарная нагрузка 32k оставляет запас против
        // трения четырёх машин — сеть стабильно выходит на плато источника
        final MechanicalGroup group = branch(
                new TransferCaseMachine(net.minecraft.core.Direction.WEST,
                        net.minecraft.core.Direction.EAST, 1,
                        net.minecraft.core.Direction.NORTH, 1),
                16_000, 16_000);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        final long east = group.getMachine(2).getReceived().getTorqueRaw();
        final long north = group.getMachine(3).getReceived().getTorqueRaw();

        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState(), "east consumer");
        assertEquals(WorkState.WORKING, group.getMachine(3).getWorkState(), "north consumer");
        assertEquals(16_000, east, GRANT_EPSILON,
                "east output gets its demand, not a copy of input");
        assertEquals(16_000, north, GRANT_EPSILON,
                "north output gets its demand, not a copy of input");
        assertTrue(east + north <= GEN_TORQUE,
                "granted torque must not exceed input: " + east + " + " + north);
        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0, "network at source speed");
    }

    @Test
    void differential_grantsDemand_notFullCopy() {
        final MechanicalGroup group = branch(
                new DifferentialMachine(net.minecraft.core.Direction.WEST,
                        net.minecraft.core.Direction.EAST,
                        net.minecraft.core.Direction.NORTH, false),
                20_000, 20_000);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        final long east = group.getMachine(2).getReceived().getTorqueRaw();
        final long north = group.getMachine(3).getReceived().getTorqueRaw();

        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState(), "east consumer");
        assertEquals(WorkState.WORKING, group.getMachine(3).getWorkState(), "north consumer");
        assertEquals(20_000, east, GRANT_EPSILON, "east output gets its demand");
        assertEquals(20_000, north, GRANT_EPSILON, "north output gets its demand");
        assertTrue(east + north <= GEN_TORQUE,
                "granted torque must not exceed input");
    }

    @Test
    void junction_multipleBranches_grantsByDemand() {
        final MechanicalGroup group = branch(new JunctionMachine(), 20_000, 20_000);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState(), "east consumer");
        assertEquals(WorkState.WORKING, group.getMachine(3).getWorkState(), "north consumer");
        assertEquals(20_000, group.getMachine(2).getReceived().getTorqueRaw(), GRANT_EPSILON);
        assertEquals(20_000, group.getMachine(3).getReceived().getTorqueRaw(), GRANT_EPSILON);
    }

    @Test
    void reducer_demandConvertedToInputSide() {
        // Понижающая ступень u=4: 256 RPM -> 64 RPM, момент x4.
        // Потребитель 32 Nm на 64 RPM стоит источнику 32*64/256/0.95 = 8.42 Nm,
        // а не 32 Nm: до конверсии фаза B2 складывала моменты с разных уровней
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine gen = new GeneratorMachine(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final MechanicalMachine gearbox = new GearboxMachine(4, false, false,
                net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST);
        gearbox.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(gearbox);

        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(shaft);

        final ConsumerMachine consumer = new ConsumerMachine(64_000, 32_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(3, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState(),
                "reduced consumer is fully fed: its input cost is 8.4 Nm, not 32");
        assertEquals(32_000, consumer.getReceived().getTorqueRaw(), 100,
                "consumer receives its demanded torque");
        assertEquals(8_421, gearbox.getReceived().getTorqueRaw(), 200,
                "gearbox input cost = demand converted through ratio and eta");
        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0,
                "network plateaus at source speed");
    }

    @Test
    void transferCase_ratioAffectsDemandConversion() {
        // Плечо A повышает скорость x4 (момент /4), плечо B прямой 1:1.
        // Спрос плеча A (8 Nm на 1024 RPM) стоит 8*4/0.94 = 34 Nm входа,
        // плеча B — 8*1/0.94 = 8.5 Nm; суммарно 42.5 Nm из 256 — хватает.
        // Источник 256 N·m: ветвь 4x ест вязкое трение квадратично
        // (0.03·s·u²), слабый источник её не прокрутит
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine gen = new GeneratorMachine(
                GEN_SPEED, 256_000, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final TransferCaseMachine tc = new TransferCaseMachine(
                net.minecraft.core.Direction.WEST,
                net.minecraft.core.Direction.EAST, 4,
                net.minecraft.core.Direction.NORTH, 1);
        tc.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(tc);

        final ConsumerMachine east = new ConsumerMachine(512_000, 8_000, RotationDirection.FORWARD);
        east.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(east);

        final ConsumerMachine north = new ConsumerMachine(64_000, 8_000, RotationDirection.FORWARD);
        north.setBlockPos(new BlockPos(1, 0, -1));
        group.addElement(north);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        assertEquals(WorkState.WORKING, east.getWorkState(), "fast east branch");
        assertEquals(WorkState.WORKING, north.getWorkState(), "straight north branch");
        assertEquals(8_000, east.getReceived().getTorqueRaw(), 100,
                "east gets its demand at the stepped-up side");
        assertEquals(8_000, north.getReceived().getTorqueRaw(), 100,
                "north gets its demand at the 1:1 side");
        assertEquals(42_554, tc.getReceived().getTorqueRaw(), 300,
                "transfer case input cost sums ratio-converted demands with eta");
    }

    @Test
    void transferCase_scarcity_splitsProportionally() {
        // Два потребителя по 96 Nm на источник 64 Nm: сеть встаёт (жёсткий
        // перегруз), но делёж всё равно пропорционален спросу: по ~30 Nm
        // каждому (0.94 КПД узла), а не по полной копии входа каждому
        final MechanicalGroup group = branch(
                new TransferCaseMachine(net.minecraft.core.Direction.WEST,
                        net.minecraft.core.Direction.EAST, 1,
                        net.minecraft.core.Direction.NORTH, 1),
                96_000, 96_000);

        TestRig.settle(group, TestRig.SETTLE_TICKS);

        final long east = group.getMachine(2).getReceived().getTorqueRaw();
        final long north = group.getMachine(3).getReceived().getTorqueRaw();

        assertEquals(east, north, 2,
                "equal demands get equal grants");
        assertTrue(east > 25_000 && east < 32_000,
                "each branch gets its proportional share (~30k), got " + east);
        assertTrue(east + north <= GEN_TORQUE,
                "granted torque must not exceed input: " + east + " + " + north);
        assertEquals(WorkState.JAMMED, group.getMachine(2).getWorkState(),
                "demand above supply jams the branch");
        assertEquals(WorkState.JAMMED, group.getMachine(3).getWorkState());
    }

    // --- хелперы ---

    /**
     * Развилка: генератор -> узел в (1,0,0) -> два потребителя по 20k.
     * Индексы: 0 генератор, 1 узел, 2 восточный, 3 северный.
     */
    private MechanicalGroup branch(MechanicalMachine splitter, long torqueEast, long torqueNorth) {
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine gen = new GeneratorMachine(
                GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        splitter.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(splitter);

        final ConsumerMachine east = new ConsumerMachine(64_000, torqueEast, RotationDirection.FORWARD);
        east.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(east);

        final ConsumerMachine north = new ConsumerMachine(64_000, torqueNorth, RotationDirection.FORWARD);
        north.setBlockPos(new BlockPos(1, 0, -1));
        group.addElement(north);

        return group;
    }
}
