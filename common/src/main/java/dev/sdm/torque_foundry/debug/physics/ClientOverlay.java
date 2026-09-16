package dev.sdm.torque_foundry.debug.physics;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.sdm.torque_foundry.TorqueFoundry;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
        ImGui.setNextWindowPos(40.0f, 40.0f, ImGuiCond.FirstUseEver);

        ImBoolean open = new ImBoolean(visible);
        if (!ImGui.begin(TorqueFoundry.MOD_ID, open)) {
            ImGui.end();
            visible = open.get();
            return;
        }

        ImGui.end();
    }
}
