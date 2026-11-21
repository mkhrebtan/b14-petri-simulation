package ua.kpi.km.b14.app;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ua.kpi.km.b14.experiment.ExperimentRunner;
import ua.kpi.km.b14.model.B14Simulation;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.RunSettings;
import ua.kpi.km.b14.model.SimulationResult;
import ua.kpi.km.b14.model.Strategy;
import ua.kpi.km.b14.model.TaskType;
import ua.kpi.km.b14.model.TypeMetrics;
import ua.kpi.km.b14.verification.VerificationRunner;

/**
 * Командний рядок моделі B14.
 *
 * <pre>
 *   run    [--s 3] [--gamma 0.6] [--memory 2] [--time 100000] [--warmup 10000] [--seed 1] — один прогін
 *   verify [--out ../experiments/verification]                                  — верифікація
 *   experiment transient|pilot|factorial|improvement|boundary|flows [--out ../experiments/results]
 *              [--time 1000000] [--warmup 20000] [--runs 20]                    — експерименти
 * </pre>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> options = parseOptions(args);
        switch (args[0]) {
            case "run" -> runOnce(options);
            case "verify" -> new VerificationRunner()
                    .runAll(Path.of(options.getOrDefault("out", "../experiments/verification")));
            case "experiment" -> runExperiment(args, options);
            default -> usage();
        }
    }

    private static void runOnce(Map<String, String> options) {
        Strategy strategy = Strategy.byNumber(Integer.parseInt(options.getOrDefault("s", "3")));
        double gamma = Double.parseDouble(options.getOrDefault("gamma", "0.6"));
        RunSettings settings = new RunSettings(
                Double.parseDouble(options.getOrDefault("time", "100000")),
                Double.parseDouble(options.getOrDefault("warmup", "10000")),
                Long.parseLong(options.getOrDefault("seed", "1")));
        int memory = Integer.parseInt(options.getOrDefault("memory", String.valueOf(ModelParameters.DEFAULT_MEMORY)));
        SimulationResult r = B14Simulation.run(
                ModelParameters.ofIntensityFactor(strategy, gamma).withMemory(memory), settings);

        System.out.printf(Locale.ROOT, "M = %d, s = %d (%s), gamma = %.3f, T_mod = %.0f min, T_w = %.0f min, seed = %d%n",
                memory, strategy.number(), strategy.ordering(), gamma, settings.simulationTime(), settings.warmupTime(),
                settings.seed());
        System.out.println("type      Lq        L   lambda^     Wq, min    W, min  arrivals  completed");
        for (TaskType t : TaskType.values()) {
            TypeMetrics m = r.of(t);
            System.out.printf(Locale.ROOT, "%-4s %8.4f %8.4f %9.5f %11.4f %9.4f %9d %10d%n", t, m.queueLength(),
                    m.inSystem(), m.arrivalRate(), m.waitingTime(), m.sojournTime(), m.arrivals(), m.completed());
        }
        System.out.printf(Locale.ROOT, "total Lq = %.4f, Wq = %.4f min, W = %.4f min, U = %.4f, RC idle = %.4f%n",
                r.queueLength(), r.waitingTime(), r.sojournTime(), r.utilization(), r.reservedIdle());
    }

    private static void runExperiment(String[] args, Map<String, String> options) {
        if (args.length < 2) {
            usage();
            return;
        }
        new ExperimentRunner().run(args[1],
                Path.of(options.getOrDefault("out", "../experiments/results")),
                Double.parseDouble(options.getOrDefault("time", "1000000")),
                Double.parseDouble(options.getOrDefault("warmup", "20000")),
                Integer.parseInt(options.getOrDefault("runs", "20")));
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        int first = args.length > 1 && args[0].equals("experiment") ? 2 : 1;
        for (int i = first; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Очікується --назва значення: " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
        }
        return options;
    }

    private static void usage() {
        System.out.println("""
                Використання:
                  run    [--s 1..5] [--gamma 0.6] [--memory 2] [--time 100000] [--warmup 10000] [--seed 1]
                  verify [--out ../experiments/verification]
                  experiment transient|pilot|factorial|improvement|boundary|flows [--out ../experiments/results]
                             [--time 1000000] [--warmup 20000] [--runs 20]""");
    }
}
