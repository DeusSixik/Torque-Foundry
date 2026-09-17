package dev.sdm.torque_foundry.debug.physics;

import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.registry.client.rendering.BlockEntityRendererRegistry;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.block.TFBlockEntities;
import dev.sdm.torque_foundry.core.client.render.ShaftRenderer;
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

    /**
     * Клиентская регистрация рендеров. Реестры к этому моменту заполнены,
     * поэтому дергается из CLIENT_SETUP, а не из конструктора мода.
     */
    public static void registerRenderers() {
        BlockEntityRendererRegistry.register(TFBlockEntities.SHAFT.get(), ShaftRenderer::new);
        TorqueFoundry.LOGGER.info("Registered client renderers.");
    }

    static {
        // Регистрация BER откладывается до клиентского setup
        ClientLifecycleEvent.CLIENT_SETUP.register(client -> registerRenderers());
    }
}
