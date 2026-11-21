package ua.kpi.km.b14.model;

/**
 * Стратегії упорядкування черги та параметри мережі, що їх задають (табл. 2.3):
 * пріоритети r_A, r_B (переходи ServA, ServB), r_C (перехід ResC), кратність k_C дуг
 * резервування і ймовірності запуску b_A, b_B.
 */
public enum Strategy {
    S1(1, "A > B > C", 3, 2, 1, 2, 1.0, 1.0),
    S2(2, "B > A > C", 2, 3, 1, 2, 1.0, 1.0),
    S3(3, "C > B > A", 1, 2, 3, 1, 1.0, 1.0),
    S4(4, "C > A > B", 2, 1, 3, 1, 1.0, 1.0),
    S5(5, "C > (A = B)", 1, 1, 2, 1, 0.5, 0.5);

    private final int number;
    private final String ordering;
    private final int priorityA;
    private final int priorityB;
    private final int priorityC;
    private final int reservationMultiplicity;
    private final double probabilityA;
    private final double probabilityB;

    Strategy(int number, String ordering, int priorityA, int priorityB, int priorityC,
             int reservationMultiplicity, double probabilityA, double probabilityB) {
        this.number = number;
        this.ordering = ordering;
        this.priorityA = priorityA;
        this.priorityB = priorityB;
        this.priorityC = priorityC;
        this.reservationMultiplicity = reservationMultiplicity;
        this.probabilityA = probabilityA;
        this.probabilityB = probabilityB;
    }

    /** Номер стратегії s = 1…5. */
    public int number() {
        return number;
    }

    public String ordering() {
        return ordering;
    }

    /** Пріоритет r_i: для A, B — переходу Serv_i, для C — переходу ResC. */
    public int priority(TaskType type) {
        return switch (type) {
            case A -> priorityA;
            case B -> priorityB;
            case C -> priorityC;
        };
    }

    /** Ймовірність запуску b_i переходу Serv_i (для C завжди 1). */
    public double launchProbability(TaskType type) {
        return switch (type) {
            case A -> probabilityA;
            case B -> probabilityB;
            case C -> 1.0;
        };
    }

    /** Кратність k_C дуг Mem → ResC → RC. */
    public int reservationMultiplicity() {
        return reservationMultiplicity;
    }

    public static Strategy byNumber(int number) {
        for (Strategy s : values()) {
            if (s.number == number) {
                return s;
            }
        }
        throw new IllegalArgumentException("Немає стратегії " + number);
    }
}
