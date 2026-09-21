package dev.sdm.torque_foundry.core.client.render;

import dev.sdm.torque_foundry.core.client.render.LODModel.LODBox;
import dev.sdm.torque_foundry.core.client.render.LODModel.Part;
import dev.sdm.torque_foundry.core.client.render.structs.Quad;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Автогенерация LOD-уровней для узла {@link Part}.
 *
 * <p>Как работает: берёт боксы узла и прогрессивно сливает их
 * (по два ближайших за шаг) пока не останется заданное число;
 * последний уровень — один bounding-box (импостёр).
 * Каждый упрощённый уровень виден дальше предыдущего в 4 раза,
 * поэтому упрощённая геометрия замещает детальную на дистанции.
 *
 * <p>Вызывать после того, как все addBox добавлены:
 * {@code LODGenerator.generate(part);} — уровня 3 достаточно для боксовых моделей.
 */
public final class LODGenerator {

    /**
     * Детерминированный сид кластеризации: LOD стабильны между запусками.
     */
    private static final long CLUSTER_SEED = 0xC10DL;
    /**
     * Порог перехода жадный merge -> кластерный (малые наборы точнее жадным).
     */
    private static final int CLUSTER_BOX_THRESHOLD = 64;
    /**
     * Шаг порога видимости между соседними упрощёнными уровнями (sqr).
     */
    private static final double LEVEL_THRESHOLD_STEP = 4.0;

    private LODGenerator() {
    }

    /**
     * Генерирует уровни: оригинал + слияния + импостёр.
     */
    public static void generate(Part part) {
        generate(part, 2);
    }

    /**
     * @param simplificationLevels сколько упрощённых уровней добавить
     *                             (1 — один слитый, 2 — слитый + импостёр, ...)
     */
    public static void generate(Part part, int simplificationLevels) {
        if (part.boxes().isEmpty()) {
            return;
        }

        final LODBox[] original = part.boxes().toArray(new LODBox[0]);
        final double baseThreshold = Part.distanceForVolume(maxVolume(original));

        final List<Part.LODLevel> levels = new ArrayList<>(simplificationLevels + 1);
        levels.add(new Part.LODLevel(original, baseThreshold));

        LODBox[] current = original;
        double threshold = baseThreshold;
        for (int level = 1; level <= simplificationLevels; level++) {
            final int target = Math.max(1, (int) Math.ceil(original.length / Math.pow(2, level)));
            current = mergeBoxes(current, target);
            threshold *= LEVEL_THRESHOLD_STEP;
            levels.add(new Part.LODLevel(current, threshold));
        }

        part.setLODLevels(levels);
    }

    // --- glTF-меши: догенерация недостающих LOD из LOD0 ---

    /**
     * Сколько всего LOD-уровней держать у части (LOD0 + упрощённые).
     */
    public static final int MESH_LOD_COUNT = 3;

    /**
     * Добивает LOD-уровни glTF-части до {@link #MESH_LOD_COUNT}: каждый
     * следующий уровень — половина треугольников предыдущего через
     * edge-collapse с quadric error. Файлы LOD1/LOD2 из Blender имеют
     * приоритет: этот метод вызывается только для недостающих.
     *
     * <p>Работает на загрузке модели (не в кадре). Сложность ~O(n log n).
     *
     * @param lods существующие уровни (lods[0] = LOD0 из файла)
     * @return список ровно MESH_LOD_COUNT уровней
     */
    public static List<Quad[]> completeMeshLods(List<Quad[]> lods) {
        final List<Quad[]> out = new ArrayList<>(lods);
        while (out.size() < MESH_LOD_COUNT) {
            final Quad[] prev = out.get(out.size() - 1);
            // Вырожденный меш (< 2 квадов) дальше не упростить — дублируем.
            if (prev.length < 2) {
                out.add(prev);
                continue;
            }
            final int target = Math.max(1, prev.length / 2);
            out.add(MeshSimplifier.simplify(prev, target));
        }
        return out;
    }

    /**
     * Грейди merge: на каждом шаге объединяет пару боксов
     * с минимальной дистанцией между центрами.
     *
     * <p>Лобовой вариант — O(n^2) на шаг. Здесь: малые наборы — точный
     * жадный merge, большие — кластерный (k-means++ сиды + один проход
     * nearest-centroid, ~O(n · k)). Детерминирован (фиксированный сид).
     */
    private static LODBox[] mergeBoxes(LODBox[] input, int targetCount) {
        if (input.length <= targetCount) {
            return input;
        }
        if (input.length < CLUSTER_BOX_THRESHOLD || targetCount < 2) {
            return mergeBoxesGreedy(input, targetCount);
        }
        return mergeBoxesClustered(input, targetCount);
    }

