package ua.kpi.km.b14.verification;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToDoubleFunction;
import java.util.stream.IntStream;

import ua.kpi.km.b14.io.CsvTable;
import ua.kpi.km.b14.io.TextFile;
import ua.kpi.km.b14.model.B14Simulation;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.RunSettings;
import ua.kpi.km.b14.model.SimulationResult;
import ua.kpi.km.b14.model.Strategy;
import ua.kpi.km.b14.model.TaskType;
import ua.kpi.km.b14.model.TypeMetrics;

/**
 * Верифікація моделі (підрозділ 3.4 пояснювальної записки). Чотири серії прогонів, у кожному з
 * яких {@link InvariantMonitor} перевіряє інваріант пам'яті та правила упорядкування після
 * кожної події:
 * <ol>
 *   <li>контрольні випадки з аналітичним розв'язком (у системі лише один потік → M/M/1 або M/M/2);</li>
 *   <li>зміна вхідних змінних s і γ — напрям зміни відгуків і збіг U з ρ = γ·ρ^0;</li>
 *   <li>зміна кожної вхідної змінної окремо (λ_i ×2, 1/μ_i ×2, M = 3) відносно базового рядка;</li>
 *   <li>γ = 1 (умова задачі) — відгуки зростають разом із T_мод (нестійкий режим).</li>
 * </ol>
 */
public final class VerificationRunner {

    public static final long SEED = 20261118L;
    public static final RunSettings ANALYTIC_RUN = new RunSettings(1_000_000, 10_000, SEED);
    static final int ANALYTIC_REPLICATIONS = 10;
    /** T_мод і T_п — як в експериментальному дослідженні (розділ 4). */
    public static final RunSettings VARIATION_RUN = new RunSettings(3_500_000, 20_000, SEED);
    static final int VARIATION_REPLICATIONS = 10;
    static final double[] GAMMAS = {0.4, 0.5, 0.6, 0.7};
    static final double BASE_GAMMA = 0.4;
    static final List<Strategy> SINGLE_CHANGE_STRATEGIES = List.of(Strategy.S1, Strategy.S3);
    static final double[] HORIZONS = {10_000, 20_000, 50_000, 100_000};
    static final double PROTOCOL_GAMMA = 0.6;
    static final double PROTOCOL_UNTIL = 300;

    private final LongAdder totalChecks = new LongAdder();

    /** Контрольний випадок: у моделі залишено лише потік типу {@code type}. */
    record AnalyticCase(String title, TaskType type, ModelParameters parameters, MmcQueue reference) {
    }

    static List<AnalyticCase> analyticCases() {
        return List.of(
                new AnalyticCase("лише C, M/M/1", TaskType.C,
                        ModelParameters.ofRates(Strategy.S3, 0, 0, 0.06), new MmcQueue(0.06, 0.1, 1)),
                new AnalyticCase("лише A, M/M/2", TaskType.A,
                        ModelParameters.ofRates(Strategy.S1, 0.2, 0, 0), new MmcQueue(0.2, 0.25, 2)),
                new AnalyticCase("лише A (2λ), M/M/2", TaskType.A,
                        ModelParameters.ofRates(Strategy.S1, 0.4, 0, 0), new MmcQueue(0.4, 0.25, 2)),
                new AnalyticCase("лише B, M/M/2", TaskType.B,
                        ModelParameters.ofRates(Strategy.S2, 0, 0.07, 0), new MmcQueue(0.07, 0.2, 2)));
    }

