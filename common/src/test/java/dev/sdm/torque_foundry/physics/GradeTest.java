package dev.sdm.torque_foundry.physics;

import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.machine.ShaftMachine;
import dev.sdm.torque_foundry.physics.RotationDirection;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.ShaftGrade;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Приоритет 8: Grade-балансировка вала. Перекос тира (C=2, B=1, A=0.5,
 * S=0.1) даёт множители: трение (1 + deg x 0.5), износ (1 + deg).
 */
public class GradeTest {

    @Test
    void gradeValues_misalignmentAsDesigned() {
        assertEquals(2.0, ShaftGrade.C.misalignmentDeg(), 1e-9);
        assertEquals(1.0, ShaftGrade.B.misalignmentDeg(), 1e-9);
        assertEquals(0.5, ShaftGrade.A.misalignmentDeg(), 1e-9);
        assertEquals(0.1, ShaftGrade.S.misalignmentDeg(), 1e-9);
    }

    @Test
    void misalignment_raisesFrictionLinearly() {
        final ShaftMachine ideal = shaft(new BlockPos(0, 0, 0));
        final ShaftMachine worst = shaft(new BlockPos(1, 0, 0));
        worst.setMisalignmentDeg(ShaftGrade.C.misalignmentDeg());

        final long speed = 256_000;
        final double idealM = ideal.frictionMultiplier(speed);
        final double worstM = worst.frictionMultiplier(speed);

        assertEquals(1.0, idealM, 1e-9);
        // (1 + 2*0.5) = 2.0
        assertEquals(2.0, worstM, 1e-9);

        // Отрицательный перекос зажимается в ноль
        final ShaftMachine neg = shaft(new BlockPos(2, 0, 0));
        neg.setMisalignmentDeg(-5);
        assertEquals(1.0, neg.frictionMultiplier(speed), 1e-9);
    }

    @Test
    void misalignment_raisesBearingWear() {
        final ShaftMachine s = shaft(new BlockPos(0, 0, 0));
        s.installBearing(0, dev.sdm.torque_foundry.physics.machine.BearingType.ROLLER);
        s.installBearing(1, dev.sdm.torque_foundry.physics.machine.BearingType.ROLLER);
        s.setMisalignmentDeg(ShaftGrade.C.misalignmentDeg()); // износ x3

        for (int t = 0; t < 10_000; t++) {
            s.onSimulationTick(1000, 2_000_000);
        }
        assertTrue(s.getBearing(0).wear() > 0.1,
                "grade C misalignment must wear bearings faster: " + s.getBearing(0).wear());
    }

    @Test
    void gradeS_shaft_spinsUpFasterThanGradeC() {
        final MechanicalGroup gradeS = new MechanicalGroup();
        final GeneratorMachine genS = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        genS.setBlockPos(new BlockPos(0, 0, 0));
        gradeS.addElement(genS);
        final ShaftMachine s1 = shaft(new BlockPos(1, 0, 0));
        s1.setMisalignmentDeg(ShaftGrade.S.misalignmentDeg());
        gradeS.addElement(s1);

        final MechanicalGroup gradeC = new MechanicalGroup();
        final GeneratorMachine genC = new GeneratorMachine(256_000, 64_000, RotationDirection.FORWARD);
        genC.setBlockPos(new BlockPos(0, 0, 0));
        gradeC.addElement(genC);
        final ShaftMachine c1 = shaft(new BlockPos(1, 0, 0));
        c1.setMisalignmentDeg(ShaftGrade.C.misalignmentDeg());
        gradeC.addElement(c1);

        for (int t = 0; t < 5; t++) {
            gradeS.computeTick();
            gradeC.computeTick();
        }

        assertTrue(gradeS.getCurrentSpeedRpm() > gradeC.getCurrentSpeedRpm(),
                "grade S must out-accelerate grade C: " + gradeS.getCurrentSpeedRpm()
                        + " vs " + gradeC.getCurrentSpeedRpm());
    }

    private static ShaftMachine shaft(BlockPos pos) {
        final ShaftMachine s = new ShaftMachine();
        s.setBlockPos(pos);
        return s;
    }
}