    /**
     * Точный жадный merge (старое поведение, для малых наборов).
     */
    private static LODBox[] mergeBoxesGreedy(LODBox[] input, int targetCount) {
        final List<LODBox> current = new ArrayList<>(List.of(input));
        while (current.size() > targetCount) {
            int bestA = -1;
            int bestB = -1;
            double bestDist = Double.MAX_VALUE;

            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    final double dist = centerDistanceSqr(current.get(i), current.get(j));
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestA = i;
                        bestB = j;
                    }
                }
            }

            current.set(bestA, LODBox.union(current.get(bestA), current.get(bestB)));
            current.remove(bestB);
        }

        return current.toArray(new LODBox[0]);
    }

    /**
     * Кластерный merge: k-means++ инициализация центроидов, один проход
     * привязки боксов к ближайшему центроиду, union внутри кластера.
     */
    private static LODBox[] mergeBoxesClustered(LODBox[] input, int targetCount) {
        final Random random = new Random(CLUSTER_SEED);
        final int n = input.length;
        final double[][] centroids = new double[targetCount][3];
        final boolean[] isSeed = new boolean[n];

        final int first = random.nextInt(n);
        isSeed[first] = true;
        centroids[0] = centerOf(input[first]);

        final double[] minDist = new double[n];
        for (int i = 0; i < n; i++) {
            minDist[i] = centerDistanceSqr(input[i], input[first]);
        }
        int seeds = 1;
        while (seeds < targetCount) {
            double total = 0;
            for (int i = 0; i < n; i++) {
                if (!isSeed[i]) {
                    total += minDist[i];
                }
            }
            if (total <= 0) {
                break;
            }
            double pick = random.nextDouble() * total;
            int chosen = -1;
            for (int i = 0; i < n; i++) {
                if (isSeed[i]) {
                    continue;
                }
                pick -= minDist[i];
                if (pick <= 0) {
                    chosen = i;
                    break;
                }
            }
            if (chosen < 0) {
                break;
            }
            isSeed[chosen] = true;
            centroids[seeds] = centerOf(input[chosen]);
            seeds++;
            for (int i = 0; i < n; i++) {
                if (!isSeed[i]) {
                    final double d = distToCentroid(input[i], centroids[seeds - 1]);
                    if (d < minDist[i]) {
                        minDist[i] = d;
                    }
                }
            }
        }

        // Привязка: каждый бокс — к ближайшему центроиду, union в кластере.
        final LODBox[] acc = new LODBox[seeds];
        final boolean[] filled = new boolean[seeds];
        for (int i = 0; i < n; i++) {
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int c = 0; c < seeds; c++) {
                final double d = distToCentroid(input[i], centroids[c]);
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
            if (!filled[best]) {
                acc[best] = input[i];
                filled[best] = true;
            } else {
                acc[best] = LODBox.union(acc[best], input[i]);
            }
        }

        final List<LODBox> out = new ArrayList<>(seeds);
        for (int c = 0; c < seeds; c++) {
            if (filled[c]) {
                out.add(acc[c]);
            }
        }
        return out.toArray(new LODBox[0]);
    }

    private static double[] centerOf(LODBox box) {
        return new double[]{box.x + box.w / 2.0, box.y + box.h / 2.0, box.z + box.d / 2.0};
    }

    private static double distToCentroid(LODBox box, double[] centroid) {
        final double dx = box.x + box.w / 2.0 - centroid[0];
        final double dy = box.y + box.h / 2.0 - centroid[1];
        final double dz = box.z + box.d / 2.0 - centroid[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double centerDistanceSqr(LODBox a, LODBox b) {
        final double ax = a.x + a.w / 2.0;
        final double ay = a.y + a.h / 2.0;
        final double az = a.z + a.d / 2.0;
        final double bx = b.x + b.w / 2.0;
        final double by = b.y + b.h / 2.0;
        final double bz = b.z + b.d / 2.0;
        final double dx = ax - bx;
        final double dy = ay - by;
        final double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double maxVolume(LODBox[] boxes) {
        double max = 0;
        for (LODBox box : boxes) {
            final double volume = box.volume();
            if (volume > max) {
                max = volume;
            }
        }
        return max;
    }
}
