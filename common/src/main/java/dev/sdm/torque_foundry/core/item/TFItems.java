package dev.sdm.torque_foundry.core.item;

import dev.architectury.registry.registries.RegistrySupplier;
import dev.sdm.torque_foundry.core.block.TFBlocks;
import dev.sdm.torque_foundry.physics.machine.BearingType;
import dev.sdm.torque_foundry.physics.machine.LubricantKind;
import dev.sdm.torque_foundry.physics.machine.LubricantKinds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Предметы механики: подшипники (опорные точки вала), смазки,
 * гаечный ключ. Логика установки — в ShaftBlock.useItemOn (клик
 * по торцу вала), предметы — носители данных типа.
 */
public final class TFItems {

    // --- Подшипники (опоры осевых точек вала) ---

    public static final RegistrySupplier<Item> BEARING_SLEEVE = TFBlocks.ITEMS.register(
            id("bearing_sleeve"),
            () -> new Item(props())
    );

    public static final RegistrySupplier<Item> BEARING_ROLLER = TFBlocks.ITEMS.register(
            id("bearing_roller"),
            () -> new Item(props())
    );

    public static final RegistrySupplier<Item> BEARING_BALL = TFBlocks.ITEMS.register(
            id("bearing_ball"),
            () -> new Item(props())
    );

    // --- Смазки ---

    public static final RegistrySupplier<Item> LUBRICANT_GREASE = TFBlocks.ITEMS.register(
            id("lubricant_grease"),
            () -> new Item(props())
    );

    public static final RegistrySupplier<Item> LUBRICANT_OIL = TFBlocks.ITEMS.register(
            id("lubricant_oil"),
            () -> new Item(props())
    );

    // --- Инструменты ---

    public static final RegistrySupplier<Item> WRENCH = TFBlocks.ITEMS.register(
            id("wrench"),
            () -> new Item(props().durability(256))
    );

    // --- Шестерня (предмет для шасси) ---

    public static final RegistrySupplier<GearItem> GEAR = TFBlocks.ITEMS.register(
            id("gear"),
            () -> new GearItem(props().stacksTo(16))
    );

    // --- Вал (вставка в шасси) ---

    public static final RegistrySupplier<ShaftPartItem> SHAFT_PART = TFBlocks.ITEMS.register(
            id("shaft_part"),
            () -> new ShaftPartItem(props().stacksTo(16))
    );

    /** Предмет -> тип подшипника (null — предмет не подшипник). */
    public static BearingType bearingOf(Item item) {
        if (item == BEARING_SLEEVE.get()) {
            return BearingType.SLEEVE;
        }
        if (item == BEARING_ROLLER.get()) {
            return BearingType.ROLLER;
        }
        if (item == BEARING_BALL.get()) {
            return BearingType.BALL;
        }
        return null;
    }

    /**
     * Предмет -> сорт смазки (null — предмет не смазка). Аддон-смазка
     * мапится своим кодом на свой зарегистрированный {@link LubricantKind}.
     */
    public static LubricantKind lubricantOf(Item item) {
        if (item == LUBRICANT_GREASE.get()) {
            return LubricantKinds.GREASE;
        }
        if (item == LUBRICANT_OIL.get()) {
            return LubricantKinds.OIL;
        }
        return null;
    }

    /** Предмет-подшипник по типу (для выпадения/возврата). */
    public static Item itemOf(BearingType type) {
        return switch (type) {
            case SLEEVE -> BEARING_SLEEVE.get();
            case ROLLER -> BEARING_ROLLER.get();
            case BALL -> BEARING_BALL.get();
            case NONE -> null;
        };
    }

    private static Item.Properties props() {
        return new Item.Properties();
    }

    private static ResourceLocation id(String path) {
        return TFBlocks.id(path);
    }

    public static void register() {
        // Static initialization via Architectury RegistrarManager.
    }

    private TFItems() {
    }
}
