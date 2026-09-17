package dev.sdm.torque_foundry.physics.simulation;

import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import it.unimi.dsi.fastutil.objects.ObjectCollection;

import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

public class PhysicsTick {

    protected int groupsCount;
    protected PendingData[] data;
    protected final AtomicBoolean done = new AtomicBoolean(false);

    public final void initialize(MechanicalGroup[] groups) {
        this.groupsCount = groups.length;
        PendingData[] data = this.data;
        if (data == null || (groupsCount >= data.length)) {
            createNew(groupsCount, groups);
        } else
            updateData(groupsCount, groups);

        done.set(false);
    }

    public final void startTick(ExecutorService service) {

    }

    private void createNew(int groupsCount, MechanicalGroup[] groups) {
        this.data = new PendingData[groupsCount];

        for (int i = 0; i < groups.length; i++) {
            PendingData pendingData = new PendingData();
            pendingData.group = groups[i];
            pendingData.result = new Result();
            this.data[i] = pendingData;
        }
    }

    private void updateData(int groupsCount, MechanicalGroup[] groups) {
        final PendingData[] data = this.data;
        for (int i = 0; i < groupsCount; i++) {
            PendingData pendingData = data[i];
            pendingData.group = groups[i];
            pendingData.result.clear();
        }
    }

    public final boolean isDone() {
        return done.get();
    }

    public void await() {
        int spins = 0;
        while (!this.done.get()) {
            if (spins < 1000) {
                Thread.onSpinWait();
                spins++;
            } else {
                LockSupport.parkNanos(50_000);
            }
        }
    }

    public void markDone() {
        this.done.set(true);
    }

    public static class Result {

        public void clear() {

        }
    }

    private static class PendingData {
        MechanicalGroup group;
        Result result;
    }
}
