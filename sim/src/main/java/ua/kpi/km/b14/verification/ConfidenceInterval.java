package ua.kpi.km.b14.verification;

/**
 * Довірчий інтервал рівня 0,95 для середнього за вибіркою значень відгуку з n незалежних
 * прогонів: x̄ ± t_{0,975; n−1}·s/√n (розподіл Стьюдента).
 *
 * @param mean      вибіркове середнє x̄
 * @param halfWidth половина ширини інтервалу
 * @param size      кількість прогонів n
 */
public record ConfidenceInterval(double mean, double halfWidth, int size) {

    /** Квантилі t_{0,975; df} для df = 1…30. */
    private static final double[] T_975 = {
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042};
    private static final double Z_975 = 1.960;

    public static ConfidenceInterval of(double[] sample) {
        int n = sample.length;
        if (n < 2) {
            throw new IllegalArgumentException("Потрібно щонайменше 2 прогони");
        }
        double mean = 0;
        for (double x : sample) {
            mean += x;
        }
        mean /= n;
        double squares = 0;
        for (double x : sample) {
            squares += (x - mean) * (x - mean);
        }
        double sd = Math.sqrt(squares / (n - 1));
        double t = n - 1 <= T_975.length ? T_975[n - 2] : Z_975;
        return new ConfidenceInterval(mean, t * sd / Math.sqrt(n), n);
    }

    public boolean contains(double value) {
        return Math.abs(value - mean) <= halfWidth;
    }
}
