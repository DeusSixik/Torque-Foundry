package dev.sdm.torque_foundry.core.client.render.gltf;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * glTF-РјРѕРґРµР»СЊ Р±Р»РѕРєР°: РЅР°Р±РѕСЂ РёРјРµРЅРѕРІР°РЅРЅС‹С… С‡Р°СЃС‚РµР№ (РјРµС€РµР№ РёР· Blender) СЃ LOD-СѓСЂРѕРІРЅСЏРјРё.
 *
 * <p>РЎС‚СЂСѓРєС‚СѓСЂР° С„Р°Р№Р»РѕРІ РІ assets:
 * <pre>
 *   models/block/shaft_lod0.glb  вЂ” LOD0: РјРµС€Рё Corp, Val, ...
 *   models/block/shaft_lod1.glb  вЂ” LOD1 (РѕРїС†РёРѕРЅР°Р»СЊРЅРѕ)
 * </pre>
 * РРјСЏ С‡Р°СЃС‚Рё = РёРјСЏ РјРµС€Р° РІ Blender. LOD-СѓСЂРѕРІРµРЅСЊ РІС‹Р±РёСЂР°РµС‚СЃСЏ СЂРµРЅРґРµСЂРѕРј РїРѕ РґРёСЃС‚Р°РЅС†РёРё.
 *
 * <p>РљРѕРЅРІРµСЂСЃРёСЏ РєРѕРѕСЂРґРёРЅР°С‚: Blender/glTF вЂ” РјРµС‚СЂС‹, Y-up; Р±Р»РѕРє вЂ” 0..16 РїРёРєСЃРµР»РµР№,
 * Y-up С‚РѕР¶Рµ (СЃРѕРІРїР°РґР°РµС‚). РњР°СЃС€С‚Р°Р±: glTF-РµРґРёРЅРёС†Р° * {@link #unitsToPixels} =
 * РїРёРєСЃРµР»Рё РјРѕРґРµР»Рё. РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ 1 Рј = 16 px (РјРѕРґРµР»СЊ РІ РјРµС‚СЂРµ = СЂРѕРІРЅРѕ Р±Р»РѕРє).
 * РћСЃСЊ Z РёРЅРІРµСЂС‚РёСЂСѓРµС‚СЃСЏ (glTF +Z Рє Р·СЂРёС‚РµР»СЋ, Сѓ Р±Р»РѕРєР° -Z СЃРµРІРµСЂ) вЂ” РёРЅР°С‡Рµ РјРѕРґРµР»СЊ
 * Р·РµСЂРєР°Р»РёС‚СЃСЏ. РќРѕСЂРјР°Р»Рё РёРЅРІРµСЂС‚РёСЂСѓСЋС‚СЃСЏ РІРјРµСЃС‚Рµ СЃ РіРµРѕРјРµС‚СЂРёРµР№.
 */
public final class GltfModel {

    /**
     * РњРµС‚СЂС‹ glTF -> РїРёРєСЃРµР»Рё РјРѕРґРµР»Рё (0..16 = Р±Р»РѕРє). 16 = РјРѕРґРµР»СЊ РІ 1 Рј.
     */
    public static final float DEFAULT_UNITS_TO_PIXELS = 16.0F;

    /**
     * РћРґРЅР° С‡Р°СЃС‚СЊ РјРѕРґРµР»Рё (РјРµС€ Blender): LOD-СѓСЂРѕРІРЅРё РєРІР°РґРѕРІ + pivot РґР»СЏ РІСЂР°С‰РµРЅРёСЏ.
     */
    public static final class Part {
        public final String name;
        /** РљРІР°РґС‹ РїРѕ LOD: lods[0] = РґРµС‚Р°Р»СЊРЅС‹Р№, РґР°Р»СЊС€Рµ вЂ” СѓРїСЂРѕС‰С‘РЅРЅС‹Рµ. */
        public final List<Quad[]> lods;
        /** Pivot РІСЂР°С‰РµРЅРёСЏ РІ РїРёРєСЃРµР»СЏС… РјРѕРґРµР»Рё (С†РµРЅС‚СЂ bounding box). */
        public final float pivotX;
        public final float pivotY;
        public final float pivotZ;
        /** Р’СЂР°С‰РµРЅРёРµ СѓР·Р»Р° (СЂР°РґРёР°РЅС‹), РјСѓС‚РёСЂСѓРµС‚СЃСЏ СЂРµРЅРґРµСЂРѕРј РєР°Р¶РґС‹Р№ РєР°РґСЂ. */
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
     * Р—Р°РіСЂСѓР·РєР° РјРѕРґРµР»Рё: lod-С„Р°Р№Р»С‹ РїРѕ РїРѕСЂСЏРґРєСѓ (lod0 вЂ” РґРµС‚Р°Р»СЊРЅС‹Р№).
     * РљР°Р¶РґС‹Р№ С„Р°Р№Р» вЂ” {@code .glb}; С‡Р°СЃС‚Рё СЃ С‚РµРј Р¶Рµ РёРјРµРЅРµРј РґРѕРїРѕР»РЅСЏСЋС‚ LOD-СѓСЂРѕРІРЅРё.
     * Р‘Р°Р№С‚С‹ (РЅРµ РїРѕС‚РѕРєРё): С„Р°Р№Р» СЂР°СЃРїР°СЂСЃРёРІР°РµС‚СЃСЏ Р·Р° РѕРґРёРЅ РїСЂРѕС…РѕРґ, РїРѕРІС‚РѕСЂРЅС‹Рµ
     * РїСЂРѕС…РѕРґС‹ (Р°РІС‚Рѕ-РјР°СЃС€С‚Р°Р±, РёРјРµРЅР° РјРµС€РµР№) СЂР°Р±РѕС‚Р°СЋС‚ СЃ СѓР¶Рµ СЂР°Р·РѕР±СЂР°РЅРЅС‹РјРё РґР°РЅРЅС‹РјРё.
     *
     * @param fitToBlock     С†РµРЅС‚СЂРёСЂРѕРІР°С‚СЊ bbox РїРѕ X/Z РЅР° Р±Р»РѕРє (8,8) Рё РїРѕСЃР°РґРёС‚СЊ
     *                       РЅР° РїРѕР» (minY = 0); РІС‹РєР»СЋС‡РёС‚СЊ, РµСЃР»Рё РјРѕРґРµР»СЊ СѓР¶Рµ
     *                       РѕС‚С†РµРЅС‚СЂРёСЂРѕРІР°РЅР° РІ Blender
     * @param autoScale      РїРѕРґРѕР±СЂР°С‚СЊ РјР°СЃС€С‚Р°Р±: СЃР°РјР°СЏ РґР»РёРЅРЅР°СЏ СЃС‚РѕСЂРѕРЅР° bbox
     *                       РјРѕРґРµР»Рё = 16 px. РРЅР°С‡Рµ unitsToPixels РєР°Рє РµСЃС‚СЊ
     */
    public static GltfModel load(ResourceLocation texture, float unitsToPixels,
                                 boolean fitToBlock, boolean autoScale,
                                 byte[]... lodFiles) throws IOException {
        final GltfModel model = new GltfModel(texture);
        // Один парсинг на файл: сырые ноды/меши держим в памяти, квады
        // перестраиваются при смене масштаба без повторного чтения.
        final GltfParser.ParsedModel[] parsed = new GltfParser.ParsedModel[lodFiles.length];
        for (int lod = 0; lod < lodFiles.length; lod++) {
            parsed[lod] = GltfParser.parseGlb(
                    new java.io.ByteArrayInputStream(lodFiles[lod]));
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
            if (longest > 1e-6F && Math.abs(longest - 16.0F) > 0.05F) {
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
            offX = 8.0F - (min[0] + max[0]) * 0.5F;
            offY = -min[1];
            offZ = 8.0F - (min[2] + max[2]) * 0.5F;
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
                final List<Quad[]> lods =
                        partLods.computeIfAbsent(node.name(), k -> new ArrayList<>());
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
            final Quad[] lod0 = e.getValue().get(0);
            final float[] pivot = computePivot(lod0);
            model.parts.put(e.getKey(),
                    new Part(e.getKey(), e.getValue(), pivot[0], pivot[1], pivot[2]));
            // Диагностика: размеры частей после нод-матриц и фита.
            TorqueFoundry.LOGGER.info("glTF node '{}': bbox {} px, {} tris",
                    e.getKey(), java.util.Arrays.toString(bboxSize(lod0)), lod0.length);
        }
        TorqueFoundry.LOGGER.info("glTF model: scale={} px/unit, fit={}",
                finalScale, fitToBlock ? "on" : "off");
        return model;
    }

    /** Часть + её квады одного LOD-файла. */
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
                if (len > 1e-9F) {
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
                if (v.x < minX) minX = v.x;
                if (v.y < minY) minY = v.y;
                if (v.z < minZ) minZ = v.z;
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
                if (v.x > maxX) maxX = v.x;
                if (v.y > maxY) maxY = v.y;
                if (v.z > maxZ) maxZ = v.z;
            }
        }
        return new float[]{maxX, maxY, maxZ};
    }

    private static float[] bboxSize(Quad[] quads) {
        final float[] min = bboxMin(quads);
        final float[] max = bboxMax(quads);
        return new float[]{max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    /** Р¦РµРЅС‚СЂ bounding box РєРІР°РґРѕРІ вЂ” pivot РІСЂР°С‰РµРЅРёСЏ С‡Р°СЃС‚Рё. */
    private static float[] computePivot(Quad[] quads) {
        if (quads.length == 0) {
            return new float[]{8, 8, 8};
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;
        for (Quad q : quads) {
            for (Vertex v : q.vertices) {
                if (v.x < minX) minX = v.x;
                if (v.y < minY) minY = v.y;
                if (v.z < minZ) minZ = v.z;
                if (v.x > maxX) maxX = v.x;
                if (v.y > maxY) maxY = v.y;
                if (v.z > maxZ) maxZ = v.z;
            }
        }
        return new float[]{(minX + maxX) * 0.5F, (minY + maxY) * 0.5F, (minZ + maxZ) * 0.5F};
    }

    /**
     * РўСЂРёР°РЅРіСѓР»СЏС†РёСЏ: РєР°Р¶РґС‹Рµ 3 РІРµСЂС€РёРЅС‹ = С‚СЂРµСѓРіРѕР»СЊРЅРёРє -> Quad СЃ РІС‹СЂРѕР¶РґРµРЅРЅРѕР№
     * 4-Р№ РІРµСЂС€РёРЅРѕР№ (d = c). РџРѕСЂСЏРґРѕРє CCW СЃРѕС…СЂР°РЅСЏРµС‚СЃСЏ, РЅРѕСЂРјР°Р»Рё РёР· glTF
     * (РёР»Рё РІС‹С‡РёСЃР»РµРЅРЅС‹Рµ, РµСЃР»Рё РёС… РЅРµС‚).
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
            final Vertex d = new Vertex();
            d.x = c.x;
            d.y = c.y;
            d.z = c.z;
            d.normalX = c.normalX;
            d.normalY = c.normalY;
            d.normalZ = c.normalZ;
            d.u = c.u;
            d.v = c.v;
            final Quad q = new Quad();
            q.vertices = new Vertex[]{a, b, c, d};
            q.invertNormal = false;
            out.add(q);
        }
        return out;
    }

    private static Vertex vertex(GltfParser.RawPrimitive prim, int index, float scale) {
        final Vertex v = new Vertex();
        // glTF: РјРµС‚СЂС‹, Y-up, +Z Рє Р·СЂРёС‚РµР»СЋ. Р‘Р»РѕРє: РїРёРєСЃРµР»Рё, Y-up, -Z СЃРµРІРµСЂ.
        // РџРѕРІРѕСЂРѕС‚ РЅР° 180 РІРѕРєСЂСѓРі Y (x=-x, z=-z): СЃС‚СЂРѕРєР° "z=-z" Р‘Р•Р— x=-x Р±С‹Р»Р°
        // Р±С‹ РѕС‚СЂР°Р¶РµРЅРёРµРј вЂ” winding С‚СЂРµСѓРіРѕР»СЊРЅРёРєРѕРІ РёРЅРІРµСЂС‚РёСЂСѓРµС‚СЃСЏ Рё cull
        // РѕС‚СЃРµРєР°РµС‚ РЅР°СЂСѓР¶РЅС‹Рµ РіСЂР°РЅРё (РјРѕРґРµР»СЊ "РІС‹РІРµСЂРЅСѓС‚Р°").
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

    /** РџР»РѕСЃРєР°СЏ РЅРѕСЂРјР°Р»СЊ С‚СЂРµСѓРіРѕР»СЊРЅРёРєР° (РєРѕРіРґР° РІ glTF РЅРѕСЂРјР°Р»РµР№ РЅРµС‚). */
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
        if (len < 1e-9F) {
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

