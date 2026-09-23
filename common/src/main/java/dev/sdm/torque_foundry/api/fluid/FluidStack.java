package dev.sdm.torque_foundry.api.fluid;

import net.minecraft.world.level.material.Fluid;

public class FluidStack {

    public static final FluidStack EMPTY = new FluidStack(
            null, 0
    );

    protected Fluid fluid;
    protected long amount;

    public FluidStack(Fluid fluid, long amount) {
        this.fluid = fluid;
        this.amount = amount;
    }

    public FluidStack set(Fluid fluid, long amount) {
        this.fluid = fluid;
        this.amount = amount;
        return this;
    }

    public FluidStack setFluid(Fluid fluid) {
        this.fluid = fluid;
        return this;
    }

    public FluidStack setAmount(long amount) {
        this.amount = amount;
        return this;
    }

    public Fluid getFluid() {
        return fluid;
    }

    public long getAmount() {
        return amount;
    }

    public FluidStack plus(FluidStack stack) {
        return plus(stack.amount);
    }

    public FluidStack plus(long amount) {
        this.amount += amount;
        return this;
    }

    public FluidStack minus(FluidStack stack) {
        return minus(stack.amount);
    }

    public FluidStack minus(long amount) {
        this.amount = Math.max(this.amount - amount, 0);
        return this;
    }

    public boolean isEmpty() {
        return this == EMPTY;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FluidStack stack)) {
            return false;
        }

        return stack.getFluid().equals(fluid) && stack.getAmount() == amount;
    }

    public FluidStack copy() {
        return new FluidStack(fluid, amount);
    }
}
