package ua.kpi.km.b14.model;

/**
 * Типи завдань і їх незмінні параметри з умови задачі (табл. 1.3 пояснювальної записки).
 */
public enum TaskType {
    A(0.20, 4.0, 1),
    B(0.07, 5.0, 1),
    C(0.06, 10.0, 2);

    private final double baseArrivalRate;
    private final double meanServiceTime;
    private final int memoryDemand;

    TaskType(double baseArrivalRate, double meanServiceTime, int memoryDemand) {
        this.baseArrivalRate = baseArrivalRate;
        this.meanServiceTime = meanServiceTime;
        this.memoryDemand = memoryDemand;
    }

    /** Інтенсивність надходження з умови λ_i^0, завд./хв. */
    public double baseArrivalRate() {
        return baseArrivalRate;
    }

    /** Середній час виконання 1/μ_i, хв. */
    public double meanServiceTime() {
        return meanServiceTime;
    }

    /** Потреба в пам'яті v_i, умовних одиниць (кратність дуг у мережі). */
    public int memoryDemand() {
        return memoryDemand;
    }
}
