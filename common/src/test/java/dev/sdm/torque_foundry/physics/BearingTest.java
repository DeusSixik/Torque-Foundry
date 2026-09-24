package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.Bearing;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.LubricantState;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приоритет 7: подшипники как модификаторы узла — трение, износ от
 * превышения рейтинга, сухой ход, расход смазки, отказ опоры.
 */
public class BearingTest {

    private static ShaftMachine shaft(BlockPos pos) {
        final ShaftMachine s = new ShaftMachine();
        s.setBlockPos(pos);
        return s;
    }

    @Test
    void bearings_reduceFriction_bestToWorst() {
        final ShaftMachine s = shaft(new BlockPos(0, 0, 0));
        final long speed = 256_000;
        final double bare = s.frictionMultiplier(speed);
        assertEquals(1.0, bare, 1e-9, "no bearings = baseline friction");

        s.installBearing(0, BearingType.BALL);
        s.installBearing(1, BearingType.BALL);
        final double ball = s.frictionMultiplier(speed);
        assertTrue(ball < 0.7, "two ball bearings must cut friction hard: " + ball);

        s.installBearing(0, BearingType.SLEEVE);
        s.installBearing(1, BearingType.SLEEVE);
        final double sleeve = s.frictionMultiplier(speed);
        assertTrue(sleeve > ball, "sleeve must be worse than ball");
    }

    @Test
    void wornBearing_degradesTowardBare() {
        final Bearing b = new Bearing();
        b.install(BearingType.BALL);
        final boolean lubed = true;

        final double fresh = b.frictionMultiplier(lubed, 1.0);
        // Имитируем износ до отказа
        for (int i = 0; i < 150_000; i++) {
            b.wearTick(10_000_000, 1.0); // в 4 раза выше рейтинга — износ быстрый
        }
        assertTrue(b.broken(), "overrated bearing must fail eventually");
        // Отказавшая опора скребёт корпус — ХУЖЕ голого упора
        assertEquals(1.8, b.frictionMultiplier(lubed, 1.0), 1e-9,
                "broken bearing = scraping metal");
        assertTrue(fresh < 0.6);
    }

    @Test
    void bearingWear_rateGrowsWithSpeedOverRating() {
        final Bearing rated = new Bearing();
        rated.install(BearingType.BALL);
        final Bearing overrated = new Bearing();
        overrated.install(BearingType.SLEEVE); // рейтинг 500 против тех же оборотов

        final long speed = 2_000_000; // 2000 RPM
        for (int t = 0; t < 10_000; t++) {
            rated.wearTick(speed, 1.0);
            overrated.wearTick(speed, 1.0);
        }

        // Шариковый (рейтинг 2500) почти не износился, втулка (500) — сильно
        assertTrue(rated.wear() < 0.05, "within rating = negligible wear");
        assertTrue(overrated.wear() > 0.1,
                "4x over rating must wear fast, got " + overrated.wear());
    }

    @Test
    void lubricant_consumesAndDries() {
        final MechanicalMachine s = shaft(new BlockPos(0, 0, 0));
        s.installBearing(0, BearingType.ROLLER);
        s.installBearing(1, BearingType.ROLLER);
        final LubricantState lube = s.getLubricant();
        assertEquals(100, lube.fill(dev.sdm.torque_foundry.physics.machine.LubricantKinds.GREASE, 100), 1e-9);

        final double start = lube.amount();
        // Симуляционные тики на 256 RPM (256000 milli-RPM)
        for (int t = 0; t < 6000; t++) {
            s.onSimulationTick(1000, 256_000);
        }
        assertTrue(lube.amount() < start,
                "rollers must consume lubricant, " + start + " -> " + lube.amount());
        assertEquals(0.0, lube.amount(), 1e-9, "small fill must dry out in test time");

        // Сухой ход: множитель трения деградирует к голому упору
        assertTrue(s.frictionMultiplier(256_000) > 1.0,
                "dry bearings must raise friction");
        assertTrue(s.wearFactor() > 1.0, "dry run must wear bearings faster");
    }

    @Test
    void ballBearing_needsNoLubricant() {
        final MechanicalMachine s = shaft(new BlockPos(0, 0, 0));
        s.installBearing(0, BearingType.BALL);
        s.installBearing(1, BearingType.BALL);
        // Смазки нет вообще — шариковый закрытый не страдает
        final double m = s.frictionMultiplier(256_000);
        assertTrue(m < 0.5, "sealed ball bearings work dry: " + m);
        assertEquals(0.0, s.getLubricant().amount(), 1e-9);
    }

    @Test
    void removeBearing_returnsItem() {
        final MechanicalMachine s = shaft(new BlockPos(0, 0, 0));
        s.installBearing(0, BearingType.SLEEVE);
        assertEquals(BearingType.SLEEVE, s.removeBearing(0));
        assertEquals(BearingType.NONE, s.getBearing(0).type());
        assertEquals(BearingType.NONE, s.removeBearing(0), "second remove = nothing");
    }

    @Test
    void shaftChain_bearingsSpeedUpSpinUp() {
        // С шариковыми подшипниками трение в 4 раза ниже — разгон быстрее
        // (плато обеих сетей упирается в цель генератора 256 RPM)
        final MechanicalGroup bare = new MechanicalGroup();
        final GeneratorMachine bareGen = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        bareGen.setBlockPos(new BlockPos(0, 0, 0));
        bare.addElement(bareGen);
        final ShaftMachine b1 = shaft(new BlockPos(1, 0, 0));
        final ShaftMachine b2 = shaft(new BlockPos(2, 0, 0));
        bare.addElement(b1);
        bare.addElement(b2);

        final MechanicalGroup ball = new MechanicalGroup();
        final GeneratorMachine ballGen = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        ballGen.setBlockPos(new BlockPos(0, 0, 0));
        ball.addElement(ballGen);
        final ShaftMachine g1 = shaft(new BlockPos(1, 0, 0));
        g1.installBearing(0, BearingType.BALL);
        g1.installBearing(1, BearingType.BALL);
        final ShaftMachine g2 = shaft(new BlockPos(2, 0, 0));
        g2.installBearing(0, BearingType.BALL);
        g2.installBearing(1, BearingType.BALL);
        ball.addElement(g1);
        ball.addElement(g2);

        // Разгон до цели занимает ~9 тиков — сравниваем на ранней фазе
        for (int t = 0; t < 5; t++) {
            bare.computeTick();
            ball.computeTick();
        }

        assertTrue(ball.getCurrentSpeedRpm() > bare.getCurrentSpeedRpm(),
                "ball bearings must speed up spin-up: " + ball.getCurrentSpeedRpm()
                        + " vs " + bare.getCurrentSpeedRpm());
        assertFalse(Double.isNaN(ball.getCurrentSpeedRpm()));
    }
}
