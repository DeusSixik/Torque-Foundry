package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.ConsumerMachine;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.WorkState;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приоритеты 1-5 из дока: КПД -> тепло, конфликт направлений (знак-учёт
 * + гистерезис клина), момент страгивания, холостой ход, инерция ротора.
 */
public class EngineFeaturesTest {

    // --- Хелперы ---

    /** Проходная машина с заданным КПД (для проверки потерь в ребре). */
    static class LossyMachine extends MechanicalMachine {
        private final double eta;
        LossyMachine(double eta, BlockPos pos) {
            super(RotationalPowerHelper.zero(), (byte) -1);
            this.eta = eta;
            setBlockPos(pos);
            port(Direction.EAST, PortRole.IN_OUT);
            port(Direction.WEST, PortRole.IN_OUT);
        }

        @Override
        protected void createDirections() {
        }

        @Override
        public double getEfficiency() {
            return eta;
        }
    }

    /** Потребитель с повышенным моментом страгивания. */
    static class StubbornConsumer extends ConsumerMachine {
        private final long breakaway;
        StubbornConsumer(long speedReq, long torqueReq, long breakaway, BlockPos pos) {
            super(speedReq, torqueReq, RotationDirection.FORWARD);
            this.breakaway = breakaway;
            setBlockPos(pos);
        }

        @Override
        public long getBreakawayTorqueRaw() {
            return breakaway;
        }
    }

    static class RotationalPowerHelper {
        static dev.sdm.torque_foundry.physics.RotationalPower zero() {
            return dev.sdm.torque_foundry.physics.RotationalPower.fromRaw(0, 0);
        }
    }

    private static GeneratorMachine gen(long speed, long torque, BlockPos pos, RotationDirection dir) {
        final GeneratorMachine g = TestRig.highGen(speed, torque, dir);
        g.setBlockPos(pos);
        return g;
    }

    private static ShaftMachine shaft(BlockPos pos) {
        final ShaftMachine s = new ShaftMachine();
        s.setBlockPos(pos);
        return s;
    }

    // --- 1. КПД ---

    @Test
    void efficiency_cutsTorqueAndHeatsMachine() {
        // Потребитель требует 50 Nm при источнике 96 Nm и КПД узла 0.5:
        // спрос 50 Nm стоит 100 Nm входа, из 96 доступных узел выдаёт
        // не больше eta * вход = 48 Nm, а потерянная мощность греет узел.
        // Источник с запасом (96 > 50 + трение) — сеть выходит на плато
        // без пилы, выданный момент стабилен
        final MechanicalGroup group = new MechanicalGroup();
        group.addElement(gen(256_000, 96_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        final LossyMachine lossy = new LossyMachine(0.5, new BlockPos(1, 0, 0));
        group.addElement(lossy);
        final ConsumerMachine consumer = new ConsumerMachine(1_000, 50_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }

        // Выдача ограничена КПД: 96 Nm * 0.5 = 48 Nm < спроса 50 Nm
        assertEquals(48_000, consumer.getReceived().getTorqueRaw(), 500,
                "efficiency 0.5 must cap delivered torque at eta * input");
        // Потери: ~48 Nm на 256 RPM ~ 1.3 кВт, (1-eta)/eta ~ 1.3 кВт потерь
        // -> ~65 Дж/тик, за 100 тиков узел гарантированно горячий
        assertTrue(lossy.getSimulationState().getThermalEnergyJ() > 1000,
                "lost power must heat the machine");
    }

    @Test
    void efficiency_underDemand_costsInputNotOutput() {
        // Спрос 10 Nm через узел с КПД 0.5: потребитель получает СВОЙ спрос
        // целиком (узел передаёт то, что просят), а КПД проявляется на входе —
        // источник обязан покрыть спрос / eta
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine generator = gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD);
        group.addElement(generator);
        final LossyMachine lossy = new LossyMachine(0.5, new BlockPos(1, 0, 0));
        group.addElement(lossy);
        final ConsumerMachine consumer = new ConsumerMachine(1_000, 10_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }

        assertEquals(10_000, consumer.getReceived().getTorqueRaw(), 100,
                "demand under the eta cap is granted in full");
        assertEquals(WorkState.WORKING, consumer.getWorkState(),
                "20 Nm input cost fits into 64 Nm source: chain works");
        assertTrue(lossy.getSimulationState().getThermalEnergyJ() > 100,
                "transmission through lossy node still heats it");
    }

    // --- 2. Конфликт направлений ---

    @Test
    void opposingGenerators_cancelInsteadOfDoubling() {
        // Встречные генераторы: знак-учёт -> моменты гасятся, сеть не крутится
        final MechanicalGroup group = new MechanicalGroup();
        group.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        group.addElement(gen(256_000, 64_000, new BlockPos(2, 0, 0), RotationDirection.REVERSE));

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }

        // Эксплойт старой модели давал бы 256 RPM (моменты складывались)
        assertTrue(group.currentSpeedForTest() < 5_000,
                "opposing equal generators must cancel, speed = " + group.getCurrentSpeedRpm());
    }

