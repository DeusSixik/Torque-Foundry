package dev.sdm.torque_foundry.debug.physics;

import dev.sdm.torque_foundry.api.block.MechanicalBlockEntity;
import dev.sdm.torque_foundry.core.network.ClientGroupCache;
import dev.sdm.torque_foundry.physics.group.MechanicalGroup;
import dev.sdm.torque_foundry.physics.machine.MechanicalMachine;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;

import java.util.List;

/**
 * Полноценное отладочное окно инспектора (клавиша N): все показатели блока
 * под прицелом по секциям. ПКМ по строке открывает контекстное меню —
 * pin/unpin метрики. Закреплённые метрики дублируются в компактный HUD.
 */
public final class InspectorWindow {

    private static final PinStore PINS = new PinStore();
    private static final ImString SEARCH = new ImString(128);
    private static final ImBoolean INSPECTOR_OPEN = new ImBoolean(false);
    private static final ImBoolean HUD_OPEN = new ImBoolean(true);

    /** Кэш снепшота: не пересобираем строки, если прицел не сменился. */
    private static BlockPos lastPos;
    private static String lastMachineFingerprint;
    private static int frameCounter;
    private static InspectorSnapshot cached;

    private InspectorWindow() {
    }

    public static PinStore pins() {
        return PINS;
    }

    public static void loadPins(java.nio.file.Path file) {
        PINS.load(file);
    }

    public static void savePins(java.nio.file.Path file) {
        PINS.save(file);
    }

    /** Переключить окно инспектора (кейбинд N). */
    public static void toggleInspector() {
        INSPECTOR_OPEN.set(!INSPECTOR_OPEN.get());
    }

    public static boolean isInspectorOpen() {
        return INSPECTOR_OPEN.get();
    }

    // --- Точка входа из ClientOverlay ---

    public static void render(Minecraft minecraft) {
        frameCounter++;

        final Target target = resolveTarget(minecraft);
        final InspectorSnapshot snapshot = snapshotFor(target);

        renderHud(snapshot);
        if (INSPECTOR_OPEN.get()) {
            renderInspector(snapshot, target);
        }

        // Пины сохраняем лениво: только когда что-то менялось.
        if (PINS.isDirty() && frameCounter % 100 == 0) {
            savePins(DebugPaths.pinFile());
        }
    }

    // --- Прицел ---

    private record Target(BlockPos pos, String blockKey, MechanicalBlockEntity mechanical) {
    }

    private static Target resolveTarget(Minecraft minecraft) {
        if (minecraft.level == null || !(minecraft.hitResult instanceof BlockHitResult hit)) {
            return null;
        }
        final BlockPos pos = hit.getBlockPos();
        final BlockEntity entity = minecraft.level.getBlockEntity(pos);
        if (!(entity instanceof MechanicalBlockEntity mechanical)) {
            final String key = BuiltInRegistries.BLOCK.getKey(
                    minecraft.level.getBlockState(pos).getBlock()).toString();
            return new Target(pos, key, null);
        }
        final String key = BuiltInRegistries.BLOCK.getKey(
                mechanical.getBlockState().getBlock()).toString();
        return new Target(pos, key, mechanical);
    }

    // --- Снепшот ---

