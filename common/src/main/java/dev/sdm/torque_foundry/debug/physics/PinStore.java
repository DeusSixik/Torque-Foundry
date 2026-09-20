package dev.sdm.torque_foundry.debug.physics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Закреплённые метрики инспектора (pin). Хранит id метрик вида "Section.key"
 * в порядке закрепления; переживает перезапуск через файл в config/.
 * Потокобезопасность не нужна: всё использование — клиентский render-тред.
 */
public final class PinStore {

    private static final int MAX_PINS = 64;

    private final LinkedHashSet<String> pins = new LinkedHashSet<>();
    private boolean dirty = false;

    public boolean isPinned(String metricId) {
        return pins.contains(metricId);
    }

    /** Переключить pin. Возвращает новое состояние (true — закреплено). */
    public boolean toggle(String metricId) {
        if (pins.contains(metricId)) {
            pins.remove(metricId);
            dirty = true;
            return false;
        }
        if (pins.size() >= MAX_PINS) {
            return false;
        }
        pins.add(metricId);
        dirty = true;
        return true;
    }

    public void unpin(String metricId) {
        if (pins.remove(metricId)) {
            dirty = true;
        }
    }

    public void clear() {
        if (!pins.isEmpty()) {
            pins.clear();
            dirty = true;
        }
    }

    public List<String> ordered() {
        return new ArrayList<>(pins);
    }

    public boolean isEmpty() {
        return pins.isEmpty();
    }

    public int size() {
        return pins.size();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void markClean() {
        dirty = false;
    }

    // --- Персистентность ---

    public void load(Path file) {
        pins.clear();
        dirty = false;
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                final String id = line.strip();
                if (id.isEmpty() || id.startsWith("#")) {
                    continue;
                }
                if (pins.size() >= MAX_PINS) {
                    break;
                }
                pins.add(id);
            }
        } catch (IOException ignored) {
            // Битый файл пинов — стартуем с пустым набором, перезапишем позже.
            pins.clear();
        }
    }

    public void save(Path file) {
        if (file == null || !dirty) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            final Set<String> snapshot = new LinkedHashSet<>(pins);
            final List<String> lines = new ArrayList<>(snapshot.size() + 1);
            lines.add("# Torque Foundry pinned inspector metrics (Section.key, one per line)");
            lines.addAll(snapshot);
            Files.write(file, lines, StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException ignored) {
            // Не смогли записать — попробуем в следующий раз, пины в памяти живы.
        }
    }
}