    /**
     * Для кожного випадку — {@value #ANALYTIC_REPLICATIONS} незалежних прогонів (seed = SEED + k);
     * перевіряється, чи потрапляє теоретичне значення в довірчий інтервал рівня 0,95.
     */
    public CsvTable analyticTable() {
        CsvTable table = new CsvTable("case", "type", "lambda", "mu", "servers", "measure",
                "theory", "model_mean", "ci_half_width", "rel_error_pct", "theory_in_ci", "replications");
        for (AnalyticCase c : analyticCases()) {
            TypeMetrics[] runs = new TypeMetrics[ANALYTIC_REPLICATIONS];
            for (int k = 0; k < runs.length; k++) {
                runs[k] = simulate(c.parameters(), ANALYTIC_RUN.withSeed(SEED + k)).of(c.type());
            }
            MmcQueue q = c.reference();
            addComparison(table, c, "Lq", q.queueLength(), runs, TypeMetrics::queueLength);
            addComparison(table, c, "Wq", q.waitingTime(), runs, TypeMetrics::waitingTime);
            addComparison(table, c, "W", q.sojournTime(), runs, TypeMetrics::sojournTime);
        }
        return table;
    }

    /**
     * Сітка s × γ: у кожній точці {@value #VARIATION_REPLICATIONS} прогонів (seed = SEED + k)
     * тривалістю T_мод з розділу 4; у таблиці — середні за прогонами.
     */
    public CsvTable variationTable() {
        CsvTable table = new CsvTable("s", "ordering", "gamma", "Lq", "Wq", "W",
                "Wq_A", "Wq_B", "Wq_C", "W_A", "W_B", "W_C", "Lq_A", "Lq_B", "Lq_C",
                "U", "rho_theory", "reserved_idle", "lambda_hat", "lambda", "W_ci_half_width", "replications");
        for (Strategy s : Strategy.values()) {
            for (double gamma : GAMMAS) {
                ModelParameters p = ModelParameters.ofIntensityFactor(s, gamma);
                Replications r = replicate(p);
                table.addRow(s.number(), s.ordering(), gamma, r.mean(SimulationResult::queueLength),
                        r.mean(SimulationResult::waitingTime), r.mean(SimulationResult::sojournTime),
                        r.mean(x -> x.of(TaskType.A).waitingTime()), r.mean(x -> x.of(TaskType.B).waitingTime()),
                        r.mean(x -> x.of(TaskType.C).waitingTime()),
                        r.mean(x -> x.of(TaskType.A).sojournTime()), r.mean(x -> x.of(TaskType.B).sojournTime()),
                        r.mean(x -> x.of(TaskType.C).sojournTime()),
                        r.mean(x -> x.of(TaskType.A).queueLength()), r.mean(x -> x.of(TaskType.B).queueLength()),
                        r.mean(x -> x.of(TaskType.C).queueLength()),
                        r.mean(SimulationResult::utilization), p.offeredLoad(), r.mean(SimulationResult::reservedIdle),
                        r.mean(VerificationRunner::totalArrivalRate), totalRate(p),
                        r.interval(SimulationResult::sojournTime).halfWidth(), VARIATION_REPLICATIONS);
            }
        }
        return table;
    }

    /** Зміна однієї вхідної змінної відносно базового рядка (решта змінних — як у базовому). */
    record SingleChange(String variable, String change, ModelParameters parameters) {
    }

    /**
     * Базовий рядок: γ = {@value #BASE_GAMMA} (λ_i = γ·λ_i^0), 1/μ_i і M з умови; далі по одному
     * рядку на кожну змінену вхідну змінну (λ_i ×2, 1/μ_i ×2, M = 3).
     */
    static List<SingleChange> singleChanges(Strategy s) {
        ModelParameters base = ModelParameters.ofIntensityFactor(s, BASE_GAMMA);
        List<SingleChange> changes = new ArrayList<>();
        changes.add(new SingleChange("base", "-", base));
        for (TaskType t : TaskType.values()) {
            changes.add(new SingleChange("lambda_" + t, "x2", base.withArrivalRate(t, 2 * base.arrivalRate(t))));
        }
        for (TaskType t : TaskType.values()) {
            changes.add(new SingleChange("ES_" + t, "x2", base.withMeanServiceTime(t, 2 * base.meanServiceTime(t))));
        }
        changes.add(new SingleChange("M", "3", base.withMemory(3)));
        return changes;
    }

