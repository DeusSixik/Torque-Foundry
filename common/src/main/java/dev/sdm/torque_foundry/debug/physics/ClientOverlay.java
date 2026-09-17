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
import dev.sdm.torque_foundry.physics.basic.MechanicalMachine;
import dev.sdm.torque_foundry.physics.basic.MechanicalPower;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
        ImGui.setNextWindowSize(620.0f, 720.0f, ImGuiCond.FirstUseEver);
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
        ImGui.text("Targeted Block");

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

        ImGui.text("Block: " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + " (" + state.getBlock().getName().getString() + ")");
        ImGui.text("Pos: " + pos.toShortString());
        ImGui.text("Machine: " + machine.getClass().getSimpleName());

        if (state.getBlock() instanceof MechanicalBlock block) {
            ImGui.text("Params: " + formatPower(block.getPower()));
        }

        ImGui.separator();
        ImGui.text("Group");

        final long groupId = machine.getGroupIndex();
        ImGui.text("Group ID: " + (groupId == -1 ? "none" : groupId));

        if (groupId != -1) {
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
            } else {
                ImGui.textDisabled("Group " + groupId + " not synced yet");
            }
        }

        ImGui.separator();
        ImGui.text("Machine");

        ImGui.text("Required: " + formatPower(machine.getRequired()));
        ImGui.text("Inputs: " + formatDirections(machine.getInputDirections()));
        ImGui.text("Outputs: " + formatDirections(machine.getOutputDirections()));

        if (machine instanceof GeneratorMachine generator) {
            ImGui.text("Output: " + formatPower(generator.getOutput()));
        }
    }

    private static String formatPower(MechanicalPower power) {
        return String.format(java.util.Locale.ROOT, "%.3f RPM, %.3f Nm, dir=%s",
                power.getSpeedRpm(), power.getTorqueNm(),
                dev.sdm.torque_foundry.physics.basic.RotationDirection.from(power.getDirection()));
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
