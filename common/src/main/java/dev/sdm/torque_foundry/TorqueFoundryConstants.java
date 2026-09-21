package dev.sdm.torque_foundry;

/**
 * Константы и debug-флаги мода.
 *
 * <p>Debug-флаги читаются из JVM system properties
 * ({@code -Dtorque_foundry.dump_lods=true}), чтобы включать их только
 * в dev-окружении без пересборки. В проде все флаги по умолчанию выключены.
 */
public final class TorqueFoundryConstants {

    private TorqueFoundryConstants() {
    }

    /**
     * Дамп LOD-уровней glTF-моделей в {@code .glb} после загрузки и
     * догенерации ({@code -Dtorque_foundry.dump_lods=true}).
     *
     * <p>Файлы пишутся в {@code <gamedir>/torque_foundry_dump/<модель>_lod<N>.glb} —
     * их можно открыть в Blender и визуально проверить, что нагенерил
     * MeshSimplifier (и что LOD-файлы из assets вообще подхватились).
     * В проде выключено: лишний IO на загрузке ни к чему.
     */
    public static final boolean DUMP_LODS =
            Boolean.parseBoolean(System.getProperty("torque_foundry.dump_lods", "false"));

    /**
     * Каталог дампов относительно gameDir. Создаётся при первом дампе.
     */
    public static final String DUMP_DIR = "torque_foundry_dump";
}