    public CsvTable singleChangeTable() {
        CsvTable table = new CsvTable("s", "ordering", "variable", "change",
                "lambda_A", "lambda_B", "lambda_C", "ES_A", "ES_B", "ES_C", "M", "rho_theory", "U",
                "Lq", "Wq", "W", "Wq_A", "Wq_B", "Wq_C", "W_A", "W_B", "W_C", "Lq_A", "Lq_B", "Lq_C",
                "reserved_idle", "W_ci_half_width", "replications");
        for (Strategy s : SINGLE_CHANGE_STRATEGIES) {
            for (SingleChange c : singleChanges(s)) {
                ModelParameters p = c.parameters();
                Replications r = replicate(p);
                table.addRow(s.number(), s.ordering(), c.variable(), c.change(),
                        p.arrivalRate(TaskType.A), p.arrivalRate(TaskType.B), p.arrivalRate(TaskType.C),
                        p.meanServiceTime(TaskType.A), p.meanServiceTime(TaskType.B), p.meanServiceTime(TaskType.C),
                        p.memory(), p.offeredLoad(), r.mean(SimulationResult::utilization),
                        r.mean(SimulationResult::queueLength), r.mean(SimulationResult::waitingTime),
                        r.mean(SimulationResult::sojournTime),
                        r.mean(x -> x.of(TaskType.A).waitingTime()), r.mean(x -> x.of(TaskType.B).waitingTime()),
                        r.mean(x -> x.of(TaskType.C).waitingTime()),
                        r.mean(x -> x.of(TaskType.A).sojournTime()), r.mean(x -> x.of(TaskType.B).sojournTime()),
                        r.mean(x -> x.of(TaskType.C).sojournTime()),
                        r.mean(x -> x.of(TaskType.A).queueLength()), r.mean(x -> x.of(TaskType.B).queueLength()),
                        r.mean(x -> x.of(TaskType.C).queueLength()),
                        r.mean(SimulationResult::reservedIdle),
                        r.interval(SimulationResult::sojournTime).halfWidth(), VARIATION_REPLICATIONS);
            }
        }
        return table;
    }

    /** Результати незалежних прогонів однієї точки. */
    record Replications(List<SimulationResult> runs) {

        double mean(ToDoubleFunction<SimulationResult> response) {
            return interval(response).mean();
        }

        ConfidenceInterval interval(ToDoubleFunction<SimulationResult> response) {
            return ConfidenceInterval.of(runs.stream().mapToDouble(response).toArray());
        }
    }

    /** {@value #VARIATION_REPLICATIONS} прогонів VARIATION_RUN з seed = SEED + k (паралельно, порядок не впливає). */
    private Replications replicate(ModelParameters p) {
        return new Replications(IntStream.range(0, VARIATION_REPLICATIONS).parallel()
                .mapToObj(k -> simulate(p, VARIATION_RUN.withSeed(SEED + k)))
                .toList());
    }

    private static double totalArrivalRate(SimulationResult r) {
        double sum = 0;
        for (TaskType t : TaskType.values()) {
            sum += r.of(t).arrivalRate();
        }
        return sum;
    }

    private static double totalRate(ModelParameters p) {
        double sum = 0;
        for (TaskType t : TaskType.values()) {
            sum += p.arrivalRate(t);
        }
        return sum;
    }

