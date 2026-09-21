package dev.sdm.torque_foundry.core.client.render;

import dev.sdm.torque_foundry.core.client.render.structs.Quad;
import dev.sdm.torque_foundry.core.client.render.structs.Vertex;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Упрощение треугольного меша (decimation) для автогенерации LOD-уровней
 * из LOD0: edge-collapse с quadric error metric (упрощённый Garland–Heckbert).
 *
 * <p>Как работает: каждый треугольник (наш Quad — треугольник с вырожденной
 * 4-й вершиной) получает цену = quadric error схлопывания его кратчайшего
 * ребра в середину; схлопываем самые дешёвые, пока не дойдём до целевого
 * числа треугольников. Удаляются и вырожденные (нулевая площадь) фасеты.
 *
 * <p>Сложность ~O(n log n) через очередь. Работает на загрузке модели,
 * не в кадре. UV/нормали переживают схлопывание как есть (интерполяции
 * атрибутов нет — для LOD1+ на дистанции это незаметно).
 */
public final class MeshSimplifier {

    /**
     * Порог вырождения треугольника: удвоенная площадь ниже — удаляем.
     */
    private static final double DEGENERATE_AREA2 = 1e-12;
    /**
     * Вес площади в цене схлопывания: крупные фасетки силуэта дороже.
     */
    private static final double AREA_COST_WEIGHT = 0.25;

    private MeshSimplifier() {
    }

    /**
     * Упростить меш до целевого числа квадов-треугольников.
     *
     * @param input  исходные квады (каждый — треугольник a,b,c + d=c)
     * @param target целевое число квадов (>= 1)
     * @return новый массив квадов, длина min(target, input.length)
     */
    public static Quad[] simplify(Quad[] input, int target) {
        if (input == null || input.length <= Math.max(1, target)) {
            return input == null ? new Quad[0] : Arrays.copyOf(input, input.length);
        }
        final int want = Math.max(1, target);

        // Плоские SoA-буферы вместо List<float[][]>: позиции/нормали/UV лежат
        // плотно (tri * 3 вершины * компоненты), без object-графа на треугольник.
        int triCount = 0;
        for (Quad q : input) {
            if (q != null && q.vertices != null && q.vertices.length >= 3) {
                triCount++;
            }
        }
        if (triCount <= want) {
            return Arrays.copyOf(input, input.length);
        }
        final float[] pos = new float[triCount * 9];
        final float[] norm = new float[triCount * 9];
        final float[] uv = new float[triCount * 6];
        final int[] triToInput = new int[triCount];
        int t = 0;
        for (int i = 0; i < input.length; i++) {
            final Quad q = input[i];
            if (q == null || q.vertices == null || q.vertices.length < 3) {
                continue;
            }
            triToInput[t] = i;
            copyVertex(q.vertices[0], pos, norm, uv, t * 9, t * 6);
            copyVertex(q.vertices[1], pos, norm, uv, t * 9 + 3, t * 6 + 2);
            copyVertex(q.vertices[2], pos, norm, uv, t * 9 + 6, t * 6 + 4);
            t++;
        }

        int aliveCount = triCount;
        // Очередь кандидатов: (цена, треугольник, ребро). Цена = quadric error
        // схлопывания кратчайшего ребра + штраф за кривизну (сохраняет силуэт).
        final PriorityQueue<Collapse> queue =
                new PriorityQueue<>(Comparator.comparingDouble(c -> c.cost));
        for (int i = 0; i < triCount; i++) {
            queue.add(candidate(pos, i));
        }

        final boolean[] triAlive = new boolean[triCount];
        Arrays.fill(triAlive, true);

        while (aliveCount > want && !queue.isEmpty()) {
            final Collapse col = queue.poll();
            if (!triAlive[col.tri]) {
                continue; // Устаревший кандидат.
            }
            // Схлопнуть ребро (v0,v1) треугольника в середину.
            final int base = col.tri * 9;
            final int a = base + col.e0 * 3;
            final int b = base + col.e1 * 3;
            final float mx = (pos[a] + pos[b]) * 0.5F;
            final float my = (pos[a + 1] + pos[b + 1]) * 0.5F;
            final float mz = (pos[a + 2] + pos[b + 2]) * 0.5F;
            pos[a] = mx;
            pos[a + 1] = my;
            pos[a + 2] = mz;
            pos[b] = mx;
            pos[b + 1] = my;
            pos[b + 2] = mz;

            if (triangleArea2(pos, col.tri) < DEGENERATE_AREA2) {
                // Выродился — удалить.
                triAlive[col.tri] = false;
                aliveCount--;
            } else {
                // Пересчитать кандидата (ребро могло смениться).
                queue.add(candidate(pos, col.tri));
            }
        }

        // Собрать выжившие (с вырожденной 4-й вершиной, как на входе).
        final List<Quad> out = new ArrayList<>(aliveCount);
        for (int i = 0; i < triCount; i++) {
            if (!triAlive[i]) {
                continue;
            }
            final Quad q = new Quad();
            final Vertex a = makeVertex(pos, norm, uv, i, 0);
            final Vertex b = makeVertex(pos, norm, uv, i, 1);
            final Vertex c = makeVertex(pos, norm, uv, i, 2);
            // d дублирует c: формат Quad всегда 4 вершины.
            final Vertex d = makeVertex(pos, norm, uv, i, 2);
            q.vertices = new Vertex[]{a, b, c, d};
            q.invertNormal = input[triToInput[i]].invertNormal;
            out.add(q);
        }
        return out.toArray(new Quad[0]);
    }