    private static InspectorSnapshot snapshotFor(Target target) {
        if (target == null) {
            cached = null;
            lastPos = null;
            return InspectorSnapshot.empty("Nothing targeted");
        }
        if (target.mechanical() == null) {
            cached = null;
            lastPos = null;
            return InspectorSnapshot.empty(
                    target.pos().toShortString() + " : " + target.blockKey() + " (not mechanical)");
        }

        final MechanicalMachine machine = target.mechanical().machine;
        // Значения метрик живые (температура/скорость тикают), поэтому снепшот
        // пересобираем каждый кадр — дёшево: только форматирование строк.
        // Кэшируем только прицел для HUD-заголовка.
        lastPos = target.pos();
        lastMachineFingerprint = machine.getClass().getSimpleName();

        final long groupId = machine.getGroupIndex();
        final String groupLine = groupId == -1 ? "none" : String.valueOf(groupId);

        String membersLine = null;
        String netSpeedLine = null;
        boolean groupUnknown = groupId == -1;
        if (groupId != -1) {
            final Integer syncedMembers = ClientGroupCache.getMembers(groupId);
            if (syncedMembers != null) {
                membersLine = syncedMembers + " (synced)";
                netSpeedLine = String.format(java.util.Locale.ROOT, "%.3f RPM",
                        machine.getReceived().getSpeedRpm());
            } else {
                final MechanicalGroup group =
                        dev.sdm.torque_foundry.core.data.MechanicalGroupManager.getGroup(groupId);
                if (group != null) {
                    membersLine = group.getSize()
                            + " | slot " + machine.getGroupElementIndex();
                    netSpeedLine = String.format(java.util.Locale.ROOT, "%.3f RPM",
                            group.getCurrentSpeedRpm());
                } else {
                    groupUnknown = true;
                    membersLine = "not synced yet";
                }
            }
        }

        cached = InspectorSnapshot.collect(
                target.blockKey(), target.pos().toShortString(),
                machine.getClass().getSimpleName(),
                groupLine, membersLine, netSpeedLine, groupUnknown, machine,
                target.mechanical());
        return cached;
    }

    // --- Окно инспектора ---

    private static void renderInspector(InspectorSnapshot snapshot, Target target) {
        ImGui.setNextWindowSize(680.0f, 780.0f, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("TF Inspector", INSPECTOR_OPEN,
                ImGuiWindowFlags.NoCollapse)) {
            ImGui.end();
            return;
        }

        ImGui.textDisabled(snapshot.subtitle());
        ImGui.sameLine();
        if (ImGui.button("Clear pins (" + PINS.size() + ")")) {
            PINS.clear();
        }

        // Поиск по label/section.
        ImGui.inputText("Search", SEARCH);
        final String query = SEARCH.get().strip().toLowerCase(java.util.Locale.ROOT);

        if (snapshot.sections().isEmpty()) {
            ImGui.textDisabled(snapshot.title() + ": " + snapshot.subtitle());
            ImGui.end();
            return;
        }

        for (InspectorSnapshot.Section section : snapshot.sections()) {
            if (!ImGui.collapsingHeader(section.name())) {
                continue;
            }
            renderMetricTable(section.metrics(), query, true);
        }

        // Подсказка про ПКМ — один раз внизу, не спамит.
        ImGui.separator();
        ImGui.textDisabled("Right-click a row to pin/unpin it into the HUD (key N toggles this window).");
        if (target != null && target.mechanical() == null) {
            ImGui.textDisabled("Target is not a mechanical block.");
        }

        ImGui.end();
    }

    private static void renderMetricTable(
            List<InspectorSnapshot.Metric> metrics, String query, boolean withBars) {
        final int flags = ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersInnerV
                | ImGuiTableFlags.SizingStretchProp;
        if (!ImGui.beginTable("tf_metrics", 2, flags)) {
            return;
        }
        for (InspectorSnapshot.Metric metric : metrics) {
            if (!query.isEmpty()
                    && !metric.label().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !metric.section().toLowerCase(java.util.Locale.ROOT).contains(query)
                    && !metric.value().toLowerCase(java.util.Locale.ROOT).contains(query)) {
                continue;
            }
            ImGui.pushID(metric.id());
            ImGui.tableNextRow();

            // Колонка 0: label.
            ImGui.tableNextColumn();
            final boolean pinned = PINS.isPinned(metric.id());
            if (pinned) {
                ImGui.text("[*] " + metric.label());
            } else if (metric.alert()) {
                ImGui.textColored(1.0f, 0.45f, 0.3f, 1.0f, metric.label());
            } else {
                ImGui.text(metric.label());
            }
            rowPopup(metric, "label");

            // Колонка 1: value (+ бар).
            ImGui.tableNextColumn();
            if (metric.alert()) {
                ImGui.textColored(1.0f, 0.45f, 0.3f, 1.0f, metric.value());
            } else {
                ImGui.text(metric.value());
            }
            rowPopup(metric, "value");
            if (metric.hint() != null && ImGui.isItemHovered()) {
                ImGui.beginTooltip();
                ImGui.text(metric.hint());
                ImGui.endTooltip();
            }
            if (withBars && metric.hasBar()) {
                ImGui.progressBar(metric.barFraction(), 0, 0, "");
            }

            ImGui.popID();
        }
        ImGui.endTable();
    }

