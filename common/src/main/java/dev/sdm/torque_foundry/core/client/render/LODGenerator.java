package dev.sdm.torque_foundry.core.client.render;

import dev.sdm.torque_foundry.core.client.render.LODModel.LODBox;
import dev.sdm.torque_foundry.core.client.render.LODModel.Part;

import java.util.ArrayList;
import java.util.List;

/**
 * Автогенерация LOD-уровней для узла {@link Part}.
 *
 * Как работает: берёт боксы узла и прогрессивно сливает их
 * (по два ближайших за шаг) пока не останется заданное число;
 * последний уровень — один bounding-box (импостёр).
 * Каждый упрощённый уровень виден дальше предыдущего в 4 раза,
 * поэтому упрощённая геометрия замещает детальную на дистанции.
 *
 * Вызывать после того, как все addBox добавлены:
 * {@code LODGenerator.generate(part);} — уровня 3 достаточно для боксовых моделей.
 */
public final class LODGenerator {

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

        final List<Part.LODLevel> levels = new ArrayList<>();
        levels.add(new Part.LODLevel(original, baseThreshold));

        LODBox[] current = original;
        for (int level = 1; level <= simplificationLevels; level++) {
            final int target = Math.max(1, (int) Math.ceil(original.length / Math.pow(2, level)));
            current = mergeBoxes(current, target);
            levels.add(new Part.LODLevel(current, baseThreshold * Math.pow(4, level)));
        }

        part.setLODLevels(levels);
    }

    /**
     * Грейди merge: на каждом шаге объединяет пару боксов
     * с минимальной дистанцией между центрами.
     */
    private static LODBox[] mergeBoxes(LODBox[] input, int targetCount) {
        if (input.length <= targetCount) {
            return input;
        }

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
