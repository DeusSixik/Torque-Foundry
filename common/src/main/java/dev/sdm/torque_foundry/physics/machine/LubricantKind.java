package dev.sdm.torque_foundry.physics.machine;

/**
 * Паспорт сорта смазки — иммутабельная запись, по образцу паспорта
 * материала. Задаются только независимые свойства; движок не содержит
 * перечисления сортов: аддон регистрирует новый сорт одной строкой в
 * {@link LubricantKinds#register} и получает предметом все его свойства.
 *
 * <p>Свойства (раздел «Смазки» документа физики, таблица предварительная):
 * <ul>
 *   <li><b>maxTemperatureC</b> — предельная рабочая температура. Смазка —
 *       модификатор узла: заправленная, ограничивает предельную температуру
 *       узла (смазанная бронза отказывает по смазке раньше, чем по
 *       материалу).</li>
 *   <li><b>frictionFactor</b> — множитель базового трения СМАЗАННОЙ опоры
 *       (&lt;1 — скользче; 1.0 — нейтрально; на сухую не действует).</li>
 *   <li><b>wearFactor</b> — множитель износа смазанных точек (&lt;1 — дольше;
 *       на сухую не действует — там свой множитель ×3).</li>
 * </ul>
 *
 * <p>Пример аддона (синтетическая смазка из таблицы документа):
 * <pre>{@code
 *   LubricantKinds.register(new LubricantKind(
 *       "mysynthetic:synthetic", "Synthetic", 220.0, 0.90, 0.70));
 * }</pre>
 *
 * @param id              стабильный ключ реестра ("grease", "addon:kind");
 *                        сохраняется в NBT вместо ordinal'а
 * @param name            отображаемое имя (инспектор, локализация)
 * @param maxTemperatureC предельная рабочая температура, °C, &gt; 20
 * @param frictionFactor  множитель трения смазанной опоры, &gt; 0
 * @param wearFactor      множитель износа смазанных точек, &gt; 0
 */
public record LubricantKind(
        String id,
        String name,
        double maxTemperatureC,
        double frictionFactor,
        double wearFactor
) {

    public LubricantKind {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("lubricant id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("lubricant name must not be blank: " + id);
        }
        if (maxTemperatureC <= 20.0) {
            // 20 C — AmbientTemperatureC из SimulationState (physics.machine);
            // константа не импортируется, чтобы паспорт не зависел от машин
            throw new IllegalArgumentException(
                    "maxTemperatureC must be above ambient 20 C, got "
                            + maxTemperatureC + ": " + id);
        }
        if (frictionFactor <= 0 || wearFactor <= 0) {
            throw new IllegalArgumentException(
                    "factors must be positive: " + id);
        }
    }
}
