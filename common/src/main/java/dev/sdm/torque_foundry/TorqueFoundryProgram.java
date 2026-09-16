package dev.sdm.torque_foundry;

import dev.sdm.torque_foundry.physics.basic.*;

public class TorqueFoundryProgram {

    public static void main(String[] args) {
        BasicEngine engine = new BasicEngine(256, 32); // 256 RPM, 32 Nm — как у тебя
        MechanicalPower power = engine.output();

        System.out.println(power.speed());  // 256000  (это raw, не RPM!)
        System.out.println(power.speedRpm()); // 256.0  — для игрока

        Gearbox gearbox = new Gearbox(3);
        MechanicalPower output = gearbox.transform(power);

        System.out.println(output.speed());     // 85333   (256000 / 3)
        System.out.println(output.speedRpm());  // 85.333  — вот она, точность!
        System.out.println(output.torque());    // 96000
        System.out.println(output.torqueNm());  // 96.0

// Проверка мощности (не забываем, что она в масштабе SCALE*SCALE = 1_000_000):
        System.out.println(power.power());   // 256000 * 32000  = 8_192_000_000
        System.out.println(output.power());  // 85333 * 96000   = 8_191_968_000

// Разница:
        System.out.println(power.power() - output.power()); // 32_000, а не "весь оборот"
// В реальных единицах: 32_000 / 1_000_000 = 0.032 — потеря в 1000 раз меньше, чем была
    }
}