    public CsvTable instabilityTable() {
        CsvTable table = new CsvTable("s", "gamma", "T_mod", "Lq", "Lq_A", "Lq_B", "Lq_C", "W", "max_Q_A", "max_Q_C");
        for (Strategy s : List.of(Strategy.S1, Strategy.S3)) {
            for (double horizon : HORIZONS) {
                SimulationResult r = simulate(ModelParameters.ofIntensityFactor(s, 1.0),
                        new RunSettings(horizon, 0, SEED));
                table.addRow(s.number(), 1.0, horizon, r.queueLength(),
                        r.of(TaskType.A).queueLength(), r.of(TaskType.B).queueLength(), r.of(TaskType.C).queueLength(),
                        r.sojournTime(), r.of(TaskType.A).maxQueueLength(), r.of(TaskType.C).maxQueueLength());
            }
        }
        return table;
    }

    /** Протокол подій прогону на інтервалі [0; until] (додаток до верифікації). */
    public List<String> protocol(Strategy strategy, double gamma, double until) {
        EventProtocol[] protocol = new EventProtocol[1];
        B14Simulation.run(ModelParameters.ofIntensityFactor(strategy, gamma), new RunSettings(until, 0, SEED), net -> {
            protocol[0] = new EventProtocol(net, until);
            return List.of(protocol[0], new InvariantMonitor(net, strategy));
        });
        return protocol[0].lines();
    }

    /** Кількість подій, після яких перевірено інваріанти, за всі прогони. */
    public long totalChecks() {
        return totalChecks.sum();
    }

    public void runAll(Path outputDirectory) {
        long started = System.nanoTime();
        report("Контрольні випадки (10 прогонів, T_мод = 10^6 хв, T_п = 10^4 хв)", analyticTable(),
                outputDirectory.resolve("analytic.csv"));
        report("Зміна s і γ (10 прогонів, T_мод = 3,5·10^6 хв, T_п = 2·10^4 хв)", variationTable(),
                outputDirectory.resolve("variation.csv"));
        report("Зміна кожної вхідної змінної окремо (10 прогонів, T_мод = 3,5·10^6 хв, T_п = 2·10^4 хв)",
                singleChangeTable(), outputDirectory.resolve("single_change.csv"));
        report("γ = 1, статистика від 0", instabilityTable(), outputDirectory.resolve("instability.csv"));
        for (Strategy s : List.of(Strategy.S1, Strategy.S3)) {
            Path file = outputDirectory.resolve("protocol_s" + s.number() + ".txt");
            TextFile.writeLines(file, protocol(s, PROTOCOL_GAMMA, PROTOCOL_UNTIL));
            System.out.println("== Протокол подій s = " + s.number() + " → " + file);
        }
        System.out.printf("Інваріанти перевірено після %,d подій — порушень немає (seed = %d), %.1f с%n",
                totalChecks.sum(), SEED, (System.nanoTime() - started) / 1e9);
    }

    private SimulationResult simulate(ModelParameters parameters, RunSettings settings) {
        InvariantMonitor[] monitor = new InvariantMonitor[1];
        SimulationResult result = B14Simulation.run(parameters, settings, net -> {
            monitor[0] = new InvariantMonitor(net, parameters.strategy());
            return List.of(monitor[0]);
        });
        totalChecks.add(monitor[0].checks());
        return result;
    }

    private static void addComparison(CsvTable table, AnalyticCase c, String measure, double theory,
                                      TypeMetrics[] runs, ToDoubleFunction<TypeMetrics> response) {
        double[] sample = new double[runs.length];
        for (int k = 0; k < runs.length; k++) {
            sample[k] = response.applyAsDouble(runs[k]);
        }
        ConfidenceInterval ci = ConfidenceInterval.of(sample);
        MmcQueue q = c.reference();
        table.addRow(c.title(), c.type(), q.arrivalRate(), q.serviceRate(), q.servers(), measure,
                theory, ci.mean(), ci.halfWidth(), 100 * Math.abs(ci.mean() - theory) / theory,
                ci.contains(theory) ? "yes" : "no", ci.size());
    }

    private static void report(String title, CsvTable table, Path file) {
        table.write(file);
        System.out.println("== " + title + " → " + file);
        System.out.println(table.toPrettyString());
        System.out.println();
    }
}
