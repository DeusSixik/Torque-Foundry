package dev.sdm.torque_foundry.debug.physics;

import com.mojang.blaze3d.platform.InputConstants;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.registry.client.keymappings.KeyMappingRegistry;
import dev.sdm.torque_foundry.TorqueFoundry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Точка входа отладочного UI: кейбинд N (инспектор) + рендер связки
 * Inspector + Pinned HUD каждый ImGui-кадр.
 *
 * <p>Заменяет старый {@code ClientOverlay} (окно на O со всеми показателями):
 * теперь O убрано, вместо него N открывает инспектор, а закреплённые
 * ПКМ-метрики живут в отдельном компактном HUD.
 */
public final class ClientOverlay {

    private static KeyMapping INSPECTOR_KEY = new KeyMapping(
            "key.torque_foundry.inspector",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N,
            TorqueFoundry.MOD_ID);

    private ClientOverlay() {
    }

    public static void registerKey() {
        KeyMappingRegistry.register(INSPECTOR_KEY);
        // Пины грузим лениво на первом клиентском тике: config уже доступен.
        final boolean[] pinsLoaded = {false};
        ClientTickEvent.CLIENT_POST.register((event) -> {
            if (!pinsLoaded[0]) {
                pinsLoaded[0] = true;
                InspectorWindow.loadPins(DebugPaths.pinFile());
                InspectorWindow.savePins(DebugPaths.pinFile());
            }
            if (INSPECTOR_KEY.consumeClick()) {
                InspectorWindow.toggleInspector();
            }
        });
    }

    /** Вызывается из ImGui post-render каждый кадр. */
    public static void render() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        InspectorWindow.render(minecraft);
        // Пины сохраняем при выгрузке мира/выходе — дешёвый fsync раз в смену.
        InspectorWindow.savePins(DebugPaths.pinFile());
    }
}
