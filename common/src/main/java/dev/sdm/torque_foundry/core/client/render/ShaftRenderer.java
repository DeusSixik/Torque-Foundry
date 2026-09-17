package dev.sdm.torque_foundry.core.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sdm.torque_foundry.core.block.ShaftBlock;
import dev.sdm.torque_foundry.core.block.ShaftBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Рендер вала. Две модели:
 *  - горизонтальная (ось X модели): станина + вал-крест, крутится вокруг оси вала;
 *  - вертикальная (порт RotaryCraft ModelShaftV): клетка из 8 стоек с плитами,
 *    внутри крутится крест вокруг Y.
 * Ориентация берётся из свойства AXIS блока; CEILING переворачивает модель на потолок.
 */
public class ShaftRenderer implements BlockEntityRenderer<ShaftBlockEntity> {

    public static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath("torque_foundry", "textures/block/shaft.png");

    private final LODModel horizontalModel;
    private final LODModel.Part horizontalShaft;

    private final LODModel verticalModel;
    private final LODModel.Part verticalShaft;

    public ShaftRenderer(BlockEntityRendererProvider.Context context) {
        // --- Горизонтальный вал ---
        this.horizontalModel = new LODModel(TEXTURE);
        final int texW = 16, texH = 16;

        final LODModel.Part mount = this.horizontalModel.part("mount", 8, 0, 8);
        mount.addBox(0F, 0F, 0F, 16, 1, 16, 0, 0, texW, texH);   // основание
        mount.addBox(0F, 1F, 0F, 1, 12, 16, 0, 1, texW, texH);   // столб
        mount.addBox(15F, 1F, 0F, 1, 12, 16, 0, 1, texW, texH);  // столб

        this.horizontalShaft = this.horizontalModel.part("shaft", 8, 8, 8);
        this.horizontalShaft.addBox(0F, 7F, 7F, 16, 2, 2, 0, 1, texW, texH);
        this.horizontalShaft.setRenderDistanceScalar(2.0);

        final LODModel.Part cross = this.horizontalShaft.child("cross", 8, 8, 8);
        cross.rotate((float) (Math.PI / 4), 0, 0);
        cross.addBox(0F, 7F, 7F, 16, 2, 2, 0, 1, texW, texH);

        // --- Вертикальный вал (порт ModelShaftV, tex 128x32) ---
        this.verticalModel = new LODModel(TEXTURE);
        final int vw = 128, vh = 32;

        final LODModel.Part vmount = this.verticalModel.part("mount", 8, 8, 8);
        vmount.addBox(1F, 0F, 1F, 14, 1, 14, 0, 0, vw, vh);      // нижняя плита (Shape15b)
        vmount.addBox(1F, 15F, 1F, 14, 1, 14, 0, 0, vw, vh);     // верхняя плита (Shape15ba)
        // 8 угловых стоек (Shape1..Shape1e/g)
        vmount.addBox(2F, 0F, 15F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(13F, 0F, 15F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(0F, 0F, 2F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(2F, 0F, 0F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(13F, 0F, 0F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(15F, 0F, 2F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(15F, 0F, 13F, 1, 16, 1, 62, 0, vw, vh);
        vmount.addBox(0F, 0F, 13F, 1, 16, 1, 62, 0, vw, vh);

        // Вращающийся крест: два бара 2x17x2, один под 45 (Shape2/Shape2b).
        // По оригиналу чуть выходит за блок сверху и снизу (y = -0.5..16.5).
        this.verticalShaft = this.verticalModel.part("shaft", 8, 8, 8);
        this.verticalShaft.addBox(7F, -0.5F, 7F, 2, 17, 2, 120, 0, vw, vh);
        final LODModel.Part vcross = this.verticalShaft.child("cross", 8, 8, 8);
        vcross.rotate(0, (float) (Math.PI / 4), 0);
        vcross.addBox(7F, -0.5F, 7F, 2, 17, 2, 120, 0, vw, vh);

        // Авто-LOD: оригинал -> слитые боксы -> импостёр
        LODGenerator.generate(mount);
        LODGenerator.generate(this.horizontalShaft);
        LODGenerator.generate(vmount);
        LODGenerator.generate(this.verticalShaft);
    }

    @Override
    public void render(ShaftBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        // Позиция камеры относительно центра блока — для LOD-куллинга
        final var camPos = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        final var pos = blockEntity.getBlockPos();
        final double dx = pos.getX() + 0.5 - camPos.x;
        final double dy = pos.getY() + 0.5 - camPos.y;
        final double dz = pos.getZ() + 0.5 - camPos.z;
        final double distanceSqr = dx * dx + dy * dy + dz * dz;

        // Тестовая анимация: угол от времени; позже angle придёт из MechanicalMachine
        final long time = blockEntity.getLevel() != null
                ? blockEntity.getLevel().getGameTime() : 0;
        final float angle = (time % 20000L + partialTick) * Mth.DEG_TO_RAD * 4.0F;

        final BlockState state = blockEntity.getBlockState();
        final Direction.Axis axis = state.hasProperty(BlockStateProperties.AXIS)
                ? state.getValue(BlockStateProperties.AXIS) : Direction.Axis.X;
        final boolean ceiling = state.hasProperty(ShaftBlock.CEILING)
                ? state.getValue(ShaftBlock.CEILING) : false;

        poseStack.pushPose();
        // Вращения вокруг центра блока: сдвиг к центру, повороты, сдвиг обратно
        poseStack.translate(0.5F, 0.5F, 0.5F);
        if (axis == Direction.Axis.Z) {
            // X модели -> Z (вертикальная модель не требует поворота)
            poseStack.mulPose(Axis.YP.rotationDegrees(-90));
        }
        if (ceiling) {
            // Флип на 180 вокруг мировой оси X (y -> -y):
            // станина уходит с "пола" блока на потолок
            poseStack.mulPose(Axis.XP.rotationDegrees(180));
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        if (axis == Direction.Axis.Y) {
            // Вертикальный: клетка ModelShaftV, вращение вокруг Y
            verticalShaft.rotate(0, angle, 0);
            verticalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        } else {
            horizontalShaft.rotate(angle, 0, 0);
            horizontalModel.render(poseStack, bufferSource, packedLight, packedOverlay, distanceSqr, partialTick);
        }
        poseStack.popPose();
    }

    /**
     * Дальше ванильных 96, чтобы LOD-уровни (импостёр) были видны на дистанции.
     */
    @Override
    public int getViewDistance() {
        return 256;
    }
}
