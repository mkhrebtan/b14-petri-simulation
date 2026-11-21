package ua.kpi.km.b14.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Результати одного прогону моделі: показники за типами та загальні показники (формули 2.5–2.6).
 *
 * @param parameters   вхідні змінні прогону
 * @param settings     T_мод, T_п і seed
 * @param byType       показники за типами завдань
 * @param queueLength  L_q — середня кількість завдань у черзі (усіх типів)
 * @param waitingTime  W_q — середній час очікування в черзі, хв
 * @param sojournTime  W — середній час перебування в системі, хв
 * @param utilization  U — коефіцієнт використання пам'яті
 * @param reservedIdle частка пам'яті, що простоює через резервування, m̄_RC / M
 */
public record SimulationResult(ModelParameters parameters, RunSettings settings,
                               Map<TaskType, TypeMetrics> byType,
                               double queueLength, double waitingTime, double sojournTime,
                               double utilization, double reservedIdle) {

    public SimulationResult {
        byType = Collections.unmodifiableMap(new EnumMap<>(byType));
    }

    public TypeMetrics of(TaskType type) {
        return byType.get(type);
    }
}
