package dev.sdm.torque_foundry.core.client.render;

import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.physics.material.PhysicsMaterial;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/**
 * Разрешение текстур частей модели по материалу.
 *
 * <p>Модель блока (Corp, Val, ...) рендерится текстурой материала,
 * заданного при крафте: железо — текстура железа, дерево — досок и т.д.
 *
 * <p>Приоритет для части {@code Val} с материалом {@code IRON}:
 * <ol>
 *   <li>своя текстура мода {@code textures/block/parts/val_iron.png};</li>
 *   <li>своя статическая {@code textures/block/parts/val.png} (материал-независимая
 *       часть: стёкла, ручки);</li>
 *   <li>ванильная текстура материала ({@link #VANILLA_BY_MATERIAL}).</li>
 * </ol>
 *
 * <p>Резолв кэшируется (пара имя+материал — один lookup в ресурсах за сессию).
 * Потокобезопасность: вызовы только из render-треда, ConcurrentHashMap на всякий.
 */
public final class PartTextures {

    /**
     * Ванильные текстуры по имени PhysicsMaterial (lowercase).
     * Модовые текстуры в parts/ перекрывают их; сюда добавляются новые
     * материалы (diamond, gold, ...) одной строкой.
     */
    private static final Map<String, String> VANILLA_BY_MATERIAL = Map.ofEntries(
            Map.entry("wood", "oak_planks"),
            Map.entry("bronze", "copper_block"),
            Map.entry("iron", "iron_block"),
            Map.entry("castiron", "iron_block"),
            Map.entry("steel", "iron_block"),
            Map.entry("diamond", "diamond_block"),
            Map.entry("gold", "gold_block"),
            Map.entry("stone", "stone"),
            Map.entry("deepslate", "deepslate"),
            Map.entry("netherite", "netherite_block"));

    /**
     * Кэш резолва: "part|material" -> текстура.
     */
    private static final Map<String, ResourceLocation> CACHE = new ConcurrentHashMap<>();

    private PartTextures() {
    }

    /**
     * Текстура части с материалом. Никогда не null: крайний случай —
     * ванильное железо.
     *
     * @param partName имя части из Blender (Corp, Val; регистр не важен)
     * @param material материал машины (крафт/Shift+ПКМ)
     */
    public static ResourceLocation resolve(String partName, PhysicsMaterial material) {
        // Ключ строим из нормализованных имён: "Val"/"val" и "IRON"/"iron" —
        // один кэш-слот вместо двух, плюс computeIfAbsent не дёргает
        // resolveUncached дважды на гонке кейсов.
        final String partKey = partName.toLowerCase(Locale.ROOT);
        final String matKey = material.name().toLowerCase(Locale.ROOT);
        final String key = partKey + "|" + matKey;
        return CACHE.computeIfAbsent(key, k -> resolveUncached(partKey, matKey, material));
    }

    private static ResourceLocation resolveUncached(String partKey, String matKey,
                                                    PhysicsMaterial material) {
        // Имена мешей приходят из Blender как есть — там легко получить
        // кириллицу ("Сorp" вместо "Corp"), а ResourceLocation такое не ест
        // (только [a-z0-9/._-]). Чистим сегменты, о подмене — варнинг в лог.
        // partKey/matKey уже в lower-case из resolve(): sanitize без повторного
        // toLowerCase, original для лога — исходник до нормализации.
        final String part = sanitize(partKey, "part", partKey);
        final String mat = sanitize(matKey, "material", material.name());

        // 1. Своя текстура части + материала.
        final ResourceLocation ownMat = modTexture("parts/" + part + "_" + mat);
        if (exists(ownMat)) {
            return ownMat;
        }
        // 2. Своя статическая текстура части.
        final ResourceLocation ownPlain = modTexture("parts/" + part);
        if (exists(ownPlain)) {
            return ownPlain;
        }
        // 3. Ванильная по материалу.
        return vanillaTexture(material);
    }

    private static ResourceLocation modTexture(String path) {
        return ResourceLocation.fromNamespaceAndPath(TorqueFoundry.MOD_ID,
                "textures/block/" + path + ".png");
    }

    /**
     * Чистит сегмент пути до алфавита ResourceLocation [a-z0-9/._-].
     * При подмене пишет варнинг с исходником — иначе кириллицу в имени
     * меша из Blender не найти (падает ResourceLocationException в рендере).
     */
    private static String sanitize(String value, String kind, String original) {
        final StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
                    || c == '/' || c == '.' || c == '_' || c == '-') {
                clean.append(c);
            }
        }
        final String result = clean.toString();
        if (!result.equals(value)) {
            TorqueFoundry.LOGGER.warn(
                    "[TF-render] {} name '{}' has non-resource characters, using '{}'"
                            + " — rename the mesh in Blender",
                    kind, original, result);
        }
        return result.isEmpty() ? "part" : result;
    }

    /**
     * Есть ли ресурс в менеджере (клиент).
     */
    private static boolean exists(ResourceLocation loc) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getResourceManager() == null) {
            return false;
        }
        return minecraft.getResourceManager().getResource(loc).isPresent();
    }

    /**
     * Ванильная текстура материала; неизвестный материал -> железо.
     */
    public static ResourceLocation vanillaTexture(PhysicsMaterial material) {
        final String block = VANILLA_BY_MATERIAL.getOrDefault(
                material.name().toLowerCase(Locale.ROOT), "iron_block");
        return ResourceLocation.fromNamespaceAndPath("minecraft",
                "textures/block/" + block + ".png");
    }
}
