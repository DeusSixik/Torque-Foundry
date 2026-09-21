package dev.sdm.torque_foundry.core.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Подсветка сторон подключения (портов) механических блоков — порт
 * RotaryCraft renderFaceColors: выступ-кубик INPUT заливается зелёным,
 * OUTPUT — красным, проходной IN_OUT — жёлтым. Поверх — контур рёбер
 * и луч от центра к грани, как в оригинале.
 *
 * <p>Кубик торчит из грани наружу (глубина {@link #PLUG_DEPTH}, сечение
 * {@link #PLUG_SIZE}): направление порта читается объёмом, а не плоской
 * заливкой. Показывается только когда игрок целится в механический блок
 * И держит в руке предмет мода — иначе оверлей скрыт.
 *
 * <p>Рисуется в BER поверх модели: полупрозрачный объём чуть вынесен от грани
 * (z-fighting нет), контур — RenderType.lines(). Ноль аллокаций на кадр
 * кроме буферов ванильного батчинга.
 */
public final class PortFaceOverlay {

    /**
     * Вынос заливки от грани блока (против z-fighting с моделью).
     */
    private static final float FACE_EPS = 0.002F;

    /**
     * Вынос рамки-контура (чуть дальше заливки).
     */
    private static final float LINE_EPS = 0.004F;

    /**
     * Глубина выступа-кубика наружу от грани (в блоках).
     */
    private static final float PLUG_DEPTH = 0.25F;

    /**
     * Сечение выступа-кубика (доля грани, центрирован).
     */
    private static final float PLUG_SIZE = 0.5F;

    /**
     * Порядок Direction.values() фиксирован (DOWN UP NORTH SOUTH WEST EAST),
     * но держим свой массив: values() клонирует массив при каждом вызове.
     */
    private static final Direction[] DIRS = {Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};

    // --- Цвета портов (RotaryCraft): IN — зелёный, OUT — красный, IN_OUT — жёлтый ---

    /**
     * Бит стороны: 1 = IN, 2 = OUT (комбинация 3 = IN_OUT). 0 = глухая.
     */
    private static final int SIDE_IN = 1;
    private static final int SIDE_OUT = 2;

    /**
     * Цвет по битам [r, g, b], индекс = комбинация SIDE_*.
     */
    private static final float[][] SIDE_COLORS = {null, // NONE — не рисуется
            {0.15F, 1.0F, 0.25F}, // IN — зелёный
            {1.0F, 0.2F, 0.2F}, // OUT — красный
            {1.0F, 0.85F, 0.1F}, // IN_OUT — жёлтый
    };

    /**
     * Сколько тиков после установки светятся порты (5 секунд).
     */
    private static final long PLACEMENT_GLOW_TICKS = 100L;

    /**
     * Лимит карты свежепоставленных: выше — чистим протухшие за один проход.
     */
    private static final int PLACED_AT_MAX_SIZE = 512;

    /**
     * Пульсация яркости: база + амплитуда синуса от времени клиента.
     */
    private static final float PULSE_BASE = 0.55F;
    private static final float PULSE_AMPLITUDE = 0.45F;
    /**
     * Период пульсации в тиках (~1.5 c).
     */
    private static final double PULSE_PERIOD_TICKS = 30.0;
    /**
     * Вклад свечения установки в итоговую яркость.
     */
    private static final float GLOW_BRIGHTNESS = 0.45F;
    /**
     * Альфа заливки выступа (0..255 до умножения на яркость).
     */
    private static final int FILL_ALPHA = 90;

    /**
     * pos.asLong() -> тик установки (игровое время клиента).
     */
    private static final Long2LongOpenHashMap PLACED_AT = new Long2LongOpenHashMap();

    static {
        PLACED_AT.defaultReturnValue(-1L);
    }

    // Scratch для границ выступа [x0, y0, z0, x1, y1, z1]: plugBounds пишет
    // сюда вместо new float[6] — ноль аллокаций в кадре. Только render-тред.
    private static final float[] PLUG_BOUNDS_SCRATCH = new float[6];

    private PortFaceOverlay() {
    }

    /**
     * Отметить блок свежепоставленным: порты светятся PLACEMENT_GLOW_TICKS
     * тиков без прицела и предмета в руке. Вызывать из setPlacedBy блоков.
     */
    public static void markPlaced(BlockPos pos) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        PLACED_AT.put(pos.asLong(), minecraft.level.getGameTime());
        // Карта живёт на клиенте: чистим протухшие записи при каждой установке.
        if (PLACED_AT.size() > PLACED_AT_MAX_SIZE) {
            final long now = minecraft.level.getGameTime();
            final long[] keys = PLACED_AT.keySet().toLongArray();
            for (long key : keys) {
                if (now - PLACED_AT.get(key) > PLACEMENT_GLOW_TICKS) {
                    PLACED_AT.remove(key);
                }
            }
        }
    }

    /**
     * Доля 1..0 свечения после установки (1 — только поставили, 0 — погас).
     * 0 — не свежепоставленный.
     */
    public static float placementGlow(BlockPos pos) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return 0.0F;
        }
        final long placedAt = PLACED_AT.get(pos.asLong());
        if (placedAt < 0) {
            return 0.0F;
        }
        final long age = minecraft.level.getGameTime() - placedAt;
        if (age < 0 || age >= PLACEMENT_GLOW_TICKS) {
            PLACED_AT.remove(pos.asLong());
            return 0.0F;
        }
        return 1.0F - age / (float) PLACEMENT_GLOW_TICKS;
    }

    /**
     * Рисовать ли оверлей для этого BE: прицел на этот блок + предмет
     * мода в любой руке, ИЛИ блок только что поставили (свечение гаснет
     * само через PLACEMENT_GLOW_TICKS тиков).
     */
    public static boolean shouldRender(MechanicalBlockEntity be) {
        if (placementGlow(be.getBlockPos()) > 0.0F) {
            return true;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.hitResult == null) {
            return false;
        }
        if (minecraft.hitResult.getType() != HitResult.Type.BLOCK
                || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return false;
        }
        if (!hit.getBlockPos().equals(be.getBlockPos())) {
            return false;
        }
        return holdsModItem(minecraft.player.getMainHandItem())
                || holdsModItem(minecraft.player.getOffhandItem());
    }

    private static boolean holdsModItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        // Любой предмет мода: id из нашего namespace — новые блоки/инструменты
        // подхватываются автоматически, список править не надо.
        final ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && TorqueFoundry.MOD_ID.equals(key.getNamespace());
    }

    /**
     * Рендер всех портов машины. Пульсация альфы — как iotick в оригинале:
     * яркость дышит от времени клиента.
     *
     * @param poseStack    локальный стек BER (0..1 координаты блока)
     * @param bufferSource буферы кадра
     * @param packedLight  освещение (для заливки берём fullbright — оверлей
     *                     должен читаться и в темноте)
     */
    public static void render(MechanicalMachine machine, PoseStack poseStack,
                              MultiBufferSource bufferSource, int packedLight) {
        render(machine, machine.getBlockPos(), poseStack, bufferSource, packedLight);
    }

    /**
     * Рендер всех портов машины. Пульсация альфы — как iotick в оригинале:
     * яркость дышит от времени клиента; свежепоставленный блок дополнительно
     * гаснет от 1 к 0 за PLACEMENT_GLOW_TICKS (множитель placementGlow).
     */
    public static void render(MechanicalMachine machine, BlockPos pos, PoseStack poseStack,
                              MultiBufferSource bufferSource, int packedLight) {
        // Пульсация 0.55..1.0, период ~1.5 c — дышит, как iotick-альфа RC.
        final float pulse = PULSE_BASE + PULSE_AMPLITUDE
                * (float) (0.5 + 0.5 * Math.sin(
                Minecraft.getInstance().level.getGameTime() * 2.0 * Math.PI / PULSE_PERIOD_TICKS));
        // Свечение установки: яркий старт, линейное затухание. Без прицела
        // (обычная установка) — просто pulse, с прицелом свечение не мешает.
        final float glow = pos == null ? 0.0F : placementGlow(pos);
        final float brightness = Math.min(1.0F, pulse + glow * GLOW_BRIGHTNESS);

        // Биты сторон собираем одним проходом (без аллокаций), дальше два
        // прохода по статике: объём выступа + контур рёбер.
        final int sides = sideBitsOf(machine);

        final VertexConsumer fill = bufferSource.getBuffer(RenderType.debugQuads());
        final float fillA = (int) (FILL_ALPHA * brightness) / 255.0F;
        final PoseStack.Pose pose = poseStack.last();
        for (int d = 0; d < 6; d++) {
            final int side = (sides >>> (d * 2)) & 3;
            if (side == 0) {
                continue;
            }
            final Direction dir = DIRS[d];
            final float[] rgb = SIDE_COLORS[side];
            emitPlug(dir, pose, fill, rgb[0], rgb[1], rgb[2], fillA);
        }

        final VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());
        final float lineA = (int) (255 * brightness) / 255.0F;
        for (int d = 0; d < 6; d++) {
            final int side = (sides >>> (d * 2)) & 3;
            if (side == 0) {
                continue;
            }
            final Direction dir = DIRS[d];
            final float[] rgb = SIDE_COLORS[side];
            emitPlugEdges(dir, pose, lines, rgb[0], rgb[1], rgb[2], lineA);
            // Луч от центра блока к центру выступа (GL_LINES в оригинале).
            final float nx = dir.getStepX();
            final float ny = dir.getStepY();
            final float nz = dir.getStepZ();
            lines.addVertex(pose, 0.5F, 0.5F, 0.5F).setColor(rgb[0], rgb[1], rgb[2], lineA).setNormal(pose,
                    nx, ny, nz);
            lines.addVertex(pose, 0.5F + nx * (0.5F + PLUG_DEPTH), 0.5F + ny * (0.5F + PLUG_DEPTH),
                    0.5F + nz * (0.5F + PLUG_DEPTH)).setColor(rgb[0], rgb[1], rgb[2], lineA).setNormal(pose,
                    nx, ny, nz);
        }
    }

    /**
     * Объём выступа-кубика: 6 граней (POSITION_COLOR, без нормали).
     * Кубик строится от плоскости грани наружу: near = грань + EPS,
     * far = грань + EPS + DEPTH по направлению нормали.
     */
    private static void emitPlug(Direction dir, PoseStack.Pose pose, VertexConsumer fill, float r,
                                 float g, float b, float a) {
        plugBounds(dir, FACE_EPS, PLUG_BOUNDS_SCRATCH);
        emitBox(pose, fill, PLUG_BOUNDS_SCRATCH[0], PLUG_BOUNDS_SCRATCH[1], PLUG_BOUNDS_SCRATCH[2],
                PLUG_BOUNDS_SCRATCH[3], PLUG_BOUNDS_SCRATCH[4], PLUG_BOUNDS_SCRATCH[5], r, g, b, a);
    }

    /**
     * Заливка бокса 6 гранями, CCW наружу (POSITION_COLOR).
     */
    private static void emitBox(PoseStack.Pose pose, VertexConsumer fill, float x0, float y0,
                                float z0, float x1, float y1, float z1, float r, float g, float b, float a) {
        // DOWN (-Y).
        quad(fill, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        // UP (+Y).
        quad(fill, pose, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        // NORTH (-Z).
        quad(fill, pose, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        // SOUTH (+Z).
        quad(fill, pose, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
        // WEST (-X).
        quad(fill, pose, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, r, g, b, a);
        // EAST (+X).
        quad(fill, pose, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    private static void quad(VertexConsumer fill, PoseStack.Pose pose, float ax, float ay, float az,
                             float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz,
                             float r, float g, float b, float a) {
        fill.addVertex(pose, ax, ay, az).setColor(r, g, b, a);
        fill.addVertex(pose, bx, by, bz).setColor(r, g, b, a);
        fill.addVertex(pose, cx, cy, cz).setColor(r, g, b, a);
        fill.addVertex(pose, dx, dy, dz).setColor(r, g, b, a);
    }

    /**
     * Рёбра выступа-кубика: 12 рёбер бокса (POSITION_COLOR_NORMAL).
     */
    private static void emitPlugEdges(Direction dir, PoseStack.Pose pose, VertexConsumer lines,
                                      float r, float g, float b, float a) {
        final float nx = dir.getStepX();
        final float ny = dir.getStepY();
        final float nz = dir.getStepZ();
        plugBounds(dir, LINE_EPS, PLUG_BOUNDS_SCRATCH);
        final float x0 = PLUG_BOUNDS_SCRATCH[0];
        final float y0 = PLUG_BOUNDS_SCRATCH[1];
        final float z0 = PLUG_BOUNDS_SCRATCH[2];
        final float x1 = PLUG_BOUNDS_SCRATCH[3];
        final float y1 = PLUG_BOUNDS_SCRATCH[4];
        final float z1 = PLUG_BOUNDS_SCRATCH[5];

        // 8 вершин бокса.
        // Рёбра вдоль X (4).
        edge(lines, pose, x0, y0, z0, x1, y0, z0, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x0, y1, z0, x1, y1, z0, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x0, y0, z1, x1, y0, z1, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x0, y1, z1, x1, y1, z1, r, g, b, a, nx, ny, nz);
        // Рёбра вдоль Y (4).
        edge(lines, pose, x0, y0, z0, x0, y1, z0, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x1, y0, z0, x1, y1, z0, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x0, y0, z1, x0, y1, z1, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x1, y0, z1, x1, y1, z1, r, g, b, a, nx, ny, nz);
        // Рёбра вдоль Z (4).
        edge(lines, pose, x0, y0, z0, x0, y0, z1, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x1, y0, z0, x1, y0, z1, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x0, y1, z0, x0, y1, z1, r, g, b, a, nx, ny, nz);
        edge(lines, pose, x1, y1, z0, x1, y1, z1, r, g, b, a, nx, ny, nz);
    }

    private static void edge(VertexConsumer lines, PoseStack.Pose pose, float ax, float ay, float az,
                             float bx, float by, float bz, float r, float g, float b, float a, float nx, float ny,
                             float nz) {
        lines.addVertex(pose, ax, ay, az).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, bx, by, bz).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
    }

    /**
     * Границы выступа-кубика [x0, y0, z0, x1, y1, z1] в out: от плоскости грани
     * (со сдвигом eps против z-fighting) наружу на PLUG_DEPTH, сечение —
     * центрированный квадрат PLUG_SIZE. Общий для заливки и контура:
     * расходятся только величиной eps.
     *
     * <p>Out-параметр вместо возврата массива: метод вызывается на каждый порт
     * в кадре, аллокация float[6] на вызов — мусор для GC каждый тик рендера.
     */
    private static void plugBounds(Direction dir, float eps, float[] out) {
        final float nx = dir.getStepX();
        final float ny = dir.getStepY();
        final float nz = dir.getStepZ();
        final float half = PLUG_SIZE * 0.5F;

        // Плоскость грани: 1 для +оси, 0 для -оси.
        final float plane = (nx + ny + nz) > 0 ? 1.0F : 0.0F;
        final float near = plane + (plane > 0.5F ? eps : -eps);
        final float far = near + (nx + ny + nz) * PLUG_DEPTH;

        // min/max упорядочены: lo = меньшее, hi = большее.
        final float lo = Math.min(near, far);
        final float hi = Math.max(near, far);

        if (nx != 0) {
            out[0] = lo;
            out[1] = 0.5F - half;
            out[2] = 0.5F - half;
            out[3] = hi;
            out[4] = 0.5F + half;
            out[5] = 0.5F + half;
            return;
        }
        if (ny != 0) {
            out[0] = 0.5F - half;
            out[1] = lo;
            out[2] = 0.5F - half;
            out[3] = 0.5F + half;
            out[4] = hi;
            out[5] = 0.5F + half;
            return;
        }
        out[0] = 0.5F - half;
        out[1] = 0.5F - half;
        out[2] = lo;
        out[3] = 0.5F + half;
        out[4] = 0.5F + half;
        out[5] = hi;
    }

    /**
     * Биты сторон одним числом: 2 бита на грань (порядок DIRS =
     * ordinal Direction). 0 = глухая, 1 = IN, 2 = OUT, 3 = IN_OUT.
     */
    private static int sideBitsOf(MechanicalMachine machine) {
        int sides = 0;
        for (int d = 0; d < 6; d++) {
            final Direction dir = DIRS[d];
            int side = 0;
            if (machine.isInputSide(dir)) {
                side |= SIDE_IN;
            }
            if (machine.isOutputSide(dir)) {
                side |= SIDE_OUT;
            }
            sides |= side << (d * 2);
        }
        return sides;
    }

    // --- Форматы вершин: fill = debugQuads() = POSITION_COLOR (без нормали);
    // lines = lines() = POSITION_COLOR_NORMAL. Буферы берутся по очереди
    // (весь fill, потом весь lines): shared-BufferSource переключает тип через endBatch.
}
