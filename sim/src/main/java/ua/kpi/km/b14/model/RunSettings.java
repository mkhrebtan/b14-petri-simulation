package ua.kpi.km.b14.model;

/**
 * Параметри одного прогону.
 *
 * @param simulationTime час моделювання T_мод, хв
 * @param warmupTime     тривалість перехідного періоду T_п, хв (статистику збирають на [T_п; T_мод])
 * @param seed           seed генератора випадкових чисел (відтворюваність прогону)
 */
public record RunSettings(double simulationTime, double warmupTime, long seed) {

    public RunSettings {
        if (!(warmupTime >= 0) || !(simulationTime > warmupTime)) {
            throw new IllegalArgumentException("Потрібно 0 ≤ T_п < T_мод");
        }
    }

    /** Тривалість інтервалу збору статистики τ = T_мод − T_п. */
    public double statisticsDuration() {
        return simulationTime - warmupTime;
    }

    public RunSettings withSeed(long newSeed) {
        return new RunSettings(simulationTime, warmupTime, newSeed);
    }
}
