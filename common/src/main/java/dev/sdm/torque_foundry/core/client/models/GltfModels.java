package dev.sdm.torque_foundry.core.client.models;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.client.render.LODModel;
import dev.sdm.torque_foundry.core.client.render.gltf.GltfModel;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Загрузка glTF-моделей блоков из assets.
 *
 * <p>Соглашение о файлах (пример для вала):
 * <pre>
 *   assets/torque_foundry/models/block/shaft_lod0.glb  — меши Corp, Val, ...
 *   assets/torque_foundry/models/block/shaft_lod1.glb  — те же имена, проще
 * </pre>
 * LOD-файлы опциональны: нет lod1 — часть одноуровневая. Имена частей =
 * имена мешей в Blender (регистр важен).
 *
 * <p>Модели кэшируются после первой загрузки (клиентский старт/первый чанк).
 * Ошибка загрузки не роняет клиент: в лог + fallback на пустую модель
 * (блок виден через ванильную модель/оверлей портов).
 */
public final class GltfModels {

    private GltfModels() {
    }

    /**
     * Загрузить модель блока: lod-файлы склеиваются по именам частей.
     *
     * @param texture       текстура блока
     * @param unitsToPixels масштаб glTF-единиц в пиксели модели
     * @param basePath      путь без суффикса (например "models/block/shaft"),
     *                      файлы: basePath + "_lod0.glb", "_lod1.glb", ...
     * @param maxLod        сколько LOD-файлов искать (lod0 обязателен)
     */
    public static GltfModel loadBlockModel(ResourceLocation texture, float unitsToPixels,
                                           String basePath, int maxLod) {
        // Байты, не потоки: модель парсится в несколько проходов
        // (авто-масштаб/фит), InputStream одноразовый — второй проход
        // получал пустой буфер и падал BufferUnderflowException.
        final List<byte[]> fileBytes = new ArrayList<>();
        try {
            for (int lod = 0; lod < maxLod; lod++) {
                final String path = "/assets/" + TorqueFoundry.MOD_ID + "/"
                        + basePath + "_lod" + lod + ".glb";
                try (InputStream in = GltfModels.class.getResourceAsStream(path)) {
                    if (in == null) {
                        if (lod == 0) {
                            TorqueFoundry.LOGGER.warn("glTF model missing: {}", path);
                            return empty(texture);
                        }
                        break;
                    }
                    fileBytes.add(in.readAllBytes());
                }
            }
            return GltfModel.load(texture, unitsToPixels, true, true,
                    fileBytes.toArray(new byte[0][]));
        } catch (IOException | IllegalArgumentException e) {
            TorqueFoundry.LOGGER.error("Failed to load glTF model {}", basePath, e);
            return empty(texture);
        }
    }

    private static GltfModel empty(ResourceLocation texture) {
        try {
            return GltfModel.load(texture, GltfModel.DEFAULT_UNITS_TO_PIXELS, false, false);
        } catch (IOException e) {
            // load() без файлов не бросает — ветка недостижима.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Перенос glTF-частей в LODModel: каждая часть = Part с pivot из
     * bounding box. Возвращает мэппинг имя -> Part для анимации.
     */
    public static Map<String, LODModel.Part> attachParts(
            LODModel model, GltfModel gltf) {
        final Map<String, LODModel.Part> out = new LinkedHashMap<>();
        for (GltfModel.Part part : gltf.parts()) {
            final LODModel.Part node = model.part(part.name, part.pivotX, part.pivotY, part.pivotZ);
            for (int lod = 0; lod < part.lods.size(); lod++) {
                if (lod == 0) {
                    node.mesh(part.lods.get(0));
                } else {
                    node.addMeshLod(part.lods.get(lod));
                }
            }
            out.put(part.name, node);
            System.out.println(part.name);
        }
        return out;
    }

    /**
     * Дистанция до камеры для LOD-выбора (общее для всех BER).
     */
    public static double distanceSqrToCamera(net.minecraft.core.BlockPos pos) {
        final var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final double dx = pos.getX() + 0.5 - camPos.x;
        final double dy = pos.getY() + 0.5 - camPos.y;
        final double dz = pos.getZ() + 0.5 - camPos.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