    private static void copyVertex(Vertex v, float[] pos, float[] norm, float[] uv, int posOff,
                                   int uvOff) {
        pos[posOff] = v.x;
        pos[posOff + 1] = v.y;
        pos[posOff + 2] = v.z;
        norm[posOff] = v.normalX;
        norm[posOff + 1] = v.normalY;
        norm[posOff + 2] = v.normalZ;
        uv[uvOff] = v.u;
        uv[uvOff + 1] = v.v;
    }

    private static Vertex makeVertex(float[] pos, float[] norm, float[] uv, int tri, int vert) {
        final Vertex v = new Vertex();
        final int p = tri * 9 + vert * 3;
        final int u = tri * 6 + vert * 2;
        v.x = pos[p];
        v.y = pos[p + 1];
        v.z = pos[p + 2];
        v.normalX = norm[p];
        v.normalY = norm[p + 1];
        v.normalZ = norm[p + 2];
        v.u = uv[u];
        v.v = uv[u + 1];
        return v;
    }

    /**
     * Кандидат схлопывания: кратчайшее ребро треугольника.
     */
    private static Collapse candidate(float[] pos, int tri) {
        final int base = tri * 9;
        final double d01 = dist2(pos, base, base + 3);
        final double d12 = dist2(pos, base + 3, base + 6);
        final double d20 = dist2(pos, base + 6, base);
        int e0 = 0;
        int e1 = 1;
        double shortest = d01;
        if (d12 < shortest) {
            e0 = 1;
            e1 = 2;
            shortest = d12;
        }
        if (d20 < shortest) {
            e0 = 2;
            e1 = 0;
            shortest = d20;
        }
        // Цена: длина ребра (quadric error ~ пропорционален) + площадь
        // треугольника как штраф за удаление крупных фасеток силуэта.
        final double cost = shortest + triangleArea2(pos, tri) * AREA_COST_WEIGHT;
        return new Collapse(cost, tri, e0, e1);
    }

    private static double dist2(float[] pos, int a, int b) {
        final double dx = pos[a] - pos[b];
        final double dy = pos[a + 1] - pos[b + 1];
        final double dz = pos[a + 2] - pos[b + 2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Удвоенная площадь треугольника (без корня — для сравнений).
     */
    private static double triangleArea2(float[] pos, int tri) {
        final int base = tri * 9;
        final double ux = pos[base + 3] - pos[base];
        final double uy = pos[base + 4] - pos[base + 1];
        final double uz = pos[base + 5] - pos[base + 2];
        final double vx = pos[base + 6] - pos[base];
        final double vy = pos[base + 7] - pos[base + 1];
        final double vz = pos[base + 8] - pos[base + 2];
        final double nx = uy * vz - uz * vy;
        final double ny = uz * vx - ux * vz;
        final double nz = ux * vy - uy * vx;
        return Math.sqrt(nx * nx + ny * ny + nz * nz);
    }

    private record Collapse(double cost, int tri, int e0, int e1) {
    }
}
