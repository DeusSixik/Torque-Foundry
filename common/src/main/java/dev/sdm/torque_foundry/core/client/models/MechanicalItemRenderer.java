package dev.sdm.torque_foundry.core.client.models;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.math.MatrixUtil;
import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.block.CaseBlockEntity;
import dev.sdm.torque_foundry.core.block.ShaftBlockEntity;
import dev.sdm.torque_foundry.core.block.TFBlocks;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Рендер BER-модели механического блока в руке/инвентаре/рамке.
 *
 * <p>Идея: вместо статичной JSON-модельки айтема рисуем ту же glTF-модель,
 * что и в мире — через уже зарегистрированный BER. Для этого создаём
 * фейковый BlockEntity (без уровня, только позиция + дефолтный стейт)
 * и отдаём его диспетчеру: {@code ShaftRenderer}/{@code CaseRenderer}
 * рисуют как обычно (угол 0, материал по умолчанию, корпус без вставки).
 *
 * <p>Позиция фейкового BE = блок камеры: дистанция до камеры ~0, поэтому
 * LOD всегда детальный (LOD0) и детерминированный. Кэш пересоздаётся
 * только при смене блока камеры — ноль аллокаций на кадр.
 *
 * <p>Оверлей портов в руке не рисуется: {@code PortFaceOverlay.shouldRender}
 * требует прицел ровно на позицию BE, а это блок камеры — совпадение
 * возможно лишь если засунуть голову в блок.
 *
 * <p>Только клиент: класс грузится исключительно из клиентских
 * точек входа платформ, на сервере не трогать.
 */
public final class MechanicalItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static final Reference2ObjectArrayMap<Item, BlockEntity> OBJECT_CACHE
            = new Reference2ObjectArrayMap<>();

    private static final Map<Item, Supplier<? extends BlockEntity>> FACTORIES = new LinkedHashMap<>();
    private static MechanicalItemRenderer instance;

    /**
     * Кэш фейковых BE: Item -> BE в блоке камеры.
     */
    private final Map<Item, BlockEntity> fakeBes = new HashMap<>();
    private BlockPos lastCamPos;

    private MechanicalItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    /**
     * Синглтон; создаётся лениво при первом рендере — диспетчер точно жив.
     */
    public static synchronized MechanicalItemRenderer get() {
        if (instance == null) {
            instance = new MechanicalItemRenderer();
        }
        return instance;
    }

    /**
     * Привязки по умолчанию: вал — полный, корпус — пустой (только Casing).
     */
    public static void registerDefaults() {
        FACTORIES.put(TFBlocks.SHAFT_ITEM.get(),
                () -> new ShaftBlockEntity(BlockPos.ZERO, TFBlocks.SHAFT.get().defaultBlockState()));
        FACTORIES.put(TFBlocks.CASE_ITEM.get(),
                () -> new CaseBlockEntity(BlockPos.ZERO, TFBlocks.CASE.get().defaultBlockState()));
    }

    /**
     * Айтемы с кастомным рендером — для регистрации на платформах.
     */
    public static Set<Item> registeredItems() {
        return FACTORIES.keySet();
    }

    /**
     * Стандартные display-трансформы ванильного блока (из
     * {@code minecraft:models/block/block.json}): rotation в градусах,
     * translation в пикселях модели (/16), scale. У {@code builtin/entity}
     * display-секции нет, поэтому ванилла ничего не накладывает —
     * воспроизводим вручную, чтобы предмет лежал в руке/инвентаре
     * ровно как обычный блок.
     */
    private record DisplayT(float rx, float ry, float rz, float tx, float ty, float tz, float s) {
    }

    private static final Map<ItemDisplayContext, DisplayT> BLOCK_DISPLAY = Map.of(
            ItemDisplayContext.GUI, new DisplayT(30, 225, 0, 0, 0, 0, 0.625f),
            ItemDisplayContext.GROUND, new DisplayT(0, 0, 0, 0, 3, 0, 0.25f),
            ItemDisplayContext.FIXED, new DisplayT(0, 0, 0, 0, 0, 0, 0.5f),
            ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, new DisplayT(75, 45, 0, 0, 2.5f, 0, 0.375f),
            ItemDisplayContext.THIRD_PERSON_LEFT_HAND, new DisplayT(75, 45, 0, 0, 2.5f, 0, 0.375f),
            ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, new DisplayT(0, 45, 0, 0, 0, 0, 0.4f),
            ItemDisplayContext.FIRST_PERSON_LEFT_HAND, new DisplayT(0, 225, 0, 0, 0, 0, 0.4f));

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext mode, PoseStack poseStack,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        final Minecraft mc = Minecraft.getInstance();
        final @Nullable GameRenderer gameRenderer = mc.gameRenderer;

        if (gameRenderer == null || gameRenderer.getMainCamera() == null) {
            return;
        }

        final BlockEntity be = getModelEntity(stack);
        if(be == null) {
            return;
        }

        final BlockEntityRenderer<BlockEntity> ber =
                mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (ber == null) {
            return;
        }

        // Ванилла уже сдвинула origin на (-0.5,-0.5,-0.5) перед renderByItem;
        // у builtin/entity нет display-секции — стандартный поворот блока
        // (как у обычных кубов) накладываем сами, иначе предмет висит
        // плашмя без наклона и отцентровки как у ванильных блоков.
        final DisplayT display = BLOCK_DISPLAY.get(mode);
        poseStack.pushPose();
        if (display != null) {
            poseStack.translate(0.5, 0.5, 0.5);
            poseStack.mulPose(Axis.XP.rotationDegrees(display.rx()));
            poseStack.mulPose(Axis.YP.rotationDegrees(display.ry()));
            poseStack.mulPose(Axis.ZP.rotationDegrees(display.rz()));
            poseStack.translate(display.tx() / 16.0, display.ty() / 16.0, display.tz() / 16.0);
            poseStack.scale(display.s(), display.s(), display.s());
            poseStack.translate(-0.5, -0.5, -0.5);
        }
        ber.render(be, 0.0f, poseStack, buffers, packedLight, packedOverlay);
        poseStack.popPose();
    }

    @Nullable
    private static BlockEntity getModelEntity(ItemStack stack) {
        final Item item = stack.getItem();
        final BlockEntity be = OBJECT_CACHE.get(item);
        if(be != null) {
            return be;
        }

        final Supplier<? extends BlockEntity> factory = FACTORIES.get(item);
        if(factory == null) {
            return null;
        }

        final BlockEntity createdBe = factory.get();
        OBJECT_CACHE.put(item, createdBe);
        return createdBe;
    }
}
