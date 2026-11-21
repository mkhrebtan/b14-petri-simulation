package ua.kpi.km.b14.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Вхідні змінні моделі: стратегія упорядкування s та інтенсивності надходження λ_i, а також
 * параметри, що за умовою незмінні, але варіюються під час верифікації: середні часи
 * виконання 1/μ_i і обсяг пам'яті M (за умовою — 2 одиниці).
 *
 * @param strategy         стратегія упорядкування черги
 * @param arrivalRates     λ_i, завд./хв; λ_i = 0 вимикає потік типу i (контрольні випадки верифікації)
 * @param meanServiceTimes 1/μ_i, хв
 * @param memory           обсяг пам'яті M, умовних одиниць
 */
public record ModelParameters(Strategy strategy, Map<TaskType, Double> arrivalRates,
                              Map<TaskType, Double> meanServiceTimes, int memory) {

    public static final int DEFAULT_MEMORY = 2;

    public ModelParameters {
        Objects.requireNonNull(strategy);
        arrivalRates = copy(arrivalRates, "λ", false);
        meanServiceTimes = copy(meanServiceTimes, "1/μ", true);
        if (memory < TaskType.C.memoryDemand()) {
            throw new IllegalArgumentException("Пам'яті M = " + memory + " замало для завдання C");
        }
    }

    /** λ_i = γ·λ_i^0 для всіх типів (основна серія експериментів). */
    public static ModelParameters ofIntensityFactor(Strategy strategy, double gamma) {
        EnumMap<TaskType, Double> rates = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            rates.put(type, gamma * type.baseArrivalRate());
        }
        return new ModelParameters(strategy, rates, baseServiceTimes(), DEFAULT_MEMORY);
    }

    /** Довільні λ_A, λ_B, λ_C (контрольні випадки верифікації). */
    public static ModelParameters ofRates(Strategy strategy, double rateA, double rateB, double rateC) {
        EnumMap<TaskType, Double> rates = new EnumMap<>(TaskType.class);
        rates.put(TaskType.A, rateA);
        rates.put(TaskType.B, rateB);
        rates.put(TaskType.C, rateC);
        return new ModelParameters(strategy, rates, baseServiceTimes(), DEFAULT_MEMORY);
    }

    /** Ті самі s і λ_i з іншим обсягом пам'яті M (пропозиція розділу 5, верифікація). */
    public ModelParameters withMemory(int memoryUnits) {
        return new ModelParameters(strategy, arrivalRates, meanServiceTimes, memoryUnits);
    }

    /** Змінено лише λ_i типу {@code type} (верифікація: зміна однієї вхідної змінної). */
    public ModelParameters withArrivalRate(TaskType type, double rate) {
        EnumMap<TaskType, Double> rates = new EnumMap<>(arrivalRates);
        rates.put(type, rate);
        return new ModelParameters(strategy, rates, meanServiceTimes, memory);
    }

    /** Змінено лише 1/μ_i типу {@code type} (верифікація: зміна однієї вхідної змінної). */
    public ModelParameters withMeanServiceTime(TaskType type, double time) {
        EnumMap<TaskType, Double> times = new EnumMap<>(meanServiceTimes);
        times.put(type, time);
        return new ModelParameters(strategy, arrivalRates, times, memory);
    }

    public double arrivalRate(TaskType type) {
        return arrivalRates.get(type);
    }

    public double meanServiceTime(TaskType type) {
        return meanServiceTimes.get(type);
    }

    /**
     * Частка пам'яті, потрібна потоку завдань: ρ = Σ λ_i v_i / (μ_i M) (формула 1.1).
     * У стійкому режимі дорівнює коефіцієнту використання пам'яті U.
     */
    public double offeredLoad() {
        double load = 0;
        for (TaskType type : TaskType.values()) {
            load += arrivalRate(type) * meanServiceTime(type) * type.memoryDemand();
        }
        return load / memory;
    }

    /** 1/μ_i з умови задачі. */
    private static Map<TaskType, Double> baseServiceTimes() {
        EnumMap<TaskType, Double> times = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            times.put(type, type.meanServiceTime());
        }
        return times;
    }

    private static Map<TaskType, Double> copy(Map<TaskType, Double> values, String name, boolean positive) {
        EnumMap<TaskType, Double> result = new EnumMap<>(TaskType.class);
        for (TaskType type : TaskType.values()) {
            double value = Objects.requireNonNull(values.get(type), "Не задано " + name + " для " + type);
            if (!(positive ? value > 0 : value >= 0) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Некоректне " + name + "_" + type + " = " + value);
            }
            result.put(type, value);
        }
        return Collections.unmodifiableMap(result);
    }
}
