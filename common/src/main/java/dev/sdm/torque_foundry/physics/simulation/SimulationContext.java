package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;

/**
 * Контекст одного физического тика группы.
 * Mutable-архитектура: один экземпляр на группу, переиспользуется каждый тик.
 */
public final class SimulationContext {

    private final MechanicalGroup group;
    private long tick;

    public SimulationContext(MechanicalGroup group, long tick) {
        this.group = group;
        this.tick = tick;
    }

    public void setTick(long tick) {
        this.tick = tick;
    }

    public MechanicalGroup getGroup() {
        return group;
    }

    /**
     * Номер физического тика этой группы (счётчик группы).
     */
    public long getTick() {
        return tick;
    }
}
