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

    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        emitQuads(quads, poseStack, bufferSource.getBuffer(renderType()), packedLight, packedOverlay);
    }

    public static void emitQuads(Quad[] quads, PoseStack poseStack, VertexConsumer consumer, int packedLight, int packedOverlay) {
        final PoseStack.Pose lastPose = poseStack.last();
        final Matrix4f poseMatrix = lastPose.pose();
        final Matrix3f normalMatrix = lastPose.normal();

        for (int quadIndex = 0; quadIndex < quads.length; quadIndex++) {
            final Quad quad = quads[quadIndex];
            final float normalSign = quad.invertNormal ? -1.0F : 1.0F;

            for (int vertexIndex = 0; vertexIndex < 4; vertexIndex++) {
                final Vertex v = quad.vertices[vertexIndex];

                final float worldX = poseMatrix.m00() * v.x + poseMatrix.m10() * v.y + poseMatrix.m20() * v.z + poseMatrix.m30();
                final float worldY = poseMatrix.m01() * v.x + poseMatrix.m11() * v.y + poseMatrix.m21() * v.z + poseMatrix.m31();
                final float worldZ = poseMatrix.m02() * v.x + poseMatrix.m12() * v.y + poseMatrix.m22() * v.z + poseMatrix.m32();

                // 2. Трансформация нормали: Matrix3f * (nx, ny, nz) с учетом инверсии
                final float nx = (normalMatrix.m00 * v.normalX + normalMatrix.m10 * v.normalY + normalMatrix.m20 * v.normalZ) * normalSign;
                final float ny = (normalMatrix.m01 * v.normalX + normalMatrix.m11 * v.normalY + normalMatrix.m21 * v.normalZ) * normalSign;
                final float nz = (normalMatrix.m02 * v.normalX + normalMatrix.m12 * v.normalY + normalMatrix.m22 * v.normalZ) * normalSign;

                // 3. Запись в цепочку ванильного формата DefaultVertexFormat.NEW_ENTITY
                consumer.addVertex(worldX, worldY, worldZ)
                        .setColor(255, 255, 255, 255)
                        .setUv(v.u, v.v)
                        .setOverlay(packedOverlay)
                        .setUv2(packedLight & 0xFFFF, (packedLight >> 16) & 0xFFFF)
                        .setNormal(nx, ny, nz);
            }
        }
    }
}
