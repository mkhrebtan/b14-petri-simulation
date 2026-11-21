package ua.kpi.km.b14.verification;

/**
 * Аналітичні характеристики системи масового обслуговування M/M/c (формула Ерланга C) —
 * еталон для контрольних випадків верифікації, коли в моделі залишено потік одного типу.
 *
 * @param arrivalRate λ, завд./хв
 * @param serviceRate μ, завд./хв
 * @param servers     кількість каналів c
 */
public record MmcQueue(double arrivalRate, double serviceRate, int servers) {

    public MmcQueue {
        if (servers < 1 || !(arrivalRate > 0) || !(serviceRate > 0)) {
            throw new IllegalArgumentException("Некоректні параметри M/M/c");
        }
        if (arrivalRate >= servers * serviceRate) {
            throw new IllegalArgumentException("Система M/M/c нестійка: ρ ≥ 1");
        }
    }

    /** Завантаження одного каналу ρ = λ / (cμ). */
    public double utilization() {
        return arrivalRate / (servers * serviceRate);
    }

    /** Ймовірність того, що система порожня, P_0. */
    public double probabilityEmpty() {
        double a = arrivalRate / serviceRate;
        double sum = 0;
        double term = 1; // a^k / k!
        for (int k = 0; k < servers; k++) {
            sum += term;
            term *= a / (k + 1);
        }
        // після циклу term = a^c / c!
        return 1.0 / (sum + term / (1 - utilization()));
    }

    /** Середня довжина черги L_q. */
    public double queueLength() {
        double a = arrivalRate / serviceRate;
        double rho = utilization();
        double aPowCOverCFactorial = 1;
        for (int k = 1; k <= servers; k++) {
            aPowCOverCFactorial *= a / k;
        }
        return probabilityEmpty() * aPowCOverCFactorial * rho / ((1 - rho) * (1 - rho));
    }

    /** Середній час очікування W_q = L_q / λ. */
    public double waitingTime() {
        return queueLength() / arrivalRate;
    }

    /** Середній час перебування W = W_q + 1/μ. */
    public double sojournTime() {
        return waitingTime() + 1 / serviceRate;
    }
}
