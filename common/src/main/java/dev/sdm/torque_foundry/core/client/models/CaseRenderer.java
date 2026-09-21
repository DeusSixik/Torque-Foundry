package dev.sdm.torque_foundry.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sdm.torque_foundry.core.block.CaseBlock;
import dev.sdm.torque_foundry.core.block.CaseBlockEntity;
import dev.sdm.torque_foundry.core.client.render.LODModel;
import dev.sdm.torque_foundry.core.client.render.PartTextures;
import dev.sdm.torque_foundry.core.client.render.gltf.GltfModels;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;

/**
 * Рендер корпуса (Case) из glTF-модели вала: Casing (Corp) рисуется всегда,
 * Val — только когда вставлен вал (shaft_part). Пустой корпус = голый
 * Casing без вала, вращать нечего.
 *
 * <p>Ось/повороты/вращение — как у {@link ShaftRenderer}: горизонтальная
 * модель для X/Z, вертикальная для Y; Val крутится углом из физики.
 * Подсветку портов дорисовывает базовый {@link MechanicalRenderer}.
 */
public class CaseRenderer extends MechanicalRenderer<CaseBlockEntity> {

    private final LODModel horizontalModel;
    private final LODModel verticalModel;
    private final Map<String, LODModel.Part> partsH;
    private final Map<String, LODModel.Part> partsV;
    private final LODModel.Part val_h;
    private final LODModel.Part val_v;

    public CaseRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
        this.horizontalModel = LODModel.createDefault();
        this.verticalModel = LODModel.createDefault();

        this.partsH = GltfModels.attachParts(horizontalModel, DefaultModModels.SHAFT_HORIZONTAL);
        this.val_h = partsH.get(DefaultModModels.SHAFT_ID);
        this.partsV = GltfModels.attachParts(verticalModel, DefaultModModels.SHAFT_VERTICAL);
        this.val_v = partsV.get(DefaultModModels.SHAFT_ID);
    }

    @Override
    protected void renderModel(CaseBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        final double distanceSqr = GltfModels.distanceSqrToCamera(blockEntity.getBlockPos());
        final float angle = blockEntity.getRenderAngle(partialTick);

        final BlockState state = blockEntity.getBlockState();
        final Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS)
                ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.X;
        final boolean ceiling = state.hasProperty(CaseBlock.CEILING)
                && state.getValue(CaseBlock.CEILING);

        // Вал вставлен (клиентский BE синхронизирован update-пакетом).
        final boolean hasShaft = !blockEntity.getShaft().isEmpty();

        // Текстуры по именам частей (Corp/Val) и материалу машины.
        // Резолв кэширован, на кадр — только присваивание полей.
        applyTextures(partsH, blockEntity);
        applyTextures(partsV, blockEntity);

        // Val видим только со вставленным валом; вращение — как у ShaftRenderer.
        if (val_h != null) {
            val_h.visible = hasShaft;
            val_h.rotate(0, 0, angle);
        }
        if (val_v != null) {
            val_v.visible = hasShaft;
            val_v.rotate(0, angle, 0);
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (axis == Direction.Axis.X) {
            // Модель Blender вдоль Y -> кладём на X.
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        final boolean vertical = axis == Direction.Axis.Y;
        if (vertical) {
            verticalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        } else {
            horizontalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        }

        poseStack.popPose();
    }

    private static void applyTextures(Map<String, LODModel.Part> parts, CaseBlockEntity be) {
        for (Map.Entry<String, LODModel.Part> entry : parts.entrySet()) {
            entry.getValue().textureOverride =
                    PartTextures.resolve(entry.getKey(), be.machine.getMaterial());
        }
    }

    /**
     * Дальше ванильных 96, чтобы LOD-уровни были видны на дистанции.
     */
    @Override
    public int getViewDistance() {
        return 256;
    }
}
