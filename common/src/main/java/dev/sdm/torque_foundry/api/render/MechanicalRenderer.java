package dev.sdm.torque_foundry.api.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.client.render.PortFaceOverlay;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

/**
 * Базовый BER всех механических блоков. Берёт на себя подсветку портов
 * ({@link PortFaceOverlay}), чтобы наследникам не приходилось копипастить
 * shouldRender/render в каждый рендер.
 *
 * <p>Контракт: наследник рисует ТОЛЬКО свою модель в
 * {@link #renderModel}, общий {@link #render} оборачивает её оверлеем:
 * модель — в текущем стеке (повороты модели легальны), порты — отдельным
 * чистым стеком (порты машины уже мировые, повороты модели им запрещены).
 *
 * <p>Блоки со статической моделью (генератор/потребитель/шасси) модель
 * не рисуют вовсе — ванилла рисует блок сама, здесь только оверлей.
 * Переопределять ничего не надо, регистрируй {@code MechanicalRenderer::new}.
 *
 * @param <T> тип BlockEntity блока
 */
public class MechanicalRenderer<T extends MechanicalBlockEntity>
        implements BlockEntityRenderer<T> {

    protected final BlockEntityRendererProvider.Context context;

    public MechanicalRenderer(BlockEntityRendererProvider.Context context) {
        this.context = context;
    }

    @Override
    public final void render(T blockEntity, float partialTick, PoseStack poseStack,
                             MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        renderModel(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);

        if (!PortFaceOverlay.shouldRender(blockEntity)) {
            return;
        }
        poseStack.pushPose();
        PortFaceOverlay.render(blockEntity.machine, poseStack, bufferSource, packedLight);
        poseStack.popPose();
    }

    /**
     * Модель блока. По умолчанию пусто (статическая ванильная модель).
     * Переопределяют блоки с кастомной геометрией (вал).
     */
    protected void renderModel(T blockEntity, float partialTick, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
    }

    @Override
    public int getViewDistance() {
        return 64;
    }
}
