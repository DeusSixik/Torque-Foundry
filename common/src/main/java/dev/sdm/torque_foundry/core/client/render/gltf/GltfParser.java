package dev.sdm.torque_foundry.core.client.render.gltf;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Минимальный парсер glTF 2.0 под модели блоков из Blender.
 *
 * <p>Что умеет:
 * <ul>
 *   <li>{@code .glb} (чанки JSON+BIN) и {@code .gltf} (JSON + внешние {@code .bin} / data-URI);</li>
 *   <li>меши по именам ({@code Corp}, {@code Val}, ...): каждый mesh = список примитивов;</li>
 *   <li>примитивы TRIANGLES с POSITION (+ NORMAL, TEXCOORD_0 опционально), индексы любые;</li>
 *   <li>ноду TRS-трансформации (translation/rotation/scale, matrix);</li>
 *   <li>LOD-папки: мэппинг {@code LOD0/Corp} — выбор уровня в {@link GltfModel};</li>
 * </ul>
 *
 * <p>Что НЕ умеет (и не надо для блоков): скининг, анимации, морфы, Draco,
 * sparse accessors, материалы сложнее baseColor. Неподдерживаемое падает
 * понятным IllegalArgumentException с именем меша, а не NPE в рендере.
 */
public final class GltfParser {

    // --- glTF константы ---

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
     * mode: TRIANGLES. Единственный поддерживаемый режим примитива.
     */
    private static final int MODE_TRIANGLES = 4;

    /**
     * componentType.
     */
    @SuppressWarnings("unused")
    private static final int CT_BYTE = 5120;
    private static final int CT_UNSIGNED_BYTE = 5121;
    @SuppressWarnings("unused")
    private static final int CT_SHORT = 5122;
    private static final int CT_UNSIGNED_SHORT = 5123;
    private static final int CT_UNSIGNED_INT = 5125;
    private static final int CT_FLOAT = 5126;

    private GltfParser() {
    }

    // --- Публичное API ---

    /**
     * Результат парсинга: сырые меши + ноды.
     */
    public record ParsedModel(List<RawMesh> meshes, List<RawNode> nodes, int sceneRoot) {
    }

    /**
     * Сырой меш: имя + примитивы (позиции/нормали/UV уже в float[], индексы в int[]).
     */
    public record RawMesh(String name, List<RawPrimitive> primitives) {
    }

    /**
     * Сырой примитив: развернутые (по индексам) массивы вершин.
     */
    public record RawPrimitive(float[] positions, float[] normals, float[] uvs) {
    }

    /**
     * Сырая нода: имя + mesh-индекс + МИРОВАЯ матрица (композиция всех
     * родителей, column-major). POSITION меша хранится в локальных
     * координатах объекта — смещение объекта в Blender живёт именно тут,
     * в ноде (translation/rotation/scale).
     */
    public record RawNode(String name, int meshIndex, float[] worldMatrix) {
    }

    /**
     * Парсинг {@code .glb} из потока (assets/mod resources).
     */
    public static ParsedModel parseGlb(InputStream in) throws IOException {
        final byte[] bytes = in.readAllBytes();
        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        final int magic = buf.getInt();
        if (magic != GLB_MAGIC) {
            throw new IllegalArgumentException("Not a GLB file (bad magic)");
        }
        buf.getInt(); // version
        buf.getInt(); // length

        String json = null;
        byte[] bin = new byte[0];
        while (buf.remaining() >= 8) {
            final int chunkLen = buf.getInt();
            final int chunkType = buf.getInt();
            if (chunkLen < 0 || chunkLen > buf.remaining()) {
                throw new IllegalArgumentException("GLB chunk length out of bounds: " + chunkLen);
            }
            final byte[] chunk = new byte[chunkLen];
            buf.get(chunk);
            if (chunkType == GLB_CHUNK_JSON) {
                json = new String(chunk, StandardCharsets.UTF_8);
            } else if (chunkType == GLB_CHUNK_BIN) {
                bin = chunk;
            }
        }
        if (json == null) {
            throw new IllegalArgumentException("GLB has no JSON chunk");
        }
        return parseJson(JsonParser.parseString(json).getAsJsonObject(), List.of(bin), "");
    }

