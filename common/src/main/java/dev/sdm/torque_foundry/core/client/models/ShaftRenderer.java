package dev.sdm.torque_foundry.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sdm.torque_foundry.core.block.ShaftBlock;
import dev.sdm.torque_foundry.core.block.ShaftBlockEntity;
import dev.sdm.torque_foundry.core.client.render.LODModel;
import dev.sdm.torque_foundry.core.client.render.PartTextures;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Map;

/**
 * Рендер вала из glTF-модели Blender.
 *
 * <p>Ожидаемая структура модели ({@code shaft_lod0.glb}):
 * <ul>
 *   <li>{@code Corp} — статика (станина/корпус), не вращается;</li>
 *   <li>{@code Val} — вал, крутится вокруг оси блока с углом из физики.</li>
 * </ul>
 * Нет файла — fallback на пустую модель (блок виден через ванильную
 * модель + оверлей портов), клиент не падает.
 *
 * <p>Ориентация: ось блока из AXIS, CEILING переворачивает на потолок.
 * Для оси Y модель как есть (вал Blender вдоль Y); для X/Z — доворот.
 * Подсветку портов дорисовывает базовый {@link MechanicalRenderer}.
 */
public class ShaftRenderer extends MechanicalRenderer<ShaftBlockEntity> {

    private final LODModel horizontalModel;
    private final LODModel verticalModel;
    private final LODModel.Part[] parts;
    private final LODModel.Part val_h;
    private final LODModel.Part val_v;

    public ShaftRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
        this.horizontalModel = LODModel.createDefault();
        this.verticalModel   = LODModel.createDefault();

        Map<String, LODModel.Part> map_v = GltfModels.attachParts(horizontalModel, DefaultModModels.SHAFT_HORIZONTAL);
        this.val_h = map_v.get(DefaultModModels.SHAFT_ID);
        Map<String, LODModel.Part> map_h = GltfModels.attachParts(verticalModel, DefaultModModels.SHAFT_VERTICAL);
        this.val_v = map_h.get(DefaultModModels.SHAFT_ID);

        this.parts = new LODModel.Part[map_v.size() + map_h.size()];

        int i = 0;
        for (Map.Entry<String, LODModel.Part> entry : map_v.entrySet()) {
            this.parts[i++] = entry.getValue();
        }
        for (Map.Entry<String, LODModel.Part> entry : map_h.entrySet()) {
            this.parts[i++] = entry.getValue();
        }
    }

    @Override
    protected void renderModel(ShaftBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                               MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        final double distanceSqr = GltfModels.distanceSqrToCamera(blockEntity.getBlockPos());
        final float angle = blockEntity.getRenderAngle(partialTick);

        final BlockState state = blockEntity.getBlockState();
        final Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS)
                ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.X;
        final boolean ceiling = state.hasProperty(ShaftBlock.CEILING)
                && state.getValue(ShaftBlock.CEILING);

        // Текстуры частей по материалу блока (крафт/Shift+ПКМ): железо —
        // ванильное железо, дерево — доски; своя parts/<part>_<mat>.png перекрывает.
        // Резолв кэширован, на кадр — только присваивание поля.
        final ResourceLocation partTexture =
                PartTextures.resolve("shaft", blockEntity.machine.getMaterial());
        for (int i = 0; i < parts.length; i++) {
            parts[i].textureOverride = partTexture;
        }

        final boolean vertical = axis == Direction.Axis.Y;
        if(vertical) {
            if(val_v != null) {
                switch (axis) {
                    case X -> val_h.rotate(angle, 0, 0);
                    case Z -> val_h.rotate(0, 0, angle);
                    default -> val_h.rotate(0, angle, 0);
                }
            }
        } else if (val_h != null) {
            switch (axis) {
                case X -> val_h.rotate(angle, 0, 0);
                case Z -> val_h.rotate(0, 0, angle);
                default -> val_h.rotate(0, angle, 0);
            }
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (axis == Direction.Axis.X) {
            // Модель Blender вдоль Y -> кладём на X.
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
        }

/*
        // Потолок: переворот верх ногами вокруг центра блока.
        if (ceiling) {
            // Флип на 180 вокруг X: y -> -y — станина уходит с пола на потолок.
            // Поворот (не отражение) — winding треугольников не ломается.
            poseStack.mulPose(Axis.XP.rotationDegrees(180));
        }
*/
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        if (vertical) {
            verticalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        } else {
            horizontalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        }

        poseStack.popPose();
    }

    /**
     * Дальше ванильных 96, чтобы LOD-уровни были видны на дистанции.
     */
    @Override
    public int getViewDistance() {
        return 256;
    }
}
