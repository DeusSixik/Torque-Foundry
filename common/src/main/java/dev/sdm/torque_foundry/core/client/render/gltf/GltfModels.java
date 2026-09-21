package dev.sdm.torque_foundry.core.client.render.gltf;

import dev.architectury.platform.Platform;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.client.render.LODModel;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

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

    /**
     * Сколько LOD-файлов искать по умолчанию (lod0..lod2).
     */
    private static final int DEFAULT_MAX_LOD = 3;

    private GltfModels() {
    }

    /**
     * Загрузить модель блока: lod-файлы склеиваются по именам частей.
     *
     * @param texture       текстура блока
     * @param unitsToPixels масштаб glTF-единиц в пиксели модели
     * @param basePath      путь без суффикса (например "models/block/shaft"),
     *                      файлы: basePath + "_lod0.glb", "_lod1.glb", ...
     */
    public static GltfModel loadBlockModel(ResourceLocation texture, float unitsToPixels,
                                           String basePath) {
        return loadBlockModel(texture, unitsToPixels, basePath, DEFAULT_MAX_LOD);
    }

    /**
     * Загрузить модель блока: lod-файлы склеиваются по именам частей.
     *
     * <p>Соглашение о файлах: {@code basePath + "_lod0.glb"}, {@code "_lod1.glb"}, ...
     * Файл без суффикса ({@code basePath + ".glb"}, например просто
     * {@code val_horizontal.glb}) считается LOD0 — удобно, пока художник
     * не нарезал уровни. Недостающие уровни догенерируются из LOD0
     * в рантайме ({@code LODGenerator.completeMeshLods}), файлы из Blender
     * имеют приоритет.
     *
     * @param texture       текстура блока
     * @param unitsToPixels масштаб glTF-единиц в пиксели модели
     * @param basePath      путь без суффикса (например "models/block/shaft")
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
                // lod0: сначала пробуем plain-файл без суффикса
                // (val_horizontal.glb), потом val_horizontal_lod0.glb.
                final String path;
                if (lod == 0) {
                    final String plain =
                            "/assets/" + TorqueFoundry.MOD_ID + "/" + basePath + ".glb";
                    try (InputStream in = GltfModels.class.getResourceAsStream(plain)) {
                        if (in != null) {
                            fileBytes.add(in.readAllBytes());
                            TorqueFoundry.LOGGER.info("glTF model {}: using plain file as LOD0", basePath);
                            continue;
                        }
                    }
                    path = "/assets/" + TorqueFoundry.MOD_ID + "/" + basePath + "_lod0.glb";
                } else {
                    path = "/assets/" + TorqueFoundry.MOD_ID + "/" + basePath + "_lod" + lod + ".glb";
                }
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
            final GltfModel model =
                    GltfModel.load(texture, unitsToPixels, true, true, fileBytes.toArray(new byte[0][]));
            // Debug-дамп LODов для проверки в Blender (флаг torque_foundry.dump_lods).
            if (GltfDumper.enabled()) {
                final Map<String, List<Quad[]>> dumpParts = new LinkedHashMap<>();
                for (GltfModel.Part part : model.parts()) {
                    dumpParts.put(part.name, part.lods);
                }
                GltfDumper.dump(basePath, dumpParts, gameDir());
            }
            return model;
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
    public static Map<String, LODModel.Part> attachParts(LODModel model, GltfModel gltf) {
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
        }
        return out;
    }

    /**
     * Каталог игры (для дампов). Через Platform, без завязки на Minecraft
     * раньше времени — вызывается только при включённом дампе.
     */
    private static Path gameDir() {
        return Platform.getGameFolder();
    }

    /**
     * Дистанция до камеры для LOD-выбора (общее для всех BER).
     */
    public static double distanceSqrToCamera(BlockPos pos) {
        final Vec3 camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final double dx = pos.getX() + 0.5 - camPos.x;
        final double dy = pos.getY() + 0.5 - camPos.y;
        final double dz = pos.getZ() + 0.5 - camPos.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
