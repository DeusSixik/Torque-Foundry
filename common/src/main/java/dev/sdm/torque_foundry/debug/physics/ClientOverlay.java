package dev.sdm.torque_foundry.debug.physics;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.block.MechanicalBlock;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.data.MechanicalGroupManager;
import dev.sdm.torque_foundry.core.machine.GeneratorMachine;
import dev.sdm.torque_foundry.core.network.ClientGroupCache;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import dev.sdm.torque_foundry.physics.RotationalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.Bearing;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.LubricantState;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;

public final class ClientOverlay {

    private static boolean visible = false;

    public static void toggle() {
//        if (!Platform.isDevelopmentEnvironment()) {
//            return;
//        }
        visible = !visible;
    }

    private static KeyMapping OPEN_GUI = new KeyMapping(
            "open_gui",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O,
            TorqueFoundry.MOD_ID
    );

    public static void registerKey() {

        KeyMappingRegistry.register(OPEN_GUI);
        ClientTickEvent.CLIENT_POST.register((e) -> {
            if(OPEN_GUI.consumeClick()) {
                toggle();
            }
        });
    }

    public static void render() {

        Minecraft minecraft = Minecraft.getInstance();
        ImGui.setNextWindowSize(640.0f, 760.0f, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(4, 4, ImGuiCond.FirstUseEver);

        ImBoolean open = new ImBoolean(visible);
        if (!ImGui.begin(TorqueFoundry.MOD_ID, open, ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize)) {
            ImGui.end();
            visible = open.get();
            return;
        }

        renderTargetedMechanicalBlock(minecraft);

        ImGui.end();
    }

    private static void renderTargetedMechanicalBlock(Minecraft minecraft) {
        ImGui.separator();
        ImGui.text("=== Targeted Block ===");

        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            ImGui.textDisabled("Nothing targeted");
            return;
        }

        final BlockPos pos = hit.getBlockPos();
        final BlockEntity entity = minecraft.level.getBlockEntity(pos);

        if (!(entity instanceof MechanicalBlockEntity mechanical)) {
            final BlockState state = minecraft.level.getBlockState(pos);
            ImGui.textDisabled(pos.toShortString() + " : "
                    + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " (not mechanical)");
            return;
        }

        final BlockState state = mechanical.getBlockState();
        final MechanicalMachine machine = mechanical.machine;

        ImGui.text("Block: " + BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        ImGui.text("Pos: " + pos.toShortString());
        ImGui.text("Machine: " + machine.getClass().getSimpleName());

        if (state.getBlock() instanceof MechanicalBlock block) {
            ImGui.text("Block params: " + formatPower(block.getPower()));
        }

        renderGroupSection(machine);
        ImGui.separator();
        ImGui.text("=== Machine ===");
        renderMachineSection(machine);
        renderMaterialSection(machine);
        renderThermalSection(machine);
        renderBearingsSection(machine);
        renderSourceSection(machine);
        renderSpecSection(machine);
    }

    // --- Группа ---

    private static void renderGroupSection(MechanicalMachine machine) {
        ImGui.separator();
        ImGui.text("=== Group ===");

        final long groupId = machine.getGroupIndex();
        ImGui.text("Group ID: " + (groupId == -1 ? "none" : groupId));

        if (groupId == -1) {
            return;
        }

        final Integer syncedMembers = ClientGroupCache.getMembers(groupId);
        final MechanicalGroup group = syncedMembers == null
                ? MechanicalGroupManager.getGroup(groupId)
                : null;

        if (syncedMembers != null) {
            ImGui.text("Members: " + syncedMembers + " (synced)");
            ImGui.text("Slot in group: " + machine.getGroupElementIndex());
        } else if (group != null) {
            ImGui.text("Members: " + group.getSize());
            ImGui.text("Slot in group: " + machine.getGroupElementIndex());
            ImGui.text("Network speed: " + String.format(java.util.Locale.ROOT, "%.3f RPM",
                    group.getCurrentSpeedRpm()));
        } else {
            ImGui.textDisabled("Group " + groupId + " not synced yet");
        }
    }

    // --- Состояние и мощность ---

    private static void renderMachineSection(MechanicalMachine machine) {
        ImGui.text("State: " + machine.getWorkState());
        ImGui.text("Required: " + formatPower(machine.getRequired()));
        ImGui.text("Received: " + formatPower(machine.getReceived()));
        ImGui.text("Net power: " + machine.getReceived().getPower() + " W");
        ImGui.text("Leaf power: " + machine.getFreePower() + " W");
        ImGui.text("Inputs: " + formatDirections(machine.getInputDirections()));
        ImGui.text("Outputs: " + formatDirections(machine.getOutputDirections()));

        // Показатели симуляции за тик
        final dev.sdm.torque_foundry.physics.machine.SimulationState sim = machine.getSimulationState();
        ImGui.text("Tick power: recv " + sim.getReceivedWatts() + " W, children "
                + sim.getChildrenWatts() + " W, free " + sim.getFreeWatts() + " W");
    }

    // --- Материал ---

    private static void renderMaterialSection(MechanicalMachine machine) {
        ImGui.separator();
        ImGui.text("=== Material ===");

        final dev.sdm.torque_foundry.physics.material.PhysicsMaterial m = machine.getMaterial();
        ImGui.text("Material: " + m.name());
        ImGui.text(String.format(java.util.Locale.ROOT,
                "Safe RPM: %d | T_max: %.0f Nm",
                m.maxSafeSpeedRpm(), m.defaultMaxSafeTorqueNm()));
        ImGui.text(String.format(java.util.Locale.ROOT,
                "E: %.0f GPa | G: %.1f GPa | nu: %.2f | rho: %.0f kg/m3",
                m.youngModulusGpa(), m.shearModulusGpa(), m.poissonRatio(), m.densityKgM3()));
        ImGui.text(String.format(java.util.Locale.ROOT,
                "sigma_y: %.0f MPa | tau_y: %.0f MPa | sigma_u: %.0f MPa | sigma-1: %.0f MPa",
                m.yieldTensileMpa(), m.yieldShearMpa(), m.tensileStrengthMpa(), m.fatigueStrengthMpa()));
        ImGui.text(String.format(java.util.Locale.ROOT,
                "mu: %.2f | HB: %.0f | c: %.0f J/kgK | lambda: %.1f W/mK",
                m.frictionCoefficient(), m.hardnessHb(), m.heatCapacityJPerKgK(),
                m.thermalConductivityWPerMK()));
        ImGui.text(String.format(java.util.Locale.ROOT,
                "Inertia (net): %.3f | Friction coeff: %.5f | Mass: %.2f kg",
                m.relativeDensity(), m.viscousFriction(), m.nominalMassKg()));
    }

    // --- Тепло ---

    private static void renderThermalSection(MechanicalMachine machine) {
        ImGui.separator();
        ImGui.text("=== Thermal ===");

        final dev.sdm.torque_foundry.physics.machine.SimulationState sim = machine.getSimulationState();
        final dev.sdm.torque_foundry.physics.material.PhysicsMaterial m = machine.getMaterial();
        final double t = sim.temperatureC(m, m.nominalMassKg());
        final double overheat = sim.overheatingK(m, m.nominalMassKg());

        ImGui.text(String.format(java.util.Locale.ROOT,
                "Temperature: %.1f C (overheat +%.1f K)", t, overheat));
        ImGui.text(String.format(java.util.Locale.ROOT,
                "Thermal energy: %.1f J | Throughput: %.1f kJ | Overload ticks: %d",
                sim.getThermalEnergyJ(), sim.getTotalThroughputJ() / 1000.0,
                sim.getOverloadTicks()));

        // Визуальный бар нагрева к условному пределу 100 C
        ImGui.progressBar((float) Math.min(1.0, Math.max(0.0, (t - 20.0) / 80.0)));
    }

    // --- Опоры и смазка ---

    private static void renderBearingsSection(MechanicalMachine machine) {
        if (!machine.hasBearingSlots()) {
            return;
        }

        ImGui.separator();
        ImGui.text("=== Bearings (shaft) ===");

        for (int slot = 0; slot < 2; slot++) {
            final Bearing b = machine.getBearing(slot);
            String label = "Slot " + slot + ": " + b.type();
            if (!b.present()) {
                ImGui.text(label + " (bare)");
            } else if (b.broken()) {
                ImGui.textColored(1.0f, 0.3f, 0.3f, 1.0f, label
                        + String.format(java.util.Locale.ROOT, " — BROKEN (%.0f%%)", b.wear() * 100));
            } else {
                ImGui.text(label + String.format(java.util.Locale.ROOT,
                        " — %.0f%% worn, rating %d RPM, friction x%.2f",
                        b.wear() * 100, b.type().rpmRating(),
                        b.frictionMultiplier(machine.getLubricant().available())));
            }
        }

        final LubricantState lube = machine.getLubricant();
        ImGui.text("Lubricant: " + lube.type() + String.format(java.util.Locale.ROOT,
                " %.0f/%.0f (%.0f%%)%s", lube.amount(), LubricantState.CAPACITY,
                lube.amount() / LubricantState.CAPACITY * 100.0,
                lube.available() ? "" : "  [DRY]"));

        ImGui.progressBar((float) (lube.amount() / LubricantState.CAPACITY));

        ImGui.text(String.format(java.util.Locale.ROOT,
                "Misalignment: +%.1f deg | Friction mult: x%.2f | Wear mult: x%.2f",
                machine.getMisalignmentDeg(),
                machine.frictionMultiplier(0),
                machine.wearFactor()));
    }

    // --- Источник ---

    private static void renderSourceSection(MechanicalMachine machine) {
        if (!(machine instanceof GeneratorMachine generator)) {
            return;
        }

        ImGui.separator();
        ImGui.text("=== Source ===");

        final RotationalPower out = generator.getOutput();
        ImGui.text("Rated output: " + formatPower(out));

        final double factor = generator.getOutputFactor();
        ImGui.text(String.format(java.util.Locale.ROOT, "Thermal derate: %.0f%%", factor * 100));
        if (factor < 1.0) {
            ImGui.textColored(1.0f, 0.6f, 0.2f, 1.0f, ">> DERATED (overheated) <<");
        }
        ImGui.progressBar((float) factor);
    }

    // --- Передача (КПД/страгивание/инерция) ---

    private static void renderSpecSection(MechanicalMachine machine) {
        ImGui.separator();
        ImGui.text("=== Transmission ===");

        final double eta = machine.getEfficiency();
        if (eta < 1.0) {
            ImGui.text(String.format(java.util.Locale.ROOT,
                    "Efficiency: %.0f%% (loss heats this machine)", eta * 100));
        } else {
            ImGui.text("Efficiency: 100% (lossless)");
        }

        final long breakaway = machine.getBreakawayTorqueRaw();
        if (breakaway > 0) {
            ImGui.text(String.format(java.util.Locale.ROOT,
                    "Breakaway torque: %.1f Nm", breakaway / 1000.0));
        }

        final long idle = machine.getIdleTorqueRaw();
        if (idle > 0) {
            ImGui.text(String.format(java.util.Locale.ROOT,
                    "Idle torque: %.1f Nm", idle / 1000.0));
        }

        final double extraI = machine.getExtraInertia();
        if (extraI > 0) {
            ImGui.text(String.format(java.util.Locale.ROOT,
                    "Rotor inertia: +%.2f", extraI));
        }
    }

    // --- Форматтеры ---

    private static String formatPower(RotationalPower power) {
        return String.format(java.util.Locale.ROOT, "%.3f RPM, %.3f Nm, dir=%s",
                power.getSpeedRpm(), power.getTorqueNm(),
                dev.sdm.torque_foundry.physics.RotationDirection.from(power.getDirection()));
    }

    private static String formatDirections(Direction[] directions) {
        if (directions == null || directions.length == 0) {
            return "-";
        }

        final StringBuilder sb = new StringBuilder();
        for (Direction direction : directions) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(direction.name());
        }
        return sb.toString();
    }
}