    /**
     * ПКМ-меню строки: pin/unpin. Явный строковый id обязателен:
     * безымянный beginPopupContextItem() падает ассертом id != 0,
     * когда текущий item — bare text() без id (см. imgui.cpp).
     */
    private static void rowPopup(InspectorSnapshot.Metric metric, String cell) {
        if (!ImGui.beginPopupContextItem(metric.id() + "#" + cell)) {
            return;
        }
        final boolean pinned = PINS.isPinned(metric.id());
        final String itemLabel = pinned ? "Unpin from HUD" : "Pin to HUD";
        if (ImGui.menuItem(itemLabel)) {
            PINS.toggle(metric.id());
        }
        if (ImGui.menuItem("Copy id")) {
            Minecraft.getInstance().keyboardHandler.setClipboard(metric.id());
        }
        ImGui.endPopup();
    }

    // --- Компактный HUD только с закреплённым ---

    private static void renderHud(InspectorSnapshot snapshot) {
        if (PINS.isEmpty()) {
            return;
        }
        ImGui.setNextWindowPos(4, 4, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(360.0f, 0.0f, ImGuiCond.FirstUseEver);
        final int flags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
        if (!ImGui.begin("TF Pinned", HUD_OPEN, flags)) {
            ImGui.end();
            return;
        }

        if (lastPos != null) {
            ImGui.textDisabled(snapshot.subtitle());
        }
        ImGui.separator();

        boolean anyAlive = false;
        for (String id : PINS.ordered()) {
            final InspectorSnapshot.Metric metric = snapshot.findById(id);
            ImGui.pushID("hud:" + id);
            if (metric == null) {
                // Метрика другой секции/машины (или прицел не механический):
                // показываем id приглушённо, чтобы было видно что пин жив.
                ImGui.textDisabled(stripSection(id) + ": —");
            } else {
                anyAlive = true;
                if (metric.alert()) {
                    ImGui.textColored(1.0f, 0.45f, 0.3f, 1.0f,
                            metric.label() + ": " + metric.value());
                } else {
                    ImGui.text(metric.label() + ": " + metric.value());
                }
                if (metric.hasBar()) {
                    ImGui.progressBar(metric.barFraction(), 0, 0, "");
                }
            }
            // ПКМ по HUD-строке — открепить без открытия инспектора.
            // Явный id: bare text()/textDisabled не дают item-id (см. rowPopup).
            if (ImGui.beginPopupContextItem("hudctx:" + id)) {
                if (ImGui.menuItem("Unpin")) {
                    PINS.unpin(id);
                }
                ImGui.endPopup();
            }
            ImGui.popID();
        }

        if (!anyAlive) {
            ImGui.textDisabled("Pins target another block — aim at it to see values.");
        }

        ImGui.end();
    }

    private static String stripSection(String id) {
        final int dot = id.indexOf('.');
        return dot >= 0 ? id.substring(dot + 1) : id;
    }

    // --- Совместимость со старым ClientOverlay ---

    /** Заглушка: старый оверлей заменён связкой Inspector + Pinned HUD. */
    static String legacyNotice() {
        return "Replaced by TF Inspector (N) + TF Pinned HUD";
    }
}
