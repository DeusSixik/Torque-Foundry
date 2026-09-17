package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;

import java.util.concurrent.ExecutorService;

public final class PhysicsPipeline {

//    private final MechanicalGroupManager manager;
//    private final ExecutorService executor;
private final int numberThreads;
    private PhysicsTick[] physicsTicks;

    public PhysicsPipeline(int numberThreads) {
        this.numberThreads = numberThreads;
        this.physicsTicks = new PhysicsTick[numberThreads];

//        this.executor = executor;
//        this.manager = new MechanicalGroupManager();
    }

    public void tick() {
        for (int i = 0; i < physicsTicks.length; i++) {
            physicsTicks[i].await();
//
//            physicsTicks[i].initialize();
        }

//        this.pending.await();
//
//        pending.initialize(MechanicalGroupManager.getGroupsPrimitive());
    }
}
