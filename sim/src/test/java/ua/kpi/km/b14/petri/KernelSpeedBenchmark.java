package ua.kpi.km.b14.petri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import ua.kpi.km.b14.model.B14Net;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.Strategy;
import ua.stetsenkoinna.petriobj.PetriObjModel;
import ua.stetsenkoinna.petriobj.PetriSim;

/**
 * Замір швидкодії (не юніт-тест; запуск — {@code just bench}): прогін мережі B14 тривалістю
 * {@code T_мод = 3,5·10⁶} хв циклом {@link PetriSimulator} і незміненим циклом
 * {@code PetriObjModel.go} ядра (без друку протоколу). Час — середній на прогін, в одному потоці,
 * після трьох прогонів для розігріву JIT; залежить від комп'ютера.
 */
public final class KernelSpeedBenchmark {

    private static final double SIMULATION_TIME = 3.5e6;
    private static final double WARMUP_TIME = 2e4;
    private static final int RUNS = 10;

    private KernelSpeedBenchmark() {
    }

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "T_mod = %.0f min, %d runs per point, %s, Java %s%n", SIMULATION_TIME, RUNS,
                System.getProperty("os.arch"), Runtime.version());
        System.out.println("s  gamma  PetriSimulator, ms  PetriObjModel.go, ms  ratio");
        for (double gamma : new double[] {0.4, 0.7}) {
            for (Strategy strategy : Strategy.values()) {
                ModelParameters parameters = ModelParameters.ofIntensityFactor(strategy, gamma);
                for (int i = 0; i < 3; i++) {
                    simulator(parameters, i);
                    kernel(parameters, i);
                }
                long t0 = System.nanoTime();
                for (int i = 0; i < RUNS; i++) {
                    simulator(parameters, 100 + i);
                }
                long t1 = System.nanoTime();
                for (int i = 0; i < RUNS; i++) {
                    kernel(parameters, 100 + i);
                }
                long t2 = System.nanoTime();
                double own = (t1 - t0) / 1e6 / RUNS;
                double base = (t2 - t1) / 1e6 / RUNS;
                System.out.printf(Locale.ROOT, "%d  %.1f  %18.0f  %20.0f  %5.2f%n", strategy.number(), gamma, own, base,
                        base / own);
            }
        }
    }

    private static void simulator(ModelParameters parameters, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        new PetriSimulator(B14Net.create(parameters, random).petriNet(), random).run(SIMULATION_TIME, WARMUP_TIME);
    }

    private static void kernel(ModelParameters parameters, long seed) {
        PetriSim sim = new PetriSim(B14Net.create(parameters, new SplittableRandom(seed)).petriNet());
        PetriObjModel model = new PetriObjModel(new ArrayList<>(List.of(sim)));
        model.setIsProtokol(false);
        model.go(SIMULATION_TIME);
    }
}