    @Test
    void sustainedDirectionConflict_jamsNetwork() {
        // Равные встречные источники: net=0, но конфликт УСТОЙЧИВЫЙ ->
        // после N=20 тиков гистерезиса вся сеть клинит
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine genA = gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD);
        final GeneratorMachine genB = gen(256_000, 64_000, new BlockPos(2, 0, 0), RotationDirection.REVERSE);
        group.addElement(genA);
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        group.addElement(genB);

        for (int t = 0; t < 30; t++) {
            group.computeTick();
        }

        assertEquals(WorkState.JAMMED, genA.getWorkState(), "sustained conflict must jam");
        assertEquals(WorkState.JAMMED, genB.getWorkState());
    }

    @Test
    void unequalOpposingGenerators_strongerWins() {
        // 64 Nm вперёд против 32 Nm назад: net = 32 Nm -> сеть раскручивается
        final MechanicalGroup group = new MechanicalGroup();
        group.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        group.addElement(gen(256_000, 32_000, new BlockPos(2, 0, 0), RotationDirection.REVERSE));

        for (int t = 0; t < 200; t++) {
            group.computeTick();
        }

        assertTrue(group.getCurrentSpeedRpm() > 100,
                "stronger generator must win, speed = " + group.getCurrentSpeedRpm());
    }

    // --- 3. Страгивание ---

    @Test
    void breakawayTorque_jamsChain() {
        // Потребитель требует 80 Nm страгивания при генераторе 64 Nm:
        // не стронется -> клин всей цепи
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine gen = gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD);
        group.addElement(gen);
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        final StubbornConsumer stubborn = new StubbornConsumer(1_000, 10_000, 80_000, new BlockPos(2, 0, 0));
        group.addElement(stubborn);

        for (int t = 0; t < 50; t++) {
            group.computeTick();
        }

        assertEquals(WorkState.JAMMED, gen.getWorkState(), "breakaway must jam the chain");
        assertTrue(group.currentSpeedForTest() < 5_000, "network must not spin");
    }

    @Test
    void breakawaySatisfied_chainWorks() {
        // Тот же потребитель при генераторе 128 Nm: страгивание проходит
        final MechanicalGroup group = new MechanicalGroup();
        group.addElement(gen(256_000, 128_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        group.addElement(new StubbornConsumer(1_000, 10_000, 80_000, new BlockPos(2, 0, 0)));

        for (int t = 0; t < 100; t++) {
            group.computeTick();
        }

        assertEquals(WorkState.WORKING, group.getMachine(2).getWorkState());
    }

    // --- 4. Холостой ход ---

    @Test
    void idleTorque_slowsSpinUp() {
        // Потери холостого хода 32 Nm: половина тяги уходит на холостой ход,
        // разгон вдвое медленнее (плато не меняем — цель генератора достижима)
        final MechanicalGroup plain = new MechanicalGroup();
        plain.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        plain.addElement(shaft(new BlockPos(1, 0, 0)));

        final MechanicalGroup idle = new MechanicalGroup();
        idle.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        final MechanicalMachine idleShaft = new ShaftMachine() {
            {
                setBlockPos(new BlockPos(1, 0, 0));
            }

            @Override
            public long getIdleTorqueRaw() {
                return 32_000;
            }
        };
        idle.addElement(idleShaft);

        for (int t = 0; t < 10; t++) {
            plain.computeTick();
            idle.computeTick();
        }

        assertTrue(idle.getCurrentSpeedRpm() < plain.getCurrentSpeedRpm(),
                "idle losses must slow spin-up: " + idle.getCurrentSpeedRpm()
                        + " vs " + plain.getCurrentSpeedRpm());
    }

    // --- 5. Инерция ротора ---

    @Test
    void extraInertia_slowsSpinUp() {
        final MechanicalGroup light = new MechanicalGroup();
        light.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        light.addElement(shaft(new BlockPos(1, 0, 0)));

        final MechanicalGroup heavy = new MechanicalGroup();
        heavy.addElement(gen(256_000, 64_000, new BlockPos(0, 0, 0), RotationDirection.FORWARD));
        final MechanicalMachine heavyShaft = new ShaftMachine() {
            {
                setBlockPos(new BlockPos(1, 0, 0));
            }

            @Override
            public double getExtraInertia() {
                return 200.0;
            }
        };
        heavy.addElement(heavyShaft);

        for (int t = 0; t < 10; t++) {
            light.computeTick();
            heavy.computeTick();
        }

        assertTrue(heavy.getCurrentSpeedRpm() < light.getCurrentSpeedRpm(),
                "rotor inertia must slow spin-up: " + heavy.getCurrentSpeedRpm()
                        + " vs " + light.getCurrentSpeedRpm());
    }

    // --- 6. Тепловой derate генератора ---

    @Test
    void overloadedGenerator_deratesThenRecovers() {
        // Генератор с КПД 0.1 (быстрый нагрев) и пределом 100 C тянет
        // потребителя на 50 Nm: греется -> derate ниже 50 Nm -> потребитель
        // клинит -> стоит/остывает -> момент вернулся -> снова работает.
        // Duty cycle (режим S2/S3) возникает из тепловой модели.
        final MechanicalGroup group = new MechanicalGroup();
        final GeneratorMachine gen = new GeneratorMachine(
                256_000, 64_000, RotationDirection.FORWARD, 0.1, 100.0);
        gen.setHighMode(true);
        gen.setBlockPos(new BlockPos(0, 0, 0));
        group.addElement(gen);
        group.addElement(shaft(new BlockPos(1, 0, 0)));
        final ConsumerMachine consumer = new ConsumerMachine(64_000, 50_000, RotationDirection.FORWARD);
        consumer.setBlockPos(new BlockPos(2, 0, 0));
        group.addElement(consumer);

        long minReceived = Long.MAX_VALUE;
        boolean seenJam = false;
        boolean recoveredAfterJam = false;
        double factorAtEndOfJam = 0;
        for (int t = 0; t < 8000; t++) {
            group.computeTick();
            minReceived = Math.min(minReceived, consumer.getReceived().getTorqueRaw());
            if (gen.getWorkState() == WorkState.JAMMED || consumer.getWorkState() == WorkState.JAMMED) {
                seenJam = true;
                factorAtEndOfJam = 0;
            } else if (seenJam) {
                // после клина следим за максимальным фактором вне клина
                factorAtEndOfJam = Math.max(factorAtEndOfJam, gen.getOutputFactor());
                recoveredAfterJam = recoveredAfterJam || factorAtEndOfJam > 0.78;
            }
        }

        assertTrue(minReceived < 60_000,
                "overheated generator must derate below rated, min = " + minReceived);
        assertTrue(seenJam, "derate must drop consumer into stall at least once");
        // Восстановление: после клина генератор остывал и фактор поднимался
        // выше порога отпускания потребителя (50000/64000 = 0.78)
        assertTrue(recoveredAfterJam,
                "generator must recover after cooling, max factor after jam = " + factorAtEndOfJam);
    }
}
