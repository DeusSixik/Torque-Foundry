package dev.sdm.torque_foundry.core.client.models;

import dev.sdm.torque_foundry.core.block.ShaftBlockEntity;
import dev.sdm.torque_foundry.core.client.render.PartTextures;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Рендер вала: ротор виден всегда, все части — одна текстура материала блока.
 */
public class ShaftRenderer extends AxisShaftRenderer<ShaftBlockEntity> {

    public ShaftRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected ResourceLocation resolveTexture(ShaftBlockEntity blockEntity, String partName) {
        // Вал целиком из одного материала (крафт/Shift+ПКМ): железо —
        // ванильное железо, дерево — доски; своя parts/shaft_<mat>.png перекрывает.
        return PartTextures.resolve("shaft", blockEntity.machine.getMaterial());
    }
}
