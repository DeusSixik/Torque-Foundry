package dev.sdm.torque_foundry.core.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.sdm.torque_foundry.api.collections.FastutilLruCache;
import dev.sdm.torque_foundry.api.render.DefaultModModels;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Модель из боксов с иерархией узлов (pivot-вращения), LOD-куллингом по объёму
 * и анимацией узлов (вращение/сдвиг), в духе RotaryCraft LODModelPart.
 *
 * <p>Каждый {@link Part} — узел с собственным pivot'ом и списком боксов;
 * дети наследуют трансформацию родителя (PoseStack-иерархия).
 * Куллинг: чем меньше объём геометрии узла, тем ближе надо стоять.
 */
public class LODModel {

    public static LODModel createDefault() {
        return new LODModel(DefaultModModels.DEFAULT_TEXTURE);
    }

    /**
     * Кэш RenderType по текстуре: создание типа каждый кадр — аллокация в кадре.
     */
    private static final FastutilLruCache<ResourceLocation, RenderType> RENDER_CACHE =
            new FastutilLruCache<>(128);

    protected final ResourceLocation texture;
    protected final Part root;

    public LODModel(ResourceLocation texture) {
        this.texture = texture;
        this.root = new Part("root", 0, 0, 0);
    }

    public ResourceLocation texture() {
        return texture;
    }

    public Part root() {
        return root;
    }