    /**
     * Парсинг {@code .gltf} из потока. Внешние буферы резолвятся через opener
     * (относительно папки .gltf): {@code open(relativePath)}.
     */
    public static ParsedModel parseGltf(InputStream in, ResourceOpener opener) throws IOException {
        final JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        final List<byte[]> buffers = new ArrayList<>();
        final JsonArray buffersJson = optArray(root, "buffers");
        for (int i = 0; i < buffersJson.size(); i++) {
            final JsonObject b = buffersJson.get(i).getAsJsonObject();
            final String uri = b.has("uri") ? b.get("uri").getAsString() : null;
            if (uri == null) {
                buffers.add(new byte[b.get("byteLength").getAsInt()]);
            } else if (uri.startsWith("data:")) {
                final int comma = uri.indexOf(',');
                buffers.add(Base64.getDecoder().decode(uri.substring(comma + 1)));
            } else {
                try (InputStream bin = opener.open(uri)) {
                    buffers.add(bin.readAllBytes());
                }
            }
        }
        return parseJson(root, buffers, "");
    }

    /**
     * Открытие внешнего ресурса (буфер/текстура) относительно модели.
     */
    public interface ResourceOpener {
        InputStream open(String relativePath) throws IOException;
    }

    // --- Разбор JSON ---

    private static ParsedModel parseJson(JsonObject root, List<byte[]> buffers, String lodPrefix) {
        final JsonArray bufferViewsJson = optArray(root, "bufferViews");
        final JsonArray accessorsJson = optArray(root, "accessors");
        final JsonArray meshesJson = optArray(root, "meshes");
        final JsonArray nodesJson = optArray(root, "nodes");

        final BufferView[] bufferViews = new BufferView[bufferViewsJson.size()];
        for (int i = 0; i < bufferViewsJson.size(); i++) {
            final JsonObject v = bufferViewsJson.get(i).getAsJsonObject();
            bufferViews[i] = new BufferView(
                    v.get("buffer").getAsInt(),
                    v.has("byteOffset") ? v.get("byteOffset").getAsInt() : 0,
                    v.get("byteLength").getAsInt(),
                    v.has("byteStride") ? v.get("byteStride").getAsInt() : 0);
        }

        final Accessor[] accessors = new Accessor[accessorsJson.size()];
        for (int i = 0; i < accessorsJson.size(); i++) {
            final JsonObject a = accessorsJson.get(i).getAsJsonObject();
            accessors[i] = new Accessor(
                    a.has("bufferView") ? a.get("bufferView").getAsInt() : -1,
                    a.has("byteOffset") ? a.get("byteOffset").getAsInt() : 0,
                    a.get("componentType").getAsInt(),
                    a.get("count").getAsInt(),
                    a.get("type").getAsString());
            if (a.has("sparse")) {
                throw new IllegalArgumentException("sparse accessors not supported (accessor " + i + ")");
            }
        }

        final List<RawMesh> meshes = new ArrayList<>();
        for (int i = 0; i < meshesJson.size(); i++) {
            final JsonObject m = meshesJson.get(i).getAsJsonObject();
            final String meshName = m.has("name") ? m.get("name").getAsString() : "mesh" + i;
            final String name = lodPrefix.isEmpty() ? meshName : lodPrefix + "/" + meshName;
            final List<RawPrimitive> primitives = new ArrayList<>();
            final JsonArray primsJson = m.getAsJsonArray("primitives");
            for (int p = 0; p < primsJson.size(); p++) {
                primitives.add(parsePrimitive(
                        primsJson.get(p).getAsJsonObject(), name + "#p" + p,
                        buffers, bufferViews, accessors));
            }
            meshes.add(new RawMesh(name, primitives));
        }

        // Ноды: локальная матрица + дети; мировая = композиция родителей
        // (DFS от корней). Именно worldMatrix несёт offset объекта из Blender.
        final int nodeCount = nodesJson.size();
        final float[][] local = new float[nodeCount][];
        final List<List<Integer>> children = new ArrayList<>(nodeCount);
        final boolean[] hasParent = new boolean[nodeCount];
        final List<RawNode> nodes = new ArrayList<>(nodeCount);

        for (int i = 0; i < nodeCount; i++) {
            final JsonObject n = nodesJson.get(i).getAsJsonObject();
            local[i] = parseNodeMatrix(n);
            final List<Integer> kids = new ArrayList<>();
            if (n.has("children")) {
                final JsonArray arr = n.getAsJsonArray("children");
                for (int c = 0; c < arr.size(); c++) {
                    final int child = arr.get(c).getAsInt();
                    if (child >= 0 && child < nodeCount) {
                        kids.add(child);
                        hasParent[child] = true;
                    }
                }
            }
            children.add(kids);
        }

        // Корни: scene[0].nodes, иначе все без родителей.
        int[] roots;
        final JsonArray scenes = optArray(root, "scenes");
        if (!scenes.isEmpty()) {
            final JsonObject scene = scenes.get(0).getAsJsonObject();
            final List<Integer> list = new ArrayList<>();
            if (scene.has("nodes")) {
                final JsonArray arr = scene.getAsJsonArray("nodes");
                for (int i = 0; i < arr.size(); i++) {
                    list.add(arr.get(i).getAsInt());
                }
            }
            roots = list.isEmpty() ? allRoots(hasParent) : toInts(list);
        } else {
            roots = allRoots(hasParent);
        }

        final float[] identity = composeTRS(new float[3], new float[]{0, 0, 0, 1},
                new float[]{1, 1, 1});
        final boolean[] visited = new boolean[nodeCount];
        // DFS с явным стеком (узел + матрица родителя): два параллельных списка
        // вместо массива int[1] под стек-фрейм — ноль wrapper-аллокаций на узел.
        final List<Integer> pending = new ArrayList<>();
        final List<float[]> pendingWorld = new ArrayList<>();
        for (int r = 0; r < roots.length; r++) {
            pending.add(roots[r]);
            pendingWorld.add(identity);
            while (!pending.isEmpty()) {
                final int idx = pending.remove(pending.size() - 1);
                final float[] parentWorld = pendingWorld.remove(pendingWorld.size() - 1);
                if (visited[idx]) {
                    continue;
                }
                visited[idx] = true;
                final float[] worldM = mul(parentWorld, local[idx]);
                final JsonObject n = nodesJson.get(idx).getAsJsonObject();
                final String nodeName = n.has("name") ? n.get("name").getAsString() : "node" + idx;
                final int meshIndex = n.has("mesh") ? n.get("mesh").getAsInt() : -1;
                nodes.add(new RawNode(nodeName, meshIndex, worldM));
                for (int child : children.get(idx)) {
                    pending.add(child);
                    pendingWorld.add(worldM);
                }
            }
        }

        return new ParsedModel(meshes, nodes, 0);
    }

