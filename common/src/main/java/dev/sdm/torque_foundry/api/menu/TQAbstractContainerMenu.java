package dev.sdm.torque_foundry.api.menu;

import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Базовый класс меню с типовой логикой слотов, инвентаря игрока и кнопок интерфейса.
 * Только ванильные системы (1.21.1): {@link Container} + {@link Slot}, без Fabric/NeoForge.
 *
 * <p>Сначала добавьте слоты контейнера через {@link #addContainerSlot(Slot)} или
 * {@link #addContainerGrid(Container, int, int, int, int, int)}, затем один раз вызовите
 * {@link #addPlayerInventoryGrid(int, int)}. После добавления сетки игрока добавлять слоты
 * контейнера нельзя: это гарантирует корректную работу {@code Shift}-переноса.</p>
 *
 * <p>Пример минимального меню:</p>
 * <pre>{@code
 * public final class ExampleMenu extends TQAbstractContainerMenu {
 *     public ExampleMenu(MenuType<ExampleMenu> type, int containerId,
 *                        Inventory inventory, Container container) {
 *         super(type, containerId, inventory);
 *         addContainerGrid(container, 0, 9, 3, 8, 18);
 *         addPlayerInventoryGrid(8, 84);
 *         addButton(0, player -> player.sendSystemMessage(Component.literal("Нажато")));
 *     }
 *
 *     @Override
 *     public boolean stillValid(Player player) {
 *         return stillValid(ACCESS, player, MY_BLOCK);
 *     }
 * }
 * }</pre>
 */
public class TQAbstractContainerMenu extends AbstractContainerMenu {

    protected static final int SLOT_SIZE = 18;
    protected static final int PLAYER_INVENTORY_ROWS = 3;
    protected static final int PLAYER_INVENTORY_COLUMNS = 9;

    /** Зазор между основным инвентарём и хотбаром (ванильные 4 px). */
    protected static final int HOTBAR_GAP = 4;

    protected final Inventory playerInventory;
    protected final Map<Integer, Slot> containerSlots = new Int2ObjectLinkedOpenHashMap<>();
    protected final Map<Integer, Consumer<Player>> buttonHandlers = new Int2ObjectLinkedOpenHashMap<>();
    protected int playerSlotsStart = -1;
    protected int playerSlotsEnd = -1;

    /**
     * Создает меню без слотов. Слоты контейнера и инвентарь игрока добавляются подклассом.
     *
     * @param type зарегистрированный тип меню
     * @param containerId идентификатор открытого контейнера
     * @param playerInventory инвентарь игрока, открывшего меню
     */
    protected TQAbstractContainerMenu(MenuType<?> type, int containerId, Inventory playerInventory) {
        super(type, containerId);
        this.playerInventory = playerInventory;
    }

    /**
     * Добавляет слот контейнера и сохраняет его по индексу среди всех слотов меню.
     *
     * <p>Пример:</p>
     * <pre>{@code
     * addContainerSlot(new Slot(container, 0, 8, 18));
     * }</pre>
     *
     * @param slot добавляемый слот
     * @return добавленный слот
     * @throws IllegalStateException если сетка инвентаря игрока уже добавлена
     */
    protected final Slot addContainerSlot(Slot slot) {
        if (playerSlotsStart >= 0) {
            throw new IllegalStateException("Container slots must be added before player inventory slots");
        }

        Slot addedSlot = super.addSlot(slot);
        containerSlots.put(addedSlot.index, addedSlot);
        return addedSlot;
    }

    /**
     * Добавляет прямоугольную сетку слотов {@link Container}.
     *
     * <p>Пример:</p>
     * <pre>{@code
     * addContainerGrid(container, 0, 9, 4, 8, 18);
     * }</pre>
     *
     * @param container ванильный контейнер (BlockEntity с {@code getContainerSize()})
     * @param firstSlot индекс первого слота в контейнере
     * @param columns число столбцов сетки
     * @param rows число строк сетки
     * @param leftPos X-координата верхнего левого слота
     * @param topPos Y-координата верхнего левого слота
     * @throws IllegalArgumentException если размеры сетки некорректны или диапазон слотов выходит за контейнер
     * @throws IllegalStateException если сетка инвентаря игрока уже добавлена
     */
    protected final void addContainerGrid(Container container, int firstSlot, int columns, int rows,
                                          int leftPos, int topPos) {
        if (columns <= 0 || rows <= 0 || firstSlot < 0) {
            throw new IllegalArgumentException("Grid dimensions and first slot must be positive");
        }

        int slotsCount = Math.multiplyExact(columns, rows);
        if (firstSlot + slotsCount > container.getContainerSize()) {
            throw new IllegalArgumentException("Container does not contain enough slots for the grid");
        }

        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int containerSlot = firstSlot + column + row * columns;
                addContainerSlot(new Slot(container, containerSlot,
                        leftPos + column * SLOT_SIZE, topPos + row * SLOT_SIZE));
            }
        }
    }

    /**
     * Добавляет одну строку слотов {@link Container}.
     *
     * <p>Пример:</p>
     * <pre>{@code
     * addContainerRow(container, 0, 3, 8, 18); // один слот 3x1
     * }</pre>
     *
     * @param container ванильный контейнер
     * @param firstSlot индекс первого слота в контейнере
     * @param columns число слотов в строке
     * @param leftPos X-координата первого слота
     * @param topPos Y-координата строки
     * @throws IllegalArgumentException если строка выходит за контейнер
     * @throws IllegalStateException если сетка инвентаря игрока уже добавлена
     */
    protected final void addContainerRow(Container container, int firstSlot, int columns,
                                         int leftPos, int topPos) {
        addContainerGrid(container, firstSlot, columns, 1, leftPos, topPos);
    }

    /**
     * Добавляет основной инвентарь игрока и хотбар стандартной сеткой 9x3 + 9
     * (ванильная раскладка: основной инвентарь — слоты 9..35, хотбар — 0..8).
     *
     * <p>{@code topPos} задает Y-координату первой строки основного инвентаря. Хотбар
     * располагается на {@code 3 * 18 + 4} пикселей ниже (ванильный зазор 4 px).</p>
     *
     * <p>Пример:</p>
     * <pre>{@code
     * addPlayerInventoryGrid(8, 84);
     * }</pre>
     *
     * @param leftPos X-координата первого слота
     * @param topPos Y-координата первой строки основного инвентаря
     * @throws IllegalStateException если сетка игрока уже добавлена
     */
    protected final void addPlayerInventoryGrid(int leftPos, int topPos) {
        if (playerSlotsStart >= 0) {
            throw new IllegalStateException("Player inventory slots have already been added");
        }

        playerSlotsStart = slots.size();
        for (int row = 0; row < PLAYER_INVENTORY_ROWS; row++) {
            for (int column = 0; column < PLAYER_INVENTORY_COLUMNS; column++) {
                super.addSlot(new Slot(playerInventory, column + (row + 1) * PLAYER_INVENTORY_COLUMNS,
                        leftPos + column * SLOT_SIZE, topPos + row * SLOT_SIZE));
            }
        }
        for (int column = 0; column < PLAYER_INVENTORY_COLUMNS; column++) {
            super.addSlot(new Slot(playerInventory, column, leftPos + column * SLOT_SIZE,
                    topPos + PLAYER_INVENTORY_ROWS * SLOT_SIZE + HOTBAR_GAP));
        }
        playerSlotsEnd = slots.size();
    }

    /**
     * Регистрирует действие кнопки меню.
     *
     * <p>Обработчик вызывается сервером после получения нажатия кнопки от клиента
     * (ванильный пакет {@code ServerboundContainerButtonClickPacket}).</p>
     *
     * <p>Пример:</p>
     * <pre>{@code
     * addButton(0, player -> settingsEnabled = !settingsEnabled);
     * }</pre>
     *
     * @param buttonId идентификатор кнопки, передаваемый в {@link #clickMenuButton(Player, int)}
     * @param handler действие при нажатии кнопки
     * @throws IllegalArgumentException если обработчик уже зарегистрирован для этого идентификатора
     */
    protected final void addButton(int buttonId, Consumer<Player> handler) {
        if (buttonHandlers.putIfAbsent(buttonId, handler) != null) {
            throw new IllegalArgumentException("Button handler is already registered for id " + buttonId);
        }
    }

    /**
     * Ванильная проверка валидности меню по блоку: блок на месте и игрок
     * в пределах досягаемости ({@code 8} блоков, как в ванильных контейнерах —
     * см. {@link AbstractContainerMenu#stillValid(ContainerLevelAccess, Player, Block)}).
     *
     * <p>Пример в подклассе:</p>
     * <pre>{@code
     * private final ContainerLevelAccess access;
     *
     * public ExampleMenu(int containerId, Inventory inventory,
     *                    CaseBlockEntity blockEntity) {
     *     super(MY_TYPE.get(), containerId, inventory);
     *     this.access = ContainerLevelAccess.create(
     *             blockEntity.getLevel(), blockEntity.getBlockPos());
     *     // ... слоты
     * }
     *
     * @Override
     * public boolean stillValid(Player player) {
     *     return stillValid(this.access, player, TFBlocks.CASE.get());
     * }
     * }</pre>
     *
     * @param access точка доступа к миру (создаётся подклассом из BlockEntity)
     * @param player игрок, открывший меню
     * @param block блок-владелец контейнера
     * @return true, если блок на месте и игрок рядом
     */
    protected static boolean stillValid(net.minecraft.world.inventory.ContainerLevelAccess access,
                                        Player player, Block block) {
        // AbstractContainerMenu.stillValid уже делает эту проверку — переиспользуем.
        return AbstractContainerMenu.stillValid(access, player, block);
    }

    /**
     * Валидность меню по умолчанию: база не знает мир подкласса.
     * Подкласс ОБЯЗАН переопределить через {@link #stillValid(ContainerLevelAccess,
     * Player, Block)} или свою проверку — иначе меню останется открытым
     * после разрушения блока.
     */
    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /**
     * Возвращает слоты контейнера без слотов инвентаря игрока.
     *
     * @return неизменяемое отображение индекса меню на слот
     */
    public final Map<Integer, Slot> getContainerSlots() {
        return Collections.unmodifiableMap(containerSlots);
    }

    /**
     * Обрабатывает стандартный перенос предмета с зажатым {@code Shift}.
     *
     * <p>Предмет из контейнера переносится в инвентарь игрока, а предмет из инвентаря игрока
     * переносится в слоты контейнера. Для работы требуется вызвать
     * {@link #addPlayerInventoryGrid(int, int)}.</p>
     *
     * @param player игрок, выполняющий перенос
     * @param index индекс исходного слота меню
     * @return исходная копия предмета при успешном переносе, иначе {@link ItemStack#EMPTY}
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (playerSlotsStart < 0 || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = slot.getItem();
        ItemStack result = sourceStack.copy();
        boolean moved;
        if (index < playerSlotsStart) {
            moved = moveItemStackTo(sourceStack, playerSlotsStart, playerSlotsEnd, true);
        } else {
            moved = moveItemStackTo(sourceStack, 0, playerSlotsStart, false);
        }

        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (sourceStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (sourceStack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }

        slot.onTake(player, sourceStack);
        return result;
    }

    /**
     * Вызывает обработчик кнопки, зарегистрированный через {@link #addButton(int, Consumer)}.
     *
     * @param player игрок, нажавший кнопку
     * @param buttonId идентификатор кнопки
     * @return {@code true}, если для кнопки зарегистрирован обработчик
     */
    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        Consumer<Player> handler = buttonHandlers.get(buttonId);
        if (handler == null) {
            return false;
        }

        handler.accept(player);
        return true;
    }
}
