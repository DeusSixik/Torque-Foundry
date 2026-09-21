package dev.sdm.torque_foundry.core.client.render.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.TorqueFoundryConstants;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Дамп LOD-уровней модели в валидные {@code .glb} для визуальной проверки
 * в Blender: что нагенерил MeshSimplifier и что вообще подхватилось
 * из assets (файлы vs рантайм-генерация).
 *
 * <p>Включается флагом {@link TorqueFoundryConstants#DUMP_LODS}
 * ({@code -Dtorque_foundry.dump_lods=true}). Файлы:
 * {@code <gamedir>/torque_foundry_dump/<модель>_lod<N>.glb} —
 * по одному файлу на уровень, все части модели внутри.
 *
 * <p>Координаты обратно в метры glTF: пиксели модели / 16, без R180Y
 * (Blender сам покажет как надо — зеркальность видна сразу).
 * Каждый Quad пишется как 2 треугольника (a,b,c)+(a,c,d): вырожденная
 * 4-я вершина даёт нулевой второй треугольник, Blender его переживёт.
 */
public final class GltfDumper {

    /**
     * Магика glb: 'glTF'.
     */
    private static final int GLB_MAGIC = 0x46546C67;
    /**
     * Тип чанка JSON.
     */
    private static final int GLB_CHUNK_JSON = 0x4E4F534A;
    /**
     * Тип чанка BIN.
     */
    private static final int GLB_CHUNK_BIN = 0x004E4942;
    /**
     * mode: TRIANGLES.
     */
    private static final int MODE_TRIANGLES = 4;
    /**
     * componentType FLOAT.
     */
    private static final int CT_FLOAT = 5126;
    /**
     * componentType UNSIGNED_INT.
     */
    private static final int CT_UNSIGNED_INT = 5125;
    /**
     * Пиксели модели -> метры glTF.
     */
    private static final float PIXELS_TO_METERS = 1.0F / 16.0F;
    /**
     * Паддинг JSON-чанка glb: пробел (не ноль — JSON с нулями невалиден).
     */
    private static final byte GLB_JSON_PAD = 0x20;

    private GltfDumper() {
    }

    /**
     * Включён ли дамп (флаг + клиент).
     */
    public static boolean enabled() {
        return TorqueFoundryConstants.DUMP_LODS;
    }

    /**
     * Сдампить все LOD-уровни модели: часть -> список уровней квадов.
     *
     * @param modelName базовое имя (например "models/gltf/val_horizontal")
     * @param partLods  имя части -> уровни квадов (пиксели модели 0..16)
     * @param gameDir   каталог игры (куда положить torque_foundry_dump)
     */
    public static void dump(String modelName, Map<String, List<Quad[]>> partLods, Path gameDir) {
        if (!enabled() || partLods.isEmpty()) {
            return;
        }
        dumpInternal(modelName, partLods, gameDir, "");
    }

    private static void dumpInternal(String modelName, Map<String, List<Quad[]>> partLods,
                                     Path gameDir, String suffix) {
        // Сколько уровней: максимум по частям.
        int levels = 0;
        for (List<Quad[]> lods : partLods.values()) {
            levels = Math.max(levels, lods.size());
        }
        final String safeName = modelName.replace('/', '_').replace('\\', '_');
        for (int lod = 0; lod < levels; lod++) {
            try {
                final byte[] glb = buildGlb(partLods, lod);
                final Path dir = gameDir.resolve(TorqueFoundryConstants.DUMP_DIR);
                Files.createDirectories(dir);
                final Path out = dir.resolve(safeName + "_lod" + lod + suffix + ".glb");
                Files.write(out, glb);
            } catch (IOException | RuntimeException e) {
                // Дамп — debug-инструмент: не роняем загрузку модели.
                TorqueFoundry.LOGGER.warn("glTF dump failed for {} lod{}", modelName, lod, e);
            }
        }
    }

    // --- Сборка .glb ---

    private static byte[] buildGlb(Map<String, List<Quad[]>> partLods, int lod) {
        // Вершины/индексы по мешам (частям). Позиции: пиксели/16 -> метры.
        final JsonArray meshes = new JsonArray();
        final JsonArray nodes = new JsonArray();
        final JsonArray accessors = new JsonArray();
        final JsonArray bufferViews = new JsonArray();

        // Бин соберём динамически: позиции (VEC3 float) + нормали (VEC3 float) +
        // UV (VEC2 float) подряд на меш, потом индексы (uint32).
        final ByteArrayOutputStream bin = new ByteArrayOutputStream();
        int nodeIndex = 0;
        for (Map.Entry<String, List<Quad[]>> e : partLods.entrySet()) {
            final List<Quad[]> lods = e.getValue();
            final Quad[] quads = lod < lods.size() ? lods.get(lod) : lods.get(lods.size() - 1);
            if (quads.length == 0) {
                continue;
            }
            // Разворачиваем квады в треугольники: (a,b,c) + (a,c,d).
            final int triCount = quads.length * 2;
            final float[] pos = new float[triCount * 3 * 3];
            final float[] nor = new float[triCount * 3 * 3];
            final float[] uv = new float[triCount * 3 * 2];
            int t = 0;
            for (Quad q : quads) {
                if (q == null || q.vertices == null || q.vertices.length < 4) {
                    continue;
                }
                final Vertex[] v = q.vertices;
                t = emitTri(pos, nor, uv, t, v[0], v[1], v[2]);
                t = emitTri(pos, nor, uv, t, v[0], v[2], v[3]);
            }
            final int vertCount = t * 3;

            final int posOff = bin.size();
            writeFloats(bin, pos, 0, vertCount * 3);
            final int norOff = bin.size();
            writeFloats(bin, nor, 0, vertCount * 3);
            final int uvOff = bin.size();
            writeFloats(bin, uv, 0, vertCount * 2);
            // Индексы: 0..vertCount-1 подряд.
            final int idxOff = bin.size();
            final ByteBuffer idxBuf =
                    ByteBuffer.allocate(vertCount * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < vertCount; i++) {
                idxBuf.putInt(i);
            }
            try {
                bin.write(idxBuf.array());
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }

            final int posView = addBufferView(bufferViews, posOff, vertCount * 12, 12, 0);
            final int norView = addBufferView(bufferViews, norOff, vertCount * 12, 12, 0);
            final int uvView = addBufferView(bufferViews, uvOff, vertCount * 8, 8, 0);
            final int idxView = addBufferView(bufferViews, idxOff, vertCount * 4, 0, 0);

            final float[] minPos = minmax(pos, vertCount, true);
            final float[] maxPos = minmax(pos, vertCount, false);
            final int posAcc =
                    addAccessor(accessors, posView, CT_FLOAT, vertCount, "VEC3", minPos, maxPos);
            final int norAcc = addAccessor(accessors, norView, CT_FLOAT, vertCount, "VEC3", null, null);
            final int uvAcc = addAccessor(accessors, uvView, CT_FLOAT, vertCount, "VEC2", null, null);
            final int idxAcc =
                    addAccessor(accessors, idxView, CT_UNSIGNED_INT, vertCount, "SCALAR", null, null);

            final JsonObject prim = new JsonObject();
            prim.addProperty("mode", MODE_TRIANGLES);
            prim.addProperty("indices", idxAcc);
            final JsonObject attrs = new JsonObject();
            attrs.addProperty("POSITION", posAcc);
            attrs.addProperty("NORMAL", norAcc);
            attrs.addProperty("TEXCOORD_0", uvAcc);
            prim.add("attributes", attrs);

            final JsonArray prims = new JsonArray();
            prims.add(prim);
            final JsonObject mesh = new JsonObject();
            mesh.addProperty("name", e.getKey());
            mesh.add("primitives", prims);
            meshes.add(mesh);

            final JsonObject node = new JsonObject();
            node.addProperty("name", e.getKey());
            node.addProperty("mesh", meshes.size() - 1);
            nodes.add(node);
            nodeIndex++;
        }

        final JsonObject root = new JsonObject();
        final JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "torque-foundry-lod-dump");
        root.add("asset", asset);
        root.add("meshes", meshes);
        root.add("nodes", nodes);
        final JsonArray scenes = new JsonArray();
        final JsonObject scene = new JsonObject();
        final JsonArray sceneNodes = new JsonArray();
        for (int i = 0; i < nodeIndex; i++) {
            sceneNodes.add(i);
        }
        scene.add("nodes", sceneNodes);
        scenes.add(scene);
        root.add("scenes", scenes);
        root.add("scene", JsonParser.parseString("0"));
        root.add("buffers", bufferArray(bin.size()));
        root.add("bufferViews", bufferViews);
        root.add("accessors", accessors);

        final byte[] jsonBytes = root.toString().getBytes(StandardCharsets.UTF_8);
        final byte[] binBytes = bin.toByteArray();
        return assembleGlb(jsonBytes, binBytes);
    }

    private static int emitTri(float[] pos, float[] nor, float[] uv, int t, Vertex a, Vertex b,
                               Vertex c) {
        // Пиксели модели -> метры: /16. Без R180Y — как есть из квадов.
        emitVertex(pos, nor, uv, t * 3, a);
        emitVertex(pos, nor, uv, t * 3 + 1, b);
        emitVertex(pos, nor, uv, t * 3 + 2, c);
        return t + 1;
    }

    private static void emitVertex(float[] pos, float[] nor, float[] uv, int vi, Vertex v) {
        pos[vi * 3] = v.x * PIXELS_TO_METERS;
        pos[vi * 3 + 1] = v.y * PIXELS_TO_METERS;
        pos[vi * 3 + 2] = v.z * PIXELS_TO_METERS;
        nor[vi * 3] = v.normalX;
        nor[vi * 3 + 1] = v.normalY;
        nor[vi * 3 + 2] = v.normalZ;
        uv[vi * 2] = v.u;
        uv[vi * 2 + 1] = v.v;
    }

    private static void writeFloats(ByteArrayOutputStream out, float[] data, int off, int count) {
        final ByteBuffer buf = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            buf.putFloat(data[off + i]);
        }
        try {
            out.write(buf.array());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static float[] minmax(float[] data, int vertCount, boolean min) {
        final float[] out = new float[3];
        for (int c = 0; c < 3; c++) {
            float v = min ? Float.MAX_VALUE : -Float.MAX_VALUE;
            for (int i = 0; i < vertCount; i++) {
                final float f = data[i * 3 + c];
                v = min ? Math.min(v, f) : Math.max(v, f);
            }
            out[c] = v;
        }
        return out;
    }

    private static int addBufferView(JsonArray views, int byteOffset, int byteLength,
                                     int byteStride, int buffer) {
        final JsonObject v = new JsonObject();
        v.addProperty("buffer", buffer);
        v.addProperty("byteOffset", byteOffset);
        v.addProperty("byteLength", byteLength);
        if (byteStride > 0) {
            v.addProperty("byteStride", byteStride);
        }
        views.add(v);
        return views.size() - 1;
    }

    private static int addAccessor(JsonArray accessors, int bufferView, int componentType, int count,
                                   String type, float[] min, float[] max) {
        final JsonObject a = new JsonObject();
        a.addProperty("bufferView", bufferView);
        a.addProperty("byteOffset", 0);
        a.addProperty("componentType", componentType);
        a.addProperty("count", count);
        a.addProperty("type", type);
        if (min != null) {
            final JsonArray minArr = new JsonArray();
            final JsonArray maxArr = new JsonArray();
            for (int i = 0; i < min.length; i++) {
                minArr.add(min[i]);
                maxArr.add(max[i]);
            }
            a.add("min", minArr);
            a.add("max", maxArr);
        }
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static JsonArray bufferArray(int byteLength) {
        final JsonArray buffers = new JsonArray();
        final JsonObject b = new JsonObject();
        b.addProperty("byteLength", byteLength);
        buffers.add(b);
        return buffers;
    }

    /**
     * Сборка .glb: header + JSON-чанк (с паддингом до 4) + BIN-чанк.
     */
    private static byte[] assembleGlb(byte[] json, byte[] bin) {
        final int jsonPadded = pad4(json.length);
        final int binPadded = pad4(bin.length);
        final int total = 12 + 8 + jsonPadded + 8 + binPadded;
        final ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(GLB_MAGIC); // 'glTF'
        buf.putInt(2);
        buf.putInt(total);
        buf.putInt(jsonPadded);
        buf.putInt(GLB_CHUNK_JSON); // 'JSON'
        buf.put(json);
        pad(buf, json.length);
        buf.putInt(binPadded);
        buf.putInt(GLB_CHUNK_BIN); // 'BIN\0'
        buf.put(bin);
        pad(buf, bin.length);
        return buf.array();
    }

    private static int pad4(int len) {
        return (len + 3) & ~3;
    }

    private static void pad(ByteBuffer buf, int len) {
        final int need = pad4(len) - len;
        for (int i = 0; i < need; i++) {
            buf.put(GLB_JSON_PAD);
        }
    }
}
