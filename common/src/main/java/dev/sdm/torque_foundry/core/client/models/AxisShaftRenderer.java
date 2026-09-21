package dev.sdm.torque_foundry.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.api.render.DefaultModModels;
import dev.sdm.torque_foundry.api.render.MechanicalRenderer;
import dev.sdm.torque_foundry.core.client.render.LODModel;
import dev.sdm.torque_foundry.core.client.render.PartTextures;
import dev.sdm.torque_foundry.core.client.render.gltf.GltfModels;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Общий рендер осевого блока (вал/корпус) из glTF-модели Blender.
 *
 * <p>Ожидаемая структура модели ({@code shaft_lod0.glb}):
 * <ul>
 *   <li>{@code Corp} — статика (станина/корпус), не вращается;</li>
 *   <li>{@code Val} — вал, крутится вокруг оси блока с углом из физики.</li>
 * </ul>
 * Нет файла — fallback на пустую модель (блок виден через ванильную
 * модель + оверлей портов), клиент не падает.
 *
 * <p>Ориентация: ось блока из AXIS. Для оси Y модель как есть (вал Blender
 * вдоль Y); для X/Z — доворот. Подсветку портов дорисовывает базовый
 * {@link MechanicalRenderer}.
 *
 * <p>Различия наследников — два хука: {@link #isRotorVisible} (пустой корпус
 * прячет Val, у вала ротор виден всегда) и {@link #resolveTexture} (одна
 * текстура на всё vs по имени части). Угол ротора общий — из физики BE
 * через {@code MechanicalBlockEntity.getRenderAngle}.
 *
 * @param <T> тип BlockEntity блока
 */
public abstract class AxisShaftRenderer<T extends MechanicalBlockEntity>
        extends MechanicalRenderer<T> {

    private final LODModel horizontalModel;
    private final LODModel verticalModel;
    /** Все части обеих моделей: текстуры проставляются одним проходом без map. */
    private final LODModel.Part[] texturedParts;
    private final LODModel.Part val_h;
    private final LODModel.Part val_v;

    protected AxisShaftRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
        this.horizontalModel = LODModel.createDefault();
        this.verticalModel = LODModel.createDefault();

        final Map<String, LODModel.Part> horizontalParts =
                GltfModels.attachParts(horizontalModel, DefaultModModels.SHAFT_HORIZONTAL);
        this.val_h = horizontalParts.get(DefaultModModels.SHAFT_ID);
        final Map<String, LODModel.Part> verticalParts =
                GltfModels.attachParts(verticalModel, DefaultModModels.SHAFT_VERTICAL);
        this.val_v = verticalParts.get(DefaultModModels.SHAFT_ID);

        this.texturedParts =
                new LODModel.Part[horizontalParts.size() + verticalParts.size()];
        int i = 0;
        for (LODModel.Part part : horizontalParts.values()) {
            this.texturedParts[i++] = part;
        }
        for (LODModel.Part part : verticalParts.values()) {
            this.texturedParts[i++] = part;
        }
    }

    /** Виден ли ротор Val (пустой корпус прячет вал, у вала — всегда виден). */
    protected boolean isRotorVisible(T blockEntity) {
        return true;
    }

    /**
     * Текстура части по имени: одна на всё (вал) или по имени части
     * (корпус: Corp/Val — разные текстуры).
     */
    protected ResourceLocation resolveTexture(T blockEntity, String partName) {
        return PartTextures.resolve(partName, blockEntity.machine.getMaterial());
    }

    /** Угол ротора из физики BE (partialTick-интерполяция — внутри BE). */
    protected float renderAngle(T blockEntity, float partialTick) {
        return blockEntity.getRenderAngle(partialTick);
    }

    @Override
    protected void renderModel(T blockEntity, float partialTick, PoseStack poseStack,
            MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        final double distanceSqr = GltfModels.distanceSqrToCamera(blockEntity.getBlockPos());
        final float angle = renderAngle(blockEntity, partialTick);

        final BlockState state = blockEntity.getBlockState();
        final Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS)
                ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.X;

        // Текстуры частей по материалу блока (крафт/Shift+ПКМ).
        // Резолв кэширован, на кадр — только присваивание полей.
        // Мапы partsH/partsV не храним: resolve идёт по плоскому массиву,
        // имя части — из Part.name (attachParts кладёт его же в ключ мапы).
        for (int i = 0; i < texturedParts.length; i++) {
            final LODModel.Part part = texturedParts[i];
            part.textureOverride = resolveTexture(blockEntity, part.name);
        }

        // Вращение вокруг МОДЕЛЬНОЙ оси вала: в val_horizontal вал лежит
        // вдоль локальной Z, в val_vertical — вдоль локальной Y. Ориентацию
        // в мир переносит poseStack (YP90 для оси X), поэтому локальная ось
        // вращения от оси блока НЕ зависит.
        final boolean rotorVisible = isRotorVisible(blockEntity);
        if (val_h != null) {
            val_h.visible = rotorVisible;
            val_h.rotate(0, 0, angle);
        }
        if (val_v != null) {
            val_v.visible = rotorVisible;
            val_v.rotate(0, angle, 0);
        }

        poseStack.pushPose();
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (axis == Direction.Axis.X) {
            // Модель Blender вдоль Y -> кладём на X.
            poseStack.mulPose(Axis.YP.rotationDegrees(90));
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        if (axis == Direction.Axis.Y) {
            verticalModel.render(poseStack, bufferSource, packedLight, packedOverlay,
                    distanceSqr, partialTick);
        } else {
            horizontalModel.render(poseStack, bufferSource, packedLight, packedOverlay,
                    distanceSqr, partialTick);
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
