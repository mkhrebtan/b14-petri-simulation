package ua.kpi.km.b14.petri;

import java.util.random.RandomGenerator;

/**
 * Часова затримка переходу {@code d}. Використовується в {@link StochasticTransition} замість
 * пари «назва закону розподілу + статичний {@code FunRand}» ядра {@code petri-math}:
 * генератор випадкових чисел передається явно, тож прогін із тим самим seed відтворюється.
 */
public sealed interface Delay permits Delay.Constant, Delay.Exponential {

    double sample(RandomGenerator random);

    /** Середнє значення затримки. */
    double mean();

    static Delay constant(double value) {
        return new Constant(value);
    }

    static Delay exponential(double mean) {
        return new Exponential(mean);
    }

    /** Детермінована затримка (зокрема {@code d = 0}). */
    record Constant(double value) implements Delay {
        public Constant {
            if (value < 0) {
                throw new IllegalArgumentException("Від'ємна затримка: " + value);
            }
        }

        @Override
        public double sample(RandomGenerator random) {
            return value;
        }

        @Override
        public double mean() {
            return value;
        }
    }

    /**
     * Експоненційна затримка exp(a) із середнім {@code a} — метод оберненої функції, як у
     * {@code FunRand.exp} ядра, але з генератором із seed.
     */
    record Exponential(double mean) implements Delay {
        public Exponential {
            if (!(mean > 0) || Double.isInfinite(mean)) {
                throw new IllegalArgumentException("Середнє exp має бути додатним і скінченним: " + mean);
            }
        }

        @Override
        public double sample(RandomGenerator random) {
            double u = 1.0 - random.nextDouble(); // u з (0; 1], тож ln(u) скінченний
            return -mean * Math.log(u);
        }
    }
}