    private static int[] allRoots(boolean[] hasParent) {
        final List<Integer> list = new ArrayList<>();
        for (int i = 0; i < hasParent.length; i++) {
            if (!hasParent[i]) {
                list.add(i);
            }
        }
        return toInts(list);
    }

    private static int[] toInts(List<Integer> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /**
     * Умножение column-major матриц 4x4: out = a * b.
     */
    private static float[] mul(float[] a, float[] b) {
        final float[] out = new float[16];
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = s;
            }
        }
        return out;
    }

    private static RawPrimitive parsePrimitive(JsonObject prim, String debugName,
                                               List<byte[]> buffers, BufferView[] bufferViews, Accessor[] accessors) {
        final int mode = prim.has("mode") ? prim.get("mode").getAsInt() : MODE_TRIANGLES;
        if (mode != MODE_TRIANGLES) {
            throw new IllegalArgumentException(
                    "Mesh " + debugName + ": only TRIANGLES supported, mode=" + mode);
        }
        final JsonObject attrs = prim.getAsJsonObject("attributes");
        if (attrs == null || !attrs.has("POSITION")) {
            throw new IllegalArgumentException("Mesh " + debugName + ": POSITION attribute required");
        }
        final int posAccessor = attrs.get("POSITION").getAsInt();
        final int normAccessor = attrs.has("NORMAL") ? attrs.get("NORMAL").getAsInt() : -1;
        final int uvAccessor = attrs.has("TEXCOORD_0") ? attrs.get("TEXCOORD_0").getAsInt() : -1;
        final int indexAccessor = prim.has("indices") ? prim.get("indices").getAsInt() : -1;

        final float[] positions =
                readVec3(accessors[posAccessor], buffers, bufferViews, debugName + ".POSITION");
        final float[] normals = normAccessor >= 0
                ? readVec3(accessors[normAccessor], buffers, bufferViews, debugName + ".NORMAL")
                : null;
        final float[] uvs = uvAccessor >= 0
                ? readVec2(accessors[uvAccessor], buffers, bufferViews, debugName + ".TEXCOORD_0")
                : null;

        // Развёртка по индексам: рендер ест плоские массивы без index-буфера.
        if (indexAccessor < 0) {
            return new RawPrimitive(positions, normals, uvs);
        }
        final int[] indices = readIndices(accessors[indexAccessor], buffers, bufferViews, debugName);
        final int count = indices.length;
        final float[] outPos = new float[count * 3];
        for (int i = 0; i < count; i++) {
            final int src = indices[i] * 3;
            outPos[i * 3] = positions[src];
            outPos[i * 3 + 1] = positions[src + 1];
            outPos[i * 3 + 2] = positions[src + 2];
        }
        float[] outNorm = null;
        if (normals != null) {
            outNorm = new float[count * 3];
            for (int i = 0; i < count; i++) {
                final int src = indices[i] * 3;
                outNorm[i * 3] = normals[src];
                outNorm[i * 3 + 1] = normals[src + 1];
                outNorm[i * 3 + 2] = normals[src + 2];
            }
        }
        float[] outUv = null;
        if (uvs != null) {
            outUv = new float[count * 2];
            for (int i = 0; i < count; i++) {
                final int src = indices[i] * 2;
                outUv[i * 2] = uvs[src];
                outUv[i * 2 + 1] = uvs[src + 1];
            }
        }
        return new RawPrimitive(outPos, outNorm, outUv);
    }

    /**
     * Матрица ноды: matrix напрямую или TRS (column-major, как в glTF).
     */
    private static float[] parseNodeMatrix(JsonObject node) {
        if (node.has("matrix")) {
            final JsonArray arr = node.getAsJsonArray("matrix");
            final float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = arr.get(i).getAsFloat();
            }
            return m;
        }
        final float[] t = node.has("translation") ? floats(node.getAsJsonArray("translation"), 3) : new float[3];
        final float[] r = node.has("rotation")
                ? floats(node.getAsJsonArray("rotation"), 4)
                : new float[]{0, 0, 0, 1};
        final float[] s = node.has("scale") ? floats(node.getAsJsonArray("scale"), 3) : new float[]{1, 1, 1};
        return composeTRS(t, r, s);
    }

    private static float[] floats(JsonArray arr, int count) {
        final float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = arr.get(i).getAsFloat();
        }
        return out;
    }

    /**
     * T * R * S, column-major 4x4. Кватернион (x, y, z, w).
     */
    private static float[] composeTRS(float[] t, float[] q, float[] s) {
        final float x = q[0];
        final float y = q[1];
        final float z = q[2];
        final float w = q[3];
        final float xx = x * x;
        final float yy = y * y;
        final float zz = z * z;
        final float xy = x * y;
        final float xz = x * z;
        final float yz = y * z;
        final float wx = w * x;
        final float wy = w * y;
        final float wz = w * z;
        // Column-major: m[col * 4 + row].
        return new float[]{
                (1 - 2 * (yy + zz)) * s[0], 2 * (xy + wz) * s[0], 2 * (xz - wy) * s[0], 0,
                2 * (xy - wz) * s[1], (1 - 2 * (xx + zz)) * s[1], 2 * (yz + wx) * s[1], 0,
                2 * (xz + wy) * s[2], 2 * (yz - wx) * s[2], (1 - 2 * (xx + yy)) * s[2], 0,
                t[0], t[1], t[2], 1,
        };
    }

    // --- Чтение accessors ---

    private record BufferView(int buffer, int byteOffset, int byteLength, int byteStride) {
    }

    private record Accessor(int bufferView, int byteOffset, int componentType, int count, String type) {
    }

    private static float[] readVec3(Accessor acc, List<byte[]> buffers, BufferView[] views,
                                    String debug) {
        if (!"VEC3".equals(acc.type())) {
            throw new IllegalArgumentException(debug + ": expected VEC3, got " + acc.type());
        }
        if (acc.componentType() != CT_FLOAT) {
            throw new IllegalArgumentException(debug + ": only FLOAT supported");
        }
        final ByteBuffer buf = slice(acc, buffers, views, 12);
        final float[] out = new float[acc.count() * 3];
        for (int i = 0; i < acc.count(); i++) {
            out[i * 3] = buf.getFloat();
            out[i * 3 + 1] = buf.getFloat();
            out[i * 3 + 2] = buf.getFloat();
        }
        return out;
    }

    private static float[] readVec2(Accessor acc, List<byte[]> buffers, BufferView[] views,
                                    String debug) {
        if (!"VEC2".equals(acc.type())) {
            throw new IllegalArgumentException(debug + ": expected VEC2, got " + acc.type());
        }
        if (acc.componentType() != CT_FLOAT) {
            throw new IllegalArgumentException(debug + ": only FLOAT supported");
        }
        final ByteBuffer buf = slice(acc, buffers, views, 8);
        final float[] out = new float[acc.count() * 2];
        for (int i = 0; i < acc.count(); i++) {
            out[i * 2] = buf.getFloat();
            out[i * 2 + 1] = buf.getFloat();
        }
        return out;
    }

    private static int[] readIndices(Accessor acc, List<byte[]> buffers, BufferView[] views,
                                     String debug) {
        if (!"SCALAR".equals(acc.type())) {
            throw new IllegalArgumentException(debug + ": indices must be SCALAR");
        }
        final int[] out = new int[acc.count()];
        switch (acc.componentType()) {
            case CT_UNSIGNED_BYTE -> {
                final ByteBuffer buf = slice(acc, buffers, views, 1);
                for (int i = 0; i < out.length; i++) {
                    out[i] = buf.get() & 0xFF;
                }
            }
            case CT_UNSIGNED_SHORT -> {
                final ByteBuffer buf = slice(acc, buffers, views, 2);
                for (int i = 0; i < out.length; i++) {
                    out[i] = buf.getShort() & 0xFFFF;
                }
            }
            case CT_UNSIGNED_INT -> {
                final ByteBuffer buf = slice(acc, buffers, views, 4);
                for (int i = 0; i < out.length; i++) {
                    final long v = buf.getInt() & 0xFFFFFFFFL;
                    if (v > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(debug + ": index too large");
                    }
                    out[i] = (int) v;
                }
            }
            default -> throw new IllegalArgumentException(
                    debug + ": unsupported index componentType " + acc.componentType());
        }
        return out;
    }

    /**
     * Срез буфера под accessor: учитывает byteOffset/strided interleaved.
     */
    private static ByteBuffer slice(Accessor acc, List<byte[]> buffers, BufferView[] views,
                                    int componentBytes) {
        if (acc.bufferView() < 0) {
            throw new IllegalArgumentException("accessor without bufferView not supported");
        }
        final BufferView view = views[acc.bufferView()];
        final byte[] bytes = buffers.get(view.buffer());
        final int base = view.byteOffset() + acc.byteOffset();
        final int stride = view.byteStride() != 0 ? view.byteStride() : componentBytes;
        final ByteBuffer src = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // Разуплотнение interleaved: собираем плотный буфер.
        if (stride == componentBytes) {
            src.position(base);
            src.limit(base + acc.count() * componentBytes);
            return src.slice().order(ByteOrder.LITTLE_ENDIAN);
        }
        final ByteBuffer dense = ByteBuffer.allocate(acc.count() * componentBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < acc.count(); i++) {
            final int off = base + i * stride;
            for (int b = 0; b < componentBytes; b++) {
                dense.put(bytes[off + b]);
            }
        }
        dense.flip();
        return dense;
    }

    private static JsonArray optArray(JsonObject root, String key) {
        final JsonElement el = root.get(key);
        return el instanceof JsonArray arr ? arr : new JsonArray();
    }
}
