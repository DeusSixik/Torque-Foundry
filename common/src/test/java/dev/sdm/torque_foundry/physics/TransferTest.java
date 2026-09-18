package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.RotationDirection;
import dev.sdm.torque_foundry.physics.basic.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static dev.sdm.torque_foundry.physics.TestRig.SETTLE_TICKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты передачи энергии по цепи: генератор -> валы -> потребитель.
 * Регрессии на отловленные баги: обратные рёбра, двойной счёт,
 * износ, клин, выбег, изолированные валы.
 *
 * Сеть инерционная: раскрутка занимает такты, поэтому почти все тесты
 * прогреваются ({@link TestRig#settle}) до проверки состояний.
 */
public class TransferTest {

    private static final long GEN_SPEED = 256_000;  // 256 RPM
    private static final long GEN_TORQUE = 64_000;  // 64 Nm
    private static final long CON_SPEED = 64_000;   // 64 RPM
    private static final long CON_TORQUE = 32_000;  // 32 Nm

    @Test
    void chainTransfersPower() {
        final MechanicalGroup group = TestRig.line(
                new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD),
                new MechanicalMachine[]{new ShaftMachine(), new ShaftMachine(), new ShaftMachine()},
                new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.FORWARD));

        TestRig.settle(group, SETTLE_TICKS);

        assertEquals(WorkState.WORKING, group.getMachine(0).getWorkState());
        for (int i = 1; i <= 3; i++) {
            assertEquals(WorkState.WORKING, group.getMachine(i).getWorkState(), "shaft " + i);
        }
        assertEquals(WorkState.WORKING, group.getMachine(4).getWorkState());
        // Обороты сети едины на всех машинах (допуск 1 RPM = 1000 milli-RPM
        // на лаг между фазой A и фазой C в пределах одного тика)
        assertEquals(Math.round(group.getCurrentSpeedRpm() * 1000),
                group.getMachine(4).getReceived().getSpeedRaw(), 1000.0);
    }

    @Test
    void noReverseEdges_doubleCount() {
        // Регрессия: BFS создавал обратное ребро и удваивал момент
        final MechanicalGroup group = TestRig.line(
                new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD),
                new MechanicalMachine[]{new ShaftMachine(), new ShaftMachine()},
                new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.FORWARD));

        TestRig.settle(group, SETTLE_TICKS);

        // Момент сети = источник минус трение/нагрузка, но НЕ удвоенный.
        // На входе первого вала момент не больше момента источника.
        assertTrue(group.getMachine(1).getReceived().getTorqueRaw() <= GEN_TORQUE,
                "moment doubled by reverse edge: " + group.getMachine(1).getReceived().getTorqueRaw());
    }

    @Test
    void shaftNextToConsumer_staysIdle() {
        // Регрессия: вал рядом с потребителем не должен крутиться —
        // потребитель не передаёт энергию, у вала нет источника
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine consumer = new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(consumer);

        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(shaft);

        group.computeTick();
        group.computeTick();

        assertEquals(WorkState.IDLE, shaft.getWorkState(), "вал у потребителя не должен крутиться");
        assertEquals(0, shaft.getReceived().getSpeedRaw());
    }

    @Test
    void isolatedShaftNextToConsumer_doesNotCoastWithNetwork() {
        // Регрессия: пока сеть крутится (генератор в другом месте),
        // изолированная пара потребитель+вал не должна "выбегать"
        final MechanicalGroup group = new MechanicalGroup();

        // Работающая ветка
        final MechanicalMachine gen = new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        final MechanicalMachine shaftMain = new ShaftMachine();
        shaftMain.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(gen);
        group.addElement(shaftMain);

        // Изолированная пара: потребитель -> вал (без источника)
        final MechanicalMachine isolatedConsumer = new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.FORWARD);
        isolatedConsumer.setBlockPos(new BlockPos(10, 0, 0));
        group.addElement(isolatedConsumer);
        final MechanicalMachine isolatedShaft = new ShaftMachine();
        isolatedShaft.setBlockPos(new BlockPos(11, 0, 0));
        group.addElement(isolatedShaft);

        TestRig.settle(group, SETTLE_TICKS);

        assertTrue(group.getCurrentSpeedRpm() > 0, "main network must spin");
        assertEquals(WorkState.WORKING, shaftMain.getWorkState());
        assertEquals(WorkState.IDLE, isolatedShaft.getWorkState(),
                "изолированный вал не крутится вместе с чужой сетью");
        assertEquals(0, isolatedShaft.getReceived().getSpeedRaw());
    }

    @Test
    void consumerDoesNotTransfer() {
        // Генератор -> Потребитель -> Вал: вал не питается через потребителя
        final MechanicalGroup group = new MechanicalGroup();

        final MechanicalMachine gen = new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final MechanicalMachine consumer = new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(consumer);

        final MechanicalMachine shaft = new ShaftMachine();
        shaft.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(shaft);

        TestRig.settle(group, SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState());
        assertEquals(WorkState.IDLE, shaft.getWorkState(), "consumer cannot transfer power");
        assertEquals(0, shaft.getReceived().getSpeedRaw());
    }

    @Test
    void deadTailBehindConsumer_doesNotLoadNetwork() {
        // Валы ЗА потребителем механически не связаны с сетью (consumer
        // не пропускает мощность насквозь) — их трение не должно снижать RPM
        final MechanicalMachine gen = TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0));
        final MechanicalGroup withTail = new MechanicalGroup();
        withTail.addElement(gen);
        int x = 1;
        for (int i = 0; i < 8; i++) {
            withTail.addElement(TestRig.shaft(new BlockPos(x++, 0, 0)));
        }
        // Порог выше плато — никогда не включается
        withTail.addElement(TestRig.consumer(500_000, 10_000, new BlockPos(x++, 0, 0)));
        // Мёртвый хвост
        for (int i = 0; i < 3; i++) {
            withTail.addElement(TestRig.shaft(new BlockPos(x++, 0, 0)));
        }

        final MechanicalGroup withoutTail = new MechanicalGroup();
        withoutTail.addElement(TestRig.generator(256_000, 64_000, new BlockPos(0, 0, 0)));
        x = 1;
        for (int i = 0; i < 8; i++) {
            withoutTail.addElement(TestRig.shaft(new BlockPos(x++, 0, 0)));
        }
        withoutTail.addElement(TestRig.consumer(500_000, 10_000, new BlockPos(x, 0, 0)));

        for (int t = 0; t < 4000; t++) {
            withTail.computeTick();
            withoutTail.computeTick();
        }

        assertEquals(withoutTail.getCurrentSpeedRpm(), withTail.getCurrentSpeedRpm(), 0.5,
                "dead tail behind consumer must not drag RPM down");
    }

    @Test
    void wrongDirectionConsumer() {
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine gen = new GeneratorMachine(GEN_SPEED, GEN_TORQUE, RotationDirection.FORWARD);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);

        final MechanicalMachine consumer = new ConsumerMachine(CON_SPEED, CON_TORQUE, RotationDirection.REVERSE);
        consumer.setBlockPos(new BlockPos(1, 0, 0));
        group.addElement(consumer);

        TestRig.settle(group, SETTLE_TICKS);
        assertEquals(WorkState.WRONG_DIRECTION, consumer.getWorkState());
    }

    @Test
    void mergeTwoGenerators() {
        // Регрессия: слияние двух источников в один потребитель.
        // RPM = max источников, момент = сумма.
        final MechanicalGroup group = new MechanicalGroup();
        final MechanicalMachine genA = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        final MechanicalMachine genB = new GeneratorMachine(128_000, 32_000, RotationDirection.FORWARD);
        final MechanicalMachine consumer = new ConsumerMachine(64_000, 32_000, RotationDirection.FORWARD);

        genA.setBlockPos(new BlockPos(0, 0, 0));
        consumer.setBlockPos(new BlockPos(1, 0, 0));
        genB.setBlockPos(new BlockPos(2, 0, 0));

        group.addElement(genA);
        group.addElement(consumer);
        group.addElement(genB);

        TestRig.settle(group, SETTLE_TICKS);

        assertEquals(WorkState.WORKING, consumer.getWorkState());
        assertEquals(256.0, group.getCurrentSpeedRpm(), 1.0, "network at max source speed");
        assertEquals(96_000, consumer.getReceived().getTorqueRaw(),
                "sources must merge their torque");
    }
}
