package dev.sdm.torque_foundry.core.item;

import com.mojang.serialization.Codec;
import dev.architectury.registry.registries.Registrar;
import dev.architectury.registry.registries.RegistrarManager;
import dev.architectury.registry.registries.RegistrySupplier;
import dev.sdm.torque_foundry.TorqueFoundry;
import dev.sdm.torque_foundry.core.block.TFBlocks;
import dev.sdm.torque_foundry.physics.machine.ShaftGrade;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Кастомные DataComponents предметов: тир балансировки вала,
 * параметры шестерни (зубья, материал).
 */
public final class TFComponents {

    private static final Registrar<DataComponentType<?>> TYPES =
            RegistrarManager.get(TorqueFoundry.MOD_ID).get(Registries.DATA_COMPONENT_TYPE);

    /** Тир балансировки предмета вала (Grade C..S). */
    public static final RegistrySupplier<DataComponentType<ShaftGrade>> SHAFT_GRADE = TYPES.register(
            TFBlocks.id("shaft_grade"),
            () -> DataComponentType.<ShaftGrade>builder()
                    .persistent(Codec.STRING.xmap(ShaftGrade::byName, ShaftGrade::getSerializedName))
                    .networkSynchronized(gradeStream())
                    .build()
    );

    /** Число зубьев предмета-шестерни. */
    public static final RegistrySupplier<DataComponentType<Integer>> GEAR_TEETH = TYPES.register(
            TFBlocks.id("gear_teeth"),
            () -> DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT.cast())
                    .build()
    );

    /** Имя физического материала шестерни (см. GearItem.materialOf). */
    public static final RegistrySupplier<DataComponentType<String>> GEAR_MATERIAL = TYPES.register(
            TFBlocks.id("gear_material"),
            () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8.cast())
                    .build()
    );

    /** Имя физического материала предмета-вала (см. ShaftPartItem.materialOf). */
    public static final RegistrySupplier<DataComponentType<String>> SHAFT_MATERIAL = TYPES.register(
            TFBlocks.id("shaft_material"),
            () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8.cast())
                    .build()
    );

    private static StreamCodec<RegistryFriendlyByteBuf, ShaftGrade> gradeStream() {
        return ByteBufCodecs.STRING_UTF8
                .map(ShaftGrade::byName, ShaftGrade::getSerializedName)
                .cast();
    }

    public static void register() {
        // Static initialization via Architectury RegistrarManager.
    }

    private TFComponents() {
    }
}
