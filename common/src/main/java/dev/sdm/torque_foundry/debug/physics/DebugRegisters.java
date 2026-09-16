package dev.sdm.torque_foundry.debug.physics;

import dev.sdm.torque_foundry.TorqueFoundry;
import foundry.imgui.api.ImGuiMCEvents;

public final class DebugRegisters {

    private static boolean installed;

    public static void tryInstall() {
        if (installed) {
            return;
        }
        if (!isClassPresent("foundry.imgui.api.ImGuiMCEvents")) {
            TorqueFoundry.LOGGER.debug("MCImGui is not present, GA debug overlay hook skipped.");
            return;
        }

        ClientOverlay.registerKey();
        ImGuiMCEvents.INSTANCE.postRenderImGuiEvent(ClientOverlay::render);
        installed = true;
        TorqueFoundry.LOGGER.info("Installed GA debug overlay ImGui hook.");

    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, DebugRegisters.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }
}