    /**
     * Создаёт узел верхнего уровня (ребёнок корня).
     */
    public Part part(String name, float pivotX, float pivotY, float pivotZ) {
        return root.child(name, pivotX, pivotY, pivotZ);
    }

    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight,
                       int packedOverlay, double distanceSqr, float partialTick) {
        poseStack.pushPose();
        // Пиксели модели -> блоки (координаты модели 0..16 = один блок).
        poseStack.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        renderPart(root, poseStack, bufferSource, texture, packedLight, packedOverlay, distanceSqr,
                partialTick);
        poseStack.popPose();
    }

    private void renderPart(Part part, PoseStack poseStack, MultiBufferSource bufferSource,
                            ResourceLocation texture, int packedLight, int packedOverlay, double distanceSqr,
                            float partialTick) {
        // Ручное скрытие узла (пустой корпус без вставки, снятая деталь).
        // Дефолт true — существующие рендеры не меняются.
        if (!part.visible) {
            return;
        }
        if (!part.shouldRender(distanceSqr)) {
            return;
        }

        poseStack.pushPose();
        part.applyTransform(poseStack, partialTick);

        // Текстура узла: override (материал из PartTextures) или модели.
        final ResourceLocation partTexture =
                part.textureOverride != null ? part.textureOverride : texture;
        final RenderType renderType =
                RENDER_CACHE.getOrCreate(partTexture, RenderType::entityCutout);

        final LODBox[] partBoxes = part.selectBoxes(distanceSqr);
        if (partBoxes.length > 0) {
            final VertexConsumer consumer = bufferSource.getBuffer(renderType);
            for (int i = 0; i < partBoxes.length; i++) {
                partBoxes[i].emit(poseStack, consumer, packedLight, packedOverlay);
            }
        }

        // Произвольные меши (glTF): квады напрямую, без LODBox-обёртки.
        // Координаты уже в пикселях модели — скейл 1/16 общий для узла.
        final Quad[] mesh = part.selectMesh(distanceSqr);
        if (mesh != null && mesh.length > 0) {
            final VertexConsumer consumer = bufferSource.getBuffer(renderType);
            Model.emitQuads(mesh, poseStack, consumer, packedLight, packedOverlay);
        }

        for (int i = 0; i < part.children.size(); i++) {
            renderPart(part.children.get(i), poseStack, bufferSource, texture, packedLight,
                    packedOverlay, distanceSqr, partialTick);
        }

        poseStack.popPose();
    }

    /**
     * Узел модели: pivot, поворот, анимация, боксы, дети.
     */
    public static final class Part {

        /**
         * Множитель шага порога между соседними LOD-уровнями меша (x4 sqr).
         */
        private static final double MESH_LOD_STEP = 4.0;
        /**
         * Дистанция LOD0 меша: ~24 блока (576 sqr), дальше — LOD1, ещё дальше — LOD2.
         */
        private static final double MESH_LOD_BASE_SQR = 576.0;

        public final String name;
        public final float pivotX;
        public final float pivotY;
        public final float pivotZ;

        /**
         * Сдвиг узла относительно pivot (в пикселях модели, 1/16 блока).
         */
        public float offsetX;
        public float offsetY;
        public float offsetZ;

        /**
         * Поворот узла вокруг pivot (радианы).
         */
        public float rotX;
        public float rotY;
        public float rotZ;

        /**
         * Скаляр видимости: {@code >1} — видно дальше, {@code <1} — ближе. 1 по умолчанию.
         */
        public double renderDistanceScalar = 1.0;

        /**
         * Текстура узла (null — текстура модели). Выставляется рендером
         * по материалу машины (PartTextures): один блок — разные материалы
         * крафта, разные текстуры.
         */
        public ResourceLocation textureOverride;

        /**
         * Ручная видимость узла (пустой слот корпуса, снятая деталь).
         * Дефолт true — скрытие выставляется рендером каждый кадр.
         */
        public boolean visible = true;

        private final List<LODBox> boxes = new ObjectArrayList<>();
        private final List<Part> children = new ObjectArrayList<>();

        /**
         * Произвольные меши (glTF): готовые квады, рендерятся как есть
         * (без LOD-слияния — уровни задаются файлами LOD0/LOD1).
         */
        private final List<Quad[]> meshes = new ObjectArrayList<>();

        /**
         * Индекс активного меша (LOD-уровень glTF).
         */
        private int meshLod;

        /**
         * Уровни детализации: от детального к простому.
         * Уровень 0 — оригинальные боксы (виден вплотную),
         * последующие — упрощённые (видны дальше, порог выше).
         * Заполняется через {@link LODGenerator#generate}.
         */
        private final List<LODLevel> lodLevels = new ObjectArrayList<>();

        /**
         * Кэш плоского массива боксов: selectBoxes вне уровней не аллоцирует.
         */
        private LODBox[] cachedBoxesArray;
        private double cachedDistanceSqr = -1;

        record LODLevel(LODBox[] boxes, double thresholdSqr) {
        }

        public Part(String name, float pivotX, float pivotY, float pivotZ) {
            this.name = name;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
        }

        public Part child(String name, float px, float py, float pz) {
            final Part child = new Part(name, px, py, pz);
            children.add(child);
            return child;
        }

        public Part addBox(float x, float y, float z, float w, float h, float d, int u, int v,
                           int texW, int texH) {
            boxes.add(new LODBox(x, y, z, w, h, d, u, v, texW, texH));
            cachedDistanceSqr = -1;
            cachedBoxesArray = null;
            lodLevels.clear();
            return this;
        }

        /**
         * Произвольный меш (например, из glTF): готовые квады вместо боксов.
         * LOD-куллинг по объёму bounding box квадов; LODGenerator.generate
         * для таких узлов не применяется (уровни — из файлов LOD0/LOD1).
         */
        public Part mesh(Quad[] quads) {
            meshes.add(quads);
            cachedDistanceSqr = -1;
            return this;
        }

        List<LODBox> boxes() {
            return boxes;
        }

        void setLODLevels(List<LODLevel> levels) {
            this.lodLevels.clear();
            this.lodLevels.addAll(levels);
        }

        /**
         * Максимальный порог видимости (по последнему, самому грубому уровню).
         */
        double effectiveThresholdSqr() {
            if (!lodLevels.isEmpty()) {
                return lodLevels.get(lodLevels.size() - 1).thresholdSqr() * renderDistanceScalar;
            }
            if (cachedDistanceSqr < 0) {
                cachedDistanceSqr = calculateDistanceSqr();
            }
            return cachedDistanceSqr * renderDistanceScalar;
        }

        /**
         * Выбор уровня детализации: идём от детального к грубому и берём
         * первый, чей порог покрывает дистанцию. Вплотную всегда LOD0,
         * дальше — упрощённые (порог каждого уровня в 4 раза выше).
         */
        LODBox[] selectBoxes(double distanceSqr) {
            for (int i = 0; i < lodLevels.size(); i++) {
                final LODLevel level = lodLevels.get(i);
                if (distanceSqr <= level.thresholdSqr() * renderDistanceScalar) {
                    return level.boxes();
                }
            }
            LODBox[] flat = cachedBoxesArray;
            if (flat == null) {
                flat = boxes.toArray(new LODBox[0]);
                cachedBoxesArray = flat;
            }
            return flat;
        }

        /**
         * Выбор glTF-меша по дистанции: meshes[0] — детальный (LOD0),
         * дальше — по порогам x4 на уровень, как у боксов. Без мешей — null.
         * Куллингом занимается shouldRender (он же решает полное скрытие).
         */
        Quad[] selectMesh(double distanceSqr) {
            if (meshes.isEmpty()) {
                return null;
            }
            if (meshes.size() == 1) {
                return meshes.get(0);
            }
            // Инкрементальный порог вместо Math.pow на уровень: та же геометрия x4,
            // но без трансцендентной функции в кадре.
            double threshold = MESH_LOD_BASE_SQR * renderDistanceScalar;
            for (int i = 0; i < meshes.size(); i++) {
                if (distanceSqr <= threshold) {
                    return meshes.get(i);
                }
                threshold *= MESH_LOD_STEP;
            }
            return meshes.get(meshes.size() - 1);
        }

        /**
         * Активный LOD-индекс glTF-меша (для отладки/оверлея).
         */
        public void setMeshLod(int lod) {
            this.meshLod = Math.max(0, lod);
        }

        /**
         * Добавить LOD-уровень glTF-меша (порядок: LOD0, LOD1, ...).
         */
        public Part addMeshLod(Quad[] quads) {
            meshes.add(quads);
            cachedDistanceSqr = -1;
            return this;
        }

        public Part setRenderDistanceScalar(double scalar) {
            this.renderDistanceScalar = scalar;
            this.cachedDistanceSqr = -1;
            return this;
        }

        /**
         * Анимация: поворот узла вокруг pivot (радианы).
         */
        public Part rotate(float rotX, float rotY, float rotZ) {
            this.rotX = rotX;
            this.rotY = rotY;
            this.rotZ = rotZ;
            return this;
        }

        /**
         * Анимация: сдвиг узла (в пикселях модели, 1/16 блока).
         */
        public Part offset(float x, float y, float z) {
            this.offsetX = x;
            this.offsetY = y;
            this.offsetZ = z;
            return this;
        }

        boolean shouldRender(double distanceSqr) {
            // Узел-контейнер без геометрии (например, корень) не куллится сам —
            // видимость определяют его дети. Иначе пустой узел получает
            // порог 96 (объём 0) и обрубает всё поддерево на ~10 блоках.
            if (boxes.isEmpty() && meshes.isEmpty()) {
                return true;
            }
            return effectiveThresholdSqr() >= distanceSqr;
        }

        private double calculateDistanceSqr() {
            double maxVolume = meshVolume();
            for (int i = 0; i < boxes.size(); i++) {
                final double volume = boxes.get(i).volume();
                if (volume > maxVolume) {
                    maxVolume = volume;
                }
            }
            return distanceForVolume(maxVolume);
        }

        /**
         * Объём bounding box glTF-мешей (для LOD-куллинга).
         */
        private double meshVolume() {
            if (meshes.isEmpty()) {
                return 0;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            boolean any = false;
            for (int m = 0; m < meshes.size(); m++) {
                final Quad[] quads = meshes.get(m);
                if (quads == null) {
                    continue;
                }
                for (int q = 0; q < quads.length; q++) {
                    final Quad quad = quads[q];
                    if (quad == null || quad.vertices == null) {
                        continue;
                    }
                    for (int v = 0; v < quad.vertices.length; v++) {
                        final Vertex vtx = quad.vertices[v];
                        if (vtx == null) {
                            continue;
                        }
                        any = true;
                        if (vtx.x < minX) {
                            minX = vtx.x;
                        }
                        if (vtx.y < minY) {
                            minY = vtx.y;
                        }
                        if (vtx.z < minZ) {
                            minZ = vtx.z;
                        }
                        if (vtx.x > maxX) {
                            maxX = vtx.x;
                        }
                        if (vtx.y > maxY) {
                            maxY = vtx.y;
                        }
                        if (vtx.z > maxZ) {
                            maxZ = vtx.z;
                        }
                    }
                }
            }
            if (!any) {
                return 0;
            }
            return (double) (maxX - minX) * (maxY - minY) * (maxZ - minZ);
        }

        static double distanceForVolume(double volume) {
            if (volume > 1024) {
                return 16384;
            }
            if (volume > 512) {
                return 4096;
            }
            if (volume > 128) {
                return 2048;
            }
            if (volume > 32) {
                return 1024;
            }
            if (volume > 8) {
                return 256;
            }
            if (volume > 4) {
                return 128;
            }
            return 96;
        }

        void applyTransform(PoseStack poseStack, float partialTick) {
            // Ванильная схема: translate(pivot) -> rotate -> translate(-pivot),
            // боксы заданы в абсолютных координатах модели (0..16 пикселей).
            // Скейл 1/16 уже применён на уровне модели.
            poseStack.translate(pivotX + offsetX, pivotY + offsetY, pivotZ + offsetZ);
            if (rotZ != 0) {
                poseStack.mulPose(Axis.ZP.rotation(rotZ));
            }
            if (rotY != 0) {
                poseStack.mulPose(Axis.YP.rotation(rotY));
            }
            if (rotX != 0) {
                poseStack.mulPose(Axis.XP.rotation(rotX));
            }
            poseStack.translate(-pivotX, -pivotY, -pivotZ);
        }
    }

    /**
     * Бокс модели: 6 граней, UV по таблице текстуры (ванильный layout).
     */
    public static final class LODBox {
        final float x;
        final float y;
        final float z;
        final float w;
        final float h;
        final float d;
        final int u;
        final int v;
        final int texW;
        final int texH;

        public LODBox(float x, float y, float z, float w, float h, float d, int u, int v, int texW,
                      int texH) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
            this.h = h;
            this.d = d;
            this.u = u;
            this.v = v;
            this.texW = texW;
            this.texH = texH;
        }

        public double volume() {
            return (double) w * h * d;
        }

        /**
         * Объединяющий бокс (bounding box двух), UV берётся от большего по объёму.
         */
        public static LODBox union(LODBox a, LODBox b) {
            final float x = Math.min(a.x, b.x);
            final float y = Math.min(a.y, b.y);
            final float z = Math.min(a.z, b.z);
            final float x2 = Math.max(a.x + a.w, b.x + b.w);
            final float y2 = Math.max(a.y + a.h, b.y + b.h);
            final float z2 = Math.max(a.z + a.d, b.z + b.d);
            final LODBox src = a.volume() >= b.volume() ? a : b;
            return new LODBox(x, y, z, x2 - x, y2 - y, z2 - z, src.u, src.v, src.texW, src.texH);
        }

        public void emit(PoseStack poseStack, VertexConsumer consumer, int packedLight,
                         int packedOverlay) {
            Model.emitQuads(quads(), poseStack, consumer, packedLight, packedOverlay);
        }

        /**
         * Квады бокса. LODBox иммутабелен, поэтому строятся один раз
         * при первом рендере — дальше переиспользуются те же Vertex/Quad.
         */
        public Quad[] quads() {
            Quad[] result = cachedQuads;
            if (result == null) {
                result = buildQuads();
                cachedQuads = result;
            }
            return result;
        }

        private transient Quad[] cachedQuads;

        /**
         * 6 граней бокса [x..x+w, y..y+h, z..z+d] (в пикселях).
         * Порядок вершин — CCW снаружи (нормаль по cross-product наружу),
         * UV по ванильной раскладке box UV.
         */
        private Quad[] buildQuads() {
            final float x1 = x;
            final float y1 = y;
            final float z1 = z;
            final float x2 = x + w;
            final float y2 = y + h;
            final float z2 = z + d;

            // Ванильная раскладка UV бокса (нормализованная).
            final float s = 1.0F / texW;
            final float t = 1.0F / texH;
            // down: (u+d, v) w x d.
            final float uD0 = (u + d) * s;
            final float vD0 = v * t;
            final float uD1 = (u + d + w) * s;
            final float vD1 = (v + d) * t;
            // up: (u+d+w, v) w x d.
            final float uU0 = (u + d + w) * s;
            final float vU0 = v * t;
            final float uU1 = (u + d + w + w) * s;
            final float vU1 = (v + d) * t;
            // west: (u, v+d) d x h.
            final float uW0 = u * s;
            final float vW0 = (v + d) * t;
            final float uW1 = (u + d) * s;
            final float vW1 = (v + d + h) * t;
            // north: (u+d, v+d) w x h.
            final float uN0 = (u + d) * s;
            final float vN0 = (v + d) * t;
            final float uN1 = (u + d + w) * s;
            final float vN1 = (v + d + h) * t;
            // east: (u+d+w, v+d) d x h.
            final float uE0 = (u + d + w) * s;
            final float vE0 = (v + d) * t;
            final float uE1 = (u + d + w + d) * s;
            final float vE1 = (v + d + h) * t;
            // south: (u+d+w+d, v+d) w x h.
            final float uS0 = (u + d + w + d) * s;
            final float vS0 = (v + d) * t;
            final float uS1 = (u + d + w + d + w) * s;
            final float vS1 = (v + d + h) * t;

            final Quad[] quads = new Quad[6];

            // Down (-Y): нормаль (0,-1,0).
            quads[0] = quad(vert(x1, y1, z1, 0, -1, 0, uD0, vD1),
                    vert(x2, y1, z1, 0, -1, 0, uD1, vD1), vert(x2, y1, z2, 0, -1, 0, uD1, vD0),
                    vert(x1, y1, z2, 0, -1, 0, uD0, vD0));

            // Up (+Y): нормаль (0,+1,0).
            quads[1] = quad(vert(x1, y2, z2, 0, 1, 0, uU0, vD1),
                    vert(x2, y2, z2, 0, 1, 0, uU1, vD1), vert(x2, y2, z1, 0, 1, 0, uU1, vD0),
                    vert(x1, y2, z1, 0, 1, 0, uU0, vD0));

            // West (-X): нормаль (-1,0,0).
            quads[2] = quad(vert(x1, y1, z2, -1, 0, 0, uW0, vW1),
                    vert(x1, y2, z2, -1, 0, 0, uW0, vW0), vert(x1, y2, z1, -1, 0, 0, uW1, vW0),
                    vert(x1, y1, z1, -1, 0, 0, uW1, vW1));

            // North (-Z): нормаль (0,0,-1).
            quads[3] = quad(vert(x1, y1, z1, 0, 0, -1, uN0, vN1),
                    vert(x1, y2, z1, 0, 0, -1, uN0, vN0), vert(x2, y2, z1, 0, 0, -1, uN1, vN0),
                    vert(x2, y1, z1, 0, 0, -1, uN1, vN1));

            // East (+X): нормаль (+1,0,0).
            quads[4] = quad(vert(x2, y1, z1, 1, 0, 0, uE0, vE1),
                    vert(x2, y2, z1, 1, 0, 0, uE0, vE0), vert(x2, y2, z2, 1, 0, 0, uE1, vE0),
                    vert(x2, y1, z2, 1, 0, 0, uE1, vE1));

            // South (+Z): нормаль (0,0,+1).
            quads[5] = quad(vert(x2, y1, z2, 0, 0, 1, uS0, vS1),
                    vert(x2, y2, z2, 0, 0, 1, uS0, vS0), vert(x1, y2, z2, 0, 0, 1, uS1, vS0),
                    vert(x1, y1, z2, 0, 0, 1, uS1, vS1));

            return quads;
        }

        private static Vertex vert(float x, float y, float z, float nx, float ny, float nz, float u,
                                   float v) {
            final Vertex vtx = new Vertex();
            vtx.x = x;
            vtx.y = y;
            vtx.z = z;
            vtx.normalX = nx;
            vtx.normalY = ny;
            vtx.normalZ = nz;
            vtx.u = u;
            vtx.v = v;
            return vtx;
        }

        private static Quad quad(Vertex a, Vertex b, Vertex c, Vertex d) {
            final Quad quad = new Quad();
            quad.vertices = new Vertex[]{a, b, c, d};
            quad.invertNormal = false;
            return quad;
        }
    }
}
