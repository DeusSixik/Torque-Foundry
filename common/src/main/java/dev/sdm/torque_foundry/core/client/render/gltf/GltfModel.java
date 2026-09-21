package dev.sdm.torque_foundry.core.client.render.gltf;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.client.render.LODGenerator;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * glTF-модель блока: набор именованных частей (мешей из Blender) с LOD-уровнями.
 *
 * <p>Структура файлов в assets:
 * <pre>
 *   models/block/shaft_lod0.glb — LOD0: меши Corp, Val, ...
 *   models/block/shaft_lod1.glb — LOD1 (опционально)
 * </pre>
 * Имя части = имя меша в Blender. LOD-уровень выбирается рендером по дистанции.
 *
 * <p>Конверсия координат: Blender/glTF — метры, Y-up; блок — 0..16 пикселей,
 * Y-up тоже (совпадает). Масштаб: glTF-единица * {@link #unitsToPixels} =
 * пиксели модели. По умолчанию 1 м = 16 px (модель в метре = ровно блок).
 * Ось Z инвертируется (glTF +Z к зрителю, у блока -Z север) — иначе модель
 * зеркалится. Нормали инвертируются вместе с геометрией.
 */
public final class GltfModel {

    /**
     * Метры glTF -> пиксели модели (0..16 = блок). 16 = модель в 1 м.
     */
    public static final float DEFAULT_UNITS_TO_PIXELS = 16.0F;
    /**
     * Допуск совпадения длиннейшей стороны с блоком: внутри — масштаб не трогаем.
     */
    private static final float AUTO_SCALE_TOLERANCE_PX = 0.05F;
    /**
     * Минимальная длина bbox для авто-масштаба: ниже — деление бессмысленно.
     */
    private static final float MIN_BBOX_FOR_SCALE = 1e-6F;
    /**
     * Центр блока в пикселях модели (fitToBlock сводит сюда XZ, Y на пол).
     */
    private static final float BLOCK_CENTER_PX = 8.0F;
    /**
     * Порог длины нормали: ниже — вектор вырожден, нормализацию пропускаем.
     */
    private static final float MIN_NORMAL_LENGTH = 1e-9F;
    /**
     * Pivot пустой части: центр блока.
     */
    private static final float[] EMPTY_PART_PIVOT = {8, 8, 8};

    /**
     * Одна часть модели (меш Blender): LOD-уровни квадов + pivot для вращения.
     */
    public static final class Part {
        public final String name;
        /**
         * Квады по LOD: lods[0] = детальный, дальше — упрощённые.
         */
        public final List<Quad[]> lods;
        /**
         * Pivot вращения в пикселях модели (центр bounding box).
         */
        public final float pivotX;
        public final float pivotY;
        public final float pivotZ;
        /**
         * Вращение узла (радианы), мутируется рендером каждый кадр.
         */
        public float rotX;
        public float rotY;
        public float rotZ;

        Part(String name, List<Quad[]> lods, float pivotX, float pivotY, float pivotZ) {
            this.name = name;
            this.lods = lods;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.pivotZ = pivotZ;
        }

        public Quad[] selectLod(int level) {
            if (lods.isEmpty()) {
                return new Quad[0];
            }
            return lods.get(Math.min(level, lods.size() - 1));
        }
    }

    private final ResourceLocation texture;
    private final Map<String, Part> parts = new LinkedHashMap<>();

    private GltfModel(ResourceLocation texture) {
        this.texture = texture;
    }

    public ResourceLocation texture() {
        return texture;
    }

    public Part part(String name) {
        return parts.get(name);
    }

    public Iterable<Part> parts() {
        return parts.values();
    }

    /**
     * Загрузка модели: lod-файлы по порядку (lod0 — детальный).
     * Каждый файл — {@code .glb}; части с тем же именем дополняют LOD-уровни.
     * Байты (не потоки): файл распарсивается за один проход, повторные
     * проходы (авто-масштаб, имена мешей) работают с уже разобранными данными.
     *
     * @param fitToBlock центрировать bbox по X/Z на блок (8,8) и посадить
     *                   на пол (minY = 0); выключить, если модель уже отцентрирована в Blender
     * @param autoScale  подобрать масштаб: самая длинная сторона bbox
     *                   модели = 16 px. Иначе unitsToPixels как есть
     */
    public static GltfModel load(ResourceLocation texture, float unitsToPixels,
                                 boolean fitToBlock, boolean autoScale, byte[]... lodFiles) throws IOException {
        final GltfModel model = new GltfModel(texture);
        // Один парсинг на файл: сырые ноды/меши держим в памяти, квады
        // перестраиваются при смене масштаба без повторного чтения.
        final GltfParser.ParsedModel[] parsed = new GltfParser.ParsedModel[lodFiles.length];
        for (int lod = 0; lod < lodFiles.length; lod++) {
            parsed[lod] = GltfParser.parseGlb(new ByteArrayInputStream(lodFiles[lod]));
        }

        // Части строим ПО НОДАМ (не по мешам): нода несёт world-матрицу
        // (offset/поворот объекта из Blender), POSITION меша — локальные
        // координаты. Без матрицы все части рендерились бы от Origin.
        float finalScale = unitsToPixels;
        final List<List<NodeBuild>> lodNodes = new ArrayList<>();
        for (int lod = 0; lod < parsed.length; lod++) {
            lodNodes.add(buildNodeQuads(parsed[lod], finalScale));
        }

        // Авто-масштаб: длиннейшая сторона lod0 = 16 px.
        if (autoScale && !lodNodes.isEmpty() && !lodNodes.get(0).isEmpty()) {
            final float[] size = bboxSize(flattenNodes(lodNodes.get(0)));
            final float longest = Math.max(size[0], Math.max(size[1], size[2]));
            if (longest > MIN_BBOX_FOR_SCALE && Math.abs(longest - 16.0F) > AUTO_SCALE_TOLERANCE_PX) {
                finalScale = unitsToPixels * (16.0F / longest);
                lodNodes.clear();
                for (int lod = 0; lod < parsed.length; lod++) {
                    lodNodes.add(buildNodeQuads(parsed[lod], finalScale));
                }
            }
        }

        // Смещение для фита: bbox lod0 по всем нодам.
        float offX = 0;
        float offY = 0;
        float offZ = 0;
        if (fitToBlock && !lodNodes.isEmpty()) {
            final float[] min = bboxMin(flattenNodes(lodNodes.get(0)));
            final float[] max = bboxMax(flattenNodes(lodNodes.get(0)));
            offX = BLOCK_CENTER_PX - (min[0] + max[0]) * 0.5F;
            offY = -min[1];
            offZ = BLOCK_CENTER_PX - (min[2] + max[2]) * 0.5F;
        }

        // Сборка частей: имя ноды -> LOD-уровни. Совпадение имён нескольких
        // нод (инстансы одного меша) — квады дописываются в тот же уровень.
        final Map<String, List<Quad[]>> partLods = new LinkedHashMap<>();
        for (int lod = 0; lod < lodNodes.size(); lod++) {
            for (NodeBuild node : lodNodes.get(lod)) {
                final Quad[] arr = node.quads();
                if (fitToBlock) {
                    for (Quad q : arr) {
                        for (Vertex v : q.vertices) {
                            v.x += offX;
                            v.y += offY;
                            v.z += offZ;
                        }
                    }
                }
                final List<Quad[]> lods = partLods.computeIfAbsent(node.name(), k -> new ArrayList<>());
                while (lods.size() <= lod) {
                    lods.add(new Quad[0]);
                }
                final Quad[] prev = lods.get(lod);
                if (prev.length == 0) {
                    lods.set(lod, arr);
                } else {
                    // Инстансы: слить квады этой ноды с уже собранными.
                    final Quad[] merged = new Quad[prev.length + arr.length];
                    System.arraycopy(prev, 0, merged, 0, prev.length);
                    System.arraycopy(arr, 0, merged, prev.length, arr.length);
                    lods.set(lod, merged);
                }
            }
        }

        for (Map.Entry<String, List<Quad[]>> e : partLods.entrySet()) {
            // Недостающие уровни (нет LOD1/LOD2 файлов) догенерируем
            // из LOD0: MeshSimplifier режет треугольники вдвое на уровень.
            // Файлы из Blender имеют приоритет — генерация только для дыр.
            final List<Quad[]> completed = LODGenerator.completeMeshLods(e.getValue());
            final Quad[] lod0 = completed.get(0);
            final float[] pivot = computePivot(lod0);
            model.parts.put(e.getKey(), new Part(e.getKey(), completed, pivot[0], pivot[1], pivot[2]));
            // Диагностика: размеры частей после нод-матриц и фита.
            final String lodInfo = completed.size() + " lods, tris=" + trisPerLod(completed);
            TorqueFoundry.LOGGER.info("glTF node '{}': bbox {} px, {}", e.getKey(),
                    Arrays.toString(bboxSize(lod0)), lodInfo);
        }
        TorqueFoundry.LOGGER.info("glTF model: scale={} px/unit, fit={}", finalScale,
                fitToBlock ? "on" : "off");
        return model;
    }

    private static String trisPerLod(List<Quad[]> lods) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lods.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(lods.get(i).length);
        }
        return sb.toString();
    }

    /**
     * Часть + её квады одного LOD-файла.
     */
    private record NodeBuild(String name, Quad[] quads) {
    }

    /**
     * Квады всех нод с мешем: world-матрица ноды применяется к локальным
     * вершинам (позиции w=1, нормали 3x3 с нормализацией), затем наш
     * R180Y+scale в блок-пространство.
     */
    private static List<NodeBuild> buildNodeQuads(GltfParser.ParsedModel parsed, float scale) {
        final List<NodeBuild> out = new ArrayList<>();
        final List<GltfParser.RawMesh> meshes = parsed.meshes();
        for (GltfParser.RawNode node : parsed.nodes()) {
            if (node.meshIndex() < 0 || node.meshIndex() >= meshes.size()) {
                continue;
            }
            final GltfParser.RawMesh mesh = meshes.get(node.meshIndex());
            final List<Quad> quads = new ArrayList<>();
            for (GltfParser.RawPrimitive prim : mesh.primitives()) {
                quads.addAll(trianglesToQuads(transform(prim, node.worldMatrix()), scale));
            }
            final String name = !node.name().isEmpty() ? node.name() : mesh.name();
            out.add(new NodeBuild(name, quads.toArray(new Quad[0])));
        }
        return out;
    }

    /**
     * Применение world-матрицы ноды (column-major) к примитиву.
     * Позиции: p' = M · p. Нормали: n' = normalize(3x3(M) · n) —
     * при неравномерном масштабе точная нормаль-матрица = inverse transpose,
     * для блочных моделей (rigid/равномерный scale) 3x3 достаточно.
     */
    private static GltfParser.RawPrimitive transform(GltfParser.RawPrimitive prim, float[] m) {
        if (m == null) {
            return prim;
        }
        final float[] p = prim.positions();
        final float[] outPos = new float[p.length];
        for (int i = 0; i < p.length; i += 3) {
            final float x = p[i];
            final float y = p[i + 1];
            final float z = p[i + 2];
            outPos[i] = m[0] * x + m[4] * y + m[8] * z + m[12];
            outPos[i + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
            outPos[i + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
        }
        float[] outNorm = null;
        if (prim.normals() != null) {
            final float[] n = prim.normals();
            outNorm = new float[n.length];
            for (int i = 0; i < n.length; i += 3) {
                final float x = n[i];
                final float y = n[i + 1];
                final float z = n[i + 2];
                float nx = m[0] * x + m[4] * y + m[8] * z;
                float ny = m[1] * x + m[5] * y + m[9] * z;
                float nz = m[2] * x + m[6] * y + m[10] * z;
                final float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (len > MIN_NORMAL_LENGTH) {
                    nx /= len;
                    ny /= len;
                    nz /= len;
                }
                outNorm[i] = nx;
                outNorm[i + 1] = ny;
                outNorm[i + 2] = nz;
            }
        }
        return new GltfParser.RawPrimitive(outPos, outNorm, prim.uvs());
    }

    private static Quad[] flattenNodes(List<NodeBuild> nodes) {
        int total = 0;
        for (NodeBuild node : nodes) {
            total += node.quads().length;
        }
        final Quad[] out = new Quad[total];
        int i = 0;
        for (NodeBuild node : nodes) {
            final Quad[] arr = node.quads();
            System.arraycopy(arr, 0, out, i, arr.length);
            i += arr.length;
        }
        return out;
    }

    private static float[] bboxMin(Quad[] quads) {
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        for (Quad q : quads) {
            for (Vertex v : q.vertices) {
                if (v.x < minX) {
                    minX = v.x;
                }
                if (v.y < minY) {
                    minY = v.y;
                }
                if (v.z < minZ) {
                    minZ = v.z;
                }
            }
        }
        return new float[]{minX, minY, minZ};
    }

    private static float[] bboxMax(Quad[] quads) {
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Quad q : quads) {
            for (Vertex v : q.vertices) {
                if (v.x > maxX) {
                    maxX = v.x;
                }
                if (v.y > maxY) {
                    maxY = v.y;
                }
                if (v.z > maxZ) {
                    maxZ = v.z;
                }
            }
        }
        return new float[]{maxX, maxY, maxZ};
    }

    private static float[] bboxSize(Quad[] quads) {
        final float[] min = bboxMin(quads);
        final float[] max = bboxMax(quads);
        return new float[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    /**
     * Центр bounding box квадов — pivot вращения части.
     */
    private static float[] computePivot(Quad[] quads) {
        if (quads.length == 0) {
            return EMPTY_PART_PIVOT.clone();
        }
        final float[] min = bboxMin(quads);
        final float[] max = bboxMax(quads);
        return new float[]{(min[0] + max[0]) * 0.5F, (min[1] + max[1]) * 0.5F,
                (min[2] + max[2]) * 0.5F};
    }

    /**
     * Триангуляция: каждые 3 вершины = треугольник -> Quad с вырожденной
     * 4-й вершиной (d = c). Порядок CCW сохраняется, нормали из glTF
     * (или вычисленные, если их нет).
     */
    private static List<Quad> trianglesToQuads(GltfParser.RawPrimitive prim, float scale) {
        final int triCount = prim.positions().length / 9;
        final List<Quad> out = new ArrayList<>(triCount);
        for (int t = 0; t < triCount; t++) {
            final Vertex a = vertex(prim, t * 3, scale);
            final Vertex b = vertex(prim, t * 3 + 1, scale);
            final Vertex c = vertex(prim, t * 3 + 2, scale);
            if (prim.normals() == null) {
                computeFlatNormal(a, b, c);
            }
            // d дублирует c: формат Quad всегда 4 вершины.
            final Vertex d = copyVertex(c);
            final Quad q = new Quad();
            q.vertices = new Vertex[]{a, b, c, d};
            q.invertNormal = false;
            out.add(q);
        }
        return out;
    }

    private static Vertex copyVertex(Vertex src) {
        final Vertex v = new Vertex();
        v.x = src.x;
        v.y = src.y;
        v.z = src.z;
        v.normalX = src.normalX;
        v.normalY = src.normalY;
        v.normalZ = src.normalZ;
        v.u = src.u;
        v.v = src.v;
        return v;
    }

    private static Vertex vertex(GltfParser.RawPrimitive prim, int index, float scale) {
        final Vertex v = new Vertex();
        // glTF: метры, Y-up, +Z к зрителю. Блок: пиксели, Y-up, -Z север.
        // Поворот на 180 вокруг Y (x=-x, z=-z): строка "z=-z" БЕЗ x=-x была
        // бы отражением — winding треугольников инвертируется и cull
        // отсекает наружные грани (модель "вывернута").
        v.x = -prim.positions()[index * 3] * scale;
        v.y = prim.positions()[index * 3 + 1] * scale;
        v.z = -prim.positions()[index * 3 + 2] * scale;
        if (prim.normals() != null) {
            v.normalX = -prim.normals()[index * 3];
            v.normalY = prim.normals()[index * 3 + 1];
            v.normalZ = -prim.normals()[index * 3 + 2];
        }
        if (prim.uvs() != null) {
            v.u = prim.uvs()[index * 2];
            v.v = prim.uvs()[index * 2 + 1];
        } else {
            v.u = 0;
            v.v = 0;
        }
        return v;
    }

    /**
     * Плоская нормаль треугольника (когда в glTF нормалей нет).
     */
    private static void computeFlatNormal(Vertex a, Vertex b, Vertex c) {
        final float ux = b.x - a.x;
        final float uy = b.y - a.y;
        final float uz = b.z - a.z;
        final float vx = c.x - a.x;
        final float vy = c.y - a.y;
        final float vz = c.z - a.z;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        final float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < MIN_NORMAL_LENGTH) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        a.normalX = nx;
        a.normalY = ny;
        a.normalZ = nz;
        b.normalX = nx;
        b.normalY = ny;
        b.normalZ = nz;
        c.normalX = nx;
        c.normalY = ny;
        c.normalZ = nz;
    }
}
