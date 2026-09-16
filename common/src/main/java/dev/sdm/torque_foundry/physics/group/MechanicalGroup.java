package dev.sdm.torque_foundry.physics.group;


import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

public class MechanicalGroup {
    protected static final int ADD_SIZE = 4;
    private static final AtomicLong INDEX_GENERATOR = new AtomicLong(0);

    protected long groupId;
    protected MechanicalMachine[] machines;
    protected int size;

    public MechanicalGroup() {
        generateIndex();
        this.machines = new MechanicalMachine[0];
    }

    public MechanicalGroup(long groupId) {
        this.groupId = groupId;
        this.machines = new MechanicalMachine[0];
    }

    public MechanicalGroup(MechanicalMachine... machines) {
        generateIndex();
        setMachines(machines);
    }


    public void setMachines(MechanicalMachine... machines) {
        this.machines = machines;
        this.size = machines.length;
        for (int i = 0; i < machines.length; i++) {
            var machine = machines[i];
            machine.setGroupIndex(groupId);
            machine.setGroupElementIndex(i);
        }
    }

    private void generateIndex() {
        this.groupId = INDEX_GENERATOR.getAndIncrement();;
    }

    public long getGroupId() {
        return groupId;
    }

    public MechanicalMachine getMachine(int index) {
        return machines[index];
    }

    public MechanicalMachine[] getMachines() {
        return machines;
    }

    public int getSize() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int addElement(@NotNull MechanicalMachine machine) {
        ensureCapacity(size + 1);
        int newIndex = size++;
        machines[newIndex] = machine;
        machine.setGroupIndex(groupId);
        machine.setGroupElementIndex(newIndex);
        return size;
    }

    public boolean removeElement(@NotNull MechanicalMachine machine) {
        for (int i = 0; i < size; i++) {
            if (machines[i] == machine) {
                int lastIndex = size - 1;

                if (i < lastIndex) {
                    MechanicalMachine lastMachine = machines[lastIndex];
                    machines[i] = lastMachine;
                    lastMachine.setGroupIndex(groupId);
                    lastMachine.setGroupElementIndex(i);
                }

                machines[lastIndex] = null;
                size--;

                machine.setGroupIndex(-1);
                return true;
            }
        }
        return false;
    }

    public void merge(@NotNull MechanicalGroup otherGroup) {
        if (otherGroup == this || otherGroup.size == 0) {
            return;
        }

        int startOffset = this.size;
        int totalNewSize = this.size + otherGroup.size;

        ensureCapacity(totalNewSize);

        System.arraycopy(otherGroup.machines, 0, this.machines, startOffset, otherGroup.size);
        this.size = totalNewSize;

        for (int i = startOffset; i < this.size; i++) {
            this.machines[i].setGroupIndex(groupId);
            this.machines[i].setGroupElementIndex(i);
        }

        Arrays.fill(otherGroup.machines, 0, otherGroup.size, null);
        otherGroup.size = 0;
    }

    @Nullable
    public MechanicalGroup split(int splitIndex) {
        if (splitIndex < 0 || splitIndex >= size) {
            throw new IndexOutOfBoundsException("Invalid split index: " + splitIndex + ", current size: " + size);
        }

        MechanicalMachine removedMachine = machines[splitIndex];
        int tailSize = size - splitIndex - 1;

        MechanicalGroup newGroup = null;

        if (tailSize > 0) {
            newGroup = new MechanicalGroup();
            newGroup.machines = new MechanicalMachine[tailSize];
            newGroup.size = tailSize;

            System.arraycopy(this.machines, splitIndex + 1, newGroup.machines, 0, tailSize);

            for (int i = 0; i < tailSize; i++) {
                newGroup.machines[i].setGroupIndex(newGroup.groupId);
            }
        }

        for (int i = splitIndex; i < size; i++) {
            machines[i] = null;
        }

        this.size = splitIndex;

        if (removedMachine != null) {
            removedMachine.setGroupIndex(-1);
        }

        return newGroup;
    }

    @Nullable
    public MechanicalGroup split(@NotNull MechanicalMachine machine) {
        for (int i = 0; i < size; i++) {
            if (machines[i] == machine) {
                return split(i);
            }
        }
        return null;
    }

    protected final void ensureCapacity(int minCapacity) {
        if (minCapacity > machines.length) {
            int newCapacity = Math.max(machines.length + ADD_SIZE, minCapacity);
            MechanicalMachine[] newArray = new MechanicalMachine[newCapacity];
            System.arraycopy(machines, 0, newArray, 0, size);
            this.machines = newArray;
        }
    }
}
