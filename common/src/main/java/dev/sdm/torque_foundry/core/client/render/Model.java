package dev.sdm.torque_foundry.core.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Испускание квадов в ванильный буфер (формат NEW_ENTITY).
 *
 * <p>Hot path рендера: ноль аллокаций, элементы матриц и свет кэшируются
 * один раз на вызов вместо чтения на каждую вершину.
 */
public class Model {

    protected Quad[] quads;
    protected final ResourceLocation texture;

    public Model(ResourceLocation texture, Quad[] quads) {
        this.texture = texture;
        this.quads = quads;
    }

    public RenderType renderType() {
        return RenderType.entityCutout(texture);
    }

    public void render(
            PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        emitQuads(quads, poseStack, bufferSource.getBuffer(renderType()), packedLight, packedOverlay);
    }

    public static void emitQuads(Quad[] quads, PoseStack poseStack, VertexConsumer consumer,
                                 int packedLight, int packedOverlay) {
        final PoseStack.Pose lastPose = poseStack.last();
        final Matrix4f poseMatrix = lastPose.pose();
        final Matrix3f normalMatrix = lastPose.normal();

        // Элементы матриц — в локалы: вместо ~20 field/method-reads на вершину
        // читаем 21 поле один раз на вызов.
        final float m00 = poseMatrix.m00();
        final float m10 = poseMatrix.m10();
        final float m20 = poseMatrix.m20();
        final float m30 = poseMatrix.m30();
        final float m01 = poseMatrix.m01();
        final float m11 = poseMatrix.m11();
        final float m21 = poseMatrix.m21();
        final float m31 = poseMatrix.m31();
        final float m02 = poseMatrix.m02();
        final float m12 = poseMatrix.m12();
        final float m22 = poseMatrix.m22();
        final float m32 = poseMatrix.m32();
        final float n00 = normalMatrix.m00;
        final float n10 = normalMatrix.m10;
        final float n20 = normalMatrix.m20;
        final float n01 = normalMatrix.m01;
        final float n11 = normalMatrix.m11;
        final float n21 = normalMatrix.m21;
        final float n02 = normalMatrix.m02;
        final float n12 = normalMatrix.m12;
        final float n22 = normalMatrix.m22;
        final int lightU = packedLight & 0xFFFF;
        final int lightV = (packedLight >> 16) & 0xFFFF;

        for (int quadIndex = 0; quadIndex < quads.length; quadIndex++) {
            final Quad quad = quads[quadIndex];
            if (quad == null || quad.vertices == null) {
                continue;
            }
            final float normalSign = quad.invertNormal ? -1.0F : 1.0F;

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                final Vertex v = quad.vertices[vertexIndex];
                if (v == null) {
                    continue;
                }

                // 1. Трансформация позиции: Matrix4f * (x, y, z, 1).
                final float worldX = m00 * v.x + m10 * v.y + m20 * v.z + m30;
                final float worldY = m01 * v.x + m11 * v.y + m21 * v.z + m31;
                final float worldZ = m02 * v.x + m12 * v.y + m22 * v.z + m32;

                // 2. Трансформация нормали: Matrix3f * (nx, ny, nz) с учетом инверсии.
                final float nx = (n00 * v.normalX + n10 * v.normalY + n20 * v.normalZ) * normalSign;
                final float ny = (n01 * v.normalX + n11 * v.normalY + n21 * v.normalZ) * normalSign;
                final float nz = (n02 * v.normalX + n12 * v.normalY + n22 * v.normalZ) * normalSign;

                // 3. Запись в цепочку ванильного формата DefaultVertexFormat.NEW_ENTITY.
                consumer.addVertex(worldX, worldY, worldZ)
                        .setColor(255, 255, 255, 255)
                        .setUv(v.u, v.v)
                        .setOverlay(packedOverlay)
                        .setUv2(lightU, lightV)
                        .setNormal(nx, ny, nz);
            }
        }
    }
}
