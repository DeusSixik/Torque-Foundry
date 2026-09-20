package dev.sdm.torque_foundry.core.client.models;

import dev.sdm.torque_foundry.core.client.render.gltf.GltfModel;
import net.minecraft.resources.ResourceLocation;

public final class DefaultModModels {

    /**
     * Текстура по умолчанию
     */
    public static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("torque_foundry", "textures/debug/debug_grid.png");

    /**
     * glTF-единицы -> пиксели: модель в метрах (1 м = блок = 16 px).
     */
    public static final float UNITS_TO_PIXELS = GltfModel.DEFAULT_UNITS_TO_PIXELS;

    /**
     * Модель горизонтального вала
     * <p>
     * Состоит из:
     *    <li>Corp - корпус</li>
     *    <li>Val - вал</li>
     */
    public static final GltfModel SHAFT_HORIZONTAL = GltfModels
            .loadBlockModel(DEFAULT_TEXTURE, UNITS_TO_PIXELS, "models/gltf/val_horizontal", 3);

    /**
     * Модель вертикального вала
     * <p>
     * Состоит из:
     *    <li>Corp - корпус</li>
     *    <li>Val - вал</li>
     */
    public static final GltfModel SHAFT_VERTICAL   = GltfModels
            .loadBlockModel(DEFAULT_TEXTURE, UNITS_TO_PIXELS, "models/gltf/val_vertical", 3);

    public static final String SHAFT_ID = "Val";
    public static final String CORPUS_ID = "Corp";
}
