package dev.sdm.torque_foundry.physics.basic;

public class Gearbox {

    // Степень двойки 2, 4, 6, 8 ...
    private final int ratio;

    // В будущем можно добавить перевод потери в тепло
    // private int heat = 0;
    // Пока редуктор будет в степени двойки

    public Gearbox(int ratio) {
        this.ratio = ratio;
    }

    public MechanicalPower transform(MechanicalPower input) {

        /*
        int outSpeed = input.speed() / ratio;
        int outTorque = input.torque() * ratio;

        // Считаем потерянную мощность
        long inputPower = input.power();
        long outputPower = (long) outSpeed * outTorque;
        long lostPower = inputPower - outputPower;

        // Добавляем потерю в нагрев (например, 1 единица мощности = 1 единица тепла)
        this.heat += lostPower;
         */

        return new MechanicalPower(
                input.speed() / ratio,
                input.torque() * ratio
        );
    }
}
