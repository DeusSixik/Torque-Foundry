package dev.sdm.torque_foundry.core.client.models;

import dev.sdm.torque_foundry.core.block.CaseBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Рендер корпуса (Case): Casing рисуется всегда, Val — только когда вставлен
 * вал (shaft_part). Пустой корпус = голый Casing без вала, вращать нечего.
 * Текстуры — по именам частей (Corp/Val) из базового резолва.
 */
public class CaseRenderer extends AxisShaftRenderer<CaseBlockEntity> {

    public CaseRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected boolean isRotorVisible(CaseBlockEntity blockEntity) {
        // Вал вставлен (клиентский BE синхронизирован update-пакетом).
        return !blockEntity.getShaft().isEmpty();
    }
}
