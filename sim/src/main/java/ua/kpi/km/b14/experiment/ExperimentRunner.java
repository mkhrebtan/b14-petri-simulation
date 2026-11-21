package ua.kpi.km.b14.experiment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import ua.kpi.km.b14.io.CsvTable;
import ua.kpi.km.b14.model.B14Simulation;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.RunSettings;
import ua.kpi.km.b14.model.SimulationResult;
import ua.kpi.km.b14.model.Strategy;
import ua.kpi.km.b14.model.TaskType;

/**
 * Експериментальне дослідження моделі (розділ 4 пояснювальної записки). Шість серій:
 * <ol>
 *   <li>траєкторії відгуків W(t), L_q(t), W_q(t), усереднених на [0; t], для кількох
 *       незалежних прогонів — час моделювання T_мод за критерієм (3.1) посібника; поточна
 *       довжина черги, усереднена за ансамблем прогонів, — перехідний період T_п;</li>
 *   <li>пілотна серія з великою кількістю прогонів у найбільш навантажених точках — оцінка
 *       стандартного відхилення відгуків, гістограма, кількість прогонів p;</li>
 *   <li>повний факторний план «стратегія s (5 рівнів) × коефіцієнт інтенсивності γ»
 *       з p прогонами в кожній точці — дисперсійний і регресійний аналіз;</li>
 *   <li>пробний запуск пропозиції розділу 5 — обсяг пам'яті M = 3: план «s × γ» до γ = 1
 *       (умова задачі) і траєкторії середніх за γ = 1 для перевірки критерію (3.1) посібника;</li>
 *   <li>межа області стійкості — рівні γ = 0,75 і 0,8 понад план: траєкторії середніх і прогони
 *       з T_мод для коефіцієнта використання пам'яті;</li>
 *   <li>зміна співвідношення потоків — лише λ_C множиться на γ_C за незмінних λ_A, λ_B.</li>
 * </ol>
 * Кожен прогін має власний seed; прогони незалежні, тому виконуються паралельно, а результат
 * від порядку виконання не залежить. Аналіз CSV — скрипти Python у {@code experiments/}.
 */
public final class ExperimentRunner {

    public static final long SEED = 20261119L;

    /**
     * Серія 1а: довгі траєкторії середніх на [0; t] — критерій (3.1) посібника → T_мод.
     * Серія 1б: короткі траєкторії, ансамбль прогонів — поточна черга від часу → T_п.
     * Серія 1в: γ = 1 (умова задачі) — ілюстрація нестійкого режиму.
     */
    static final double[] LONG_GAMMAS = {0.4, 0.7};
    static final int LONG_RUNS = 5;
    static final double LONG_HORIZON = 5_000_000;
    static final double LONG_STEP = 25_000;
    static final double SHORT_GAMMA = 0.7;
    static final int SHORT_RUNS = 50;
    static final double SHORT_HORIZON = 60_000;
    static final double SHORT_STEP = 250;
    static final double UNSTABLE_GAMMA = 1.0;
    static final int UNSTABLE_RUNS = 5;
    static final double UNSTABLE_HORIZON = 200_000;
    static final double UNSTABLE_STEP = 1_000;

    /** Серія 2: найбільш навантажений рівень γ. */
    static final double PILOT_GAMMA = 0.7;
    static final int PILOT_RUNS = 100;

    /** Серія 3: рівні γ (крок 0,05 на [0,4; 0,7]). */
    static final double[] FACTORIAL_GAMMAS = {0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70};

    /** Серія 5: пропозиція — обсяг пам'яті M = 3, рівні γ до умови задачі. */
    static final int IMPROVED_MEMORY = 3;
    static final double[] IMPROVED_GAMMAS = {0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
    static final double IMPROVED_TRAJECTORY_GAMMA = 1.0;

    /** Серії 6, 16: межа області стійкості — рівні γ понад план. */
    static final double[] BOUNDARY_GAMMAS = {0.75, 0.80};
    static final int BOUNDARY_RUNS = 10;
    /** Горизонт на пів кроку більший за 8·10^6 хв, щоб остання точка траєкторії припадала на 8·10^6 хв. */
    static final double BOUNDARY_HORIZON = 8_050_000;
    static final double BOUNDARY_STEP = 100_000;

    /** Серія 7: λ_C = γ_C·γ·λ_C^0, λ_A і λ_B — за γ = 0,6; стратегії 1 і 4 (найкращі у своїх групах). */
    static final double FLOW_GAMMA = 0.6;
    static final double[] FLOW_C_FACTORS = {0.5, 1.0, 1.25};
    static final Strategy[] FLOW_STRATEGIES = {Strategy.S1, Strategy.S4};

    private static final String[] RUN_HEADER = {"s", "ordering", "gamma", "rep", "seed",
            "W", "Lq", "Wq", "W_A", "W_B", "W_C", "Wq_A", "Wq_B", "Wq_C", "Lq_A", "Lq_B", "Lq_C",
            "U", "reserved_idle", "lambda_hat", "T_mod", "T_w"};
    /** Серія 5: той самий формат + стовпець обсягу пам'яті M. */
    private static final String[] IMPROVED_HEADER = Stream.concat(Arrays.stream(RUN_HEADER), Stream.of("M"))
            .toArray(String[]::new);
    /** Серія 7: той самий формат + множник γ_C і інтенсивність λ_C. */
    private static final String[] FLOW_HEADER = Stream.concat(Arrays.stream(RUN_HEADER),
            Stream.of("gamma_C", "lambda_C")).toArray(String[]::new);

    /** Серія 1а → trajectories_long.csv. */
    public CsvTable longTrajectories() {
        List<double[]> points = new ArrayList<>();
        for (double gamma : LONG_GAMMAS) {
            for (Strategy s : Strategy.values()) {
                points.add(new double[]{s.number(), gamma});
            }
        }
        return trajectoryTable(11, points, ModelParameters.DEFAULT_MEMORY, LONG_RUNS, LONG_HORIZON, LONG_STEP);
    }

    /** Серія 1б → trajectories_short.csv. */
    public CsvTable shortTrajectories() {
        List<double[]> points = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            points.add(new double[]{s.number(), SHORT_GAMMA});
        }
        return trajectoryTable(12, points, ModelParameters.DEFAULT_MEMORY, SHORT_RUNS, SHORT_HORIZON, SHORT_STEP);
    }

    /** Серія 1в → trajectories_unstable.csv. */
    public CsvTable unstableTrajectories() {
        List<double[]> points = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            points.add(new double[]{s.number(), UNSTABLE_GAMMA});
        }
        return trajectoryTable(13, points, ModelParameters.DEFAULT_MEMORY, UNSTABLE_RUNS, UNSTABLE_HORIZON,
                UNSTABLE_STEP);
    }

    /** Серія 1д (номер у seed — 16) → trajectories_boundary.csv: γ = 0,75 і 0,8, траєкторії середніх на довгому горизонті. */
    public CsvTable boundaryTrajectories() {
        List<double[]> points = new ArrayList<>();
        for (double gamma : BOUNDARY_GAMMAS) {
            for (Strategy s : Strategy.values()) {
                points.add(new double[]{s.number(), gamma});
            }
        }
        return trajectoryTable(16, points, ModelParameters.DEFAULT_MEMORY, LONG_RUNS, BOUNDARY_HORIZON,
                BOUNDARY_STEP);
    }

    /** Серія 1г → trajectories_improved.csv: M = 3, γ = 1, траєкторії середніх для критерію (3.1) посібника. */
    public CsvTable improvedTrajectories() {
        List<double[]> points = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            points.add(new double[]{s.number(), IMPROVED_TRAJECTORY_GAMMA});
        }
        return trajectoryTable(15, points, IMPROVED_MEMORY, LONG_RUNS, LONG_HORIZON, LONG_STEP);
    }

    private static CsvTable trajectoryTable(int series, List<double[]> points, int memory, int runs,
                                            double horizon, double step) {
        CsvTable table = new CsvTable("s", "gamma", "run", "seed", "t", "W", "Lq", "Wq", "Lq_now");
        for (int cell = 0; cell < points.size(); cell++) {
            Strategy s = Strategy.byNumber((int) points.get(cell)[0]);
            double gamma = points.get(cell)[1];
            int c = cell;
            List<List<ResponseTrajectory.Point>> trajectories = IntStream.range(0, runs).parallel()
                    .mapToObj(k -> trajectory(s, gamma, memory, horizon, step, seed(series, c, k)))
                    .toList();
            for (int k = 0; k < runs; k++) {
                for (ResponseTrajectory.Point p : trajectories.get(k)) {
                    table.addRow(s.number(), gamma, k + 1, seed(series, c, k), p.time(),
                            p.sojournTime(), p.queueLength(), p.waitingTime(), p.currentQueue());
                }
            }
        }
        return table;
    }

    /** Серія 2 → pilot.csv: {@value #PILOT_RUNS} прогонів для кожної стратегії при γ = 0,7. */
    public CsvTable pilotTable(double simulationTime, double warmupTime) {
        CsvTable table = new CsvTable(RUN_HEADER);
        Strategy[] strategies = Strategy.values();
        for (int cell = 0; cell < strategies.length; cell++) {
            runCell(table, 2, cell, strategies[cell], PILOT_GAMMA, ModelParameters.DEFAULT_MEMORY, PILOT_RUNS,
                    simulationTime, warmupTime);
        }
        return table;
    }

    /** Серія 3 → factorial.csv: план 5 × 7, по {@code runs} прогонів у точці. */
    public CsvTable factorialTable(double simulationTime, double warmupTime, int runs) {
        CsvTable table = new CsvTable(RUN_HEADER);
        int cell = 0;
        for (Strategy s : Strategy.values()) {
            for (double gamma : FACTORIAL_GAMMAS) {
                runCell(table, 3, cell++, s, gamma, ModelParameters.DEFAULT_MEMORY, runs, simulationTime,
                        warmupTime);
            }
        }
        return table;
    }

    /** Серія 5 → improvement.csv: M = 3, план 5 × 7 (γ до 1,0), по {@code runs} прогонів у точці. */
    public CsvTable improvementTable(double simulationTime, double warmupTime, int runs) {
        CsvTable table = new CsvTable(IMPROVED_HEADER);
        int cell = 0;
        for (Strategy s : Strategy.values()) {
            for (double gamma : IMPROVED_GAMMAS) {
                runCell(table, 5, cell++, s, gamma, IMPROVED_MEMORY, runs, simulationTime, warmupTime);
            }
        }
        return table;
    }

    /** Серія 6 → boundary.csv: γ = 0,75 і 0,8, {@value #BOUNDARY_RUNS} прогонів у точці. */
    public CsvTable boundaryTable(double simulationTime, double warmupTime) {
        CsvTable table = new CsvTable(RUN_HEADER);
        int cell = 0;
        for (double gamma : BOUNDARY_GAMMAS) {
            for (Strategy s : Strategy.values()) {
                runCell(table, 6, cell++, s, gamma, ModelParameters.DEFAULT_MEMORY, BOUNDARY_RUNS, simulationTime,
                        warmupTime);
            }
        }
        return table;
    }

    /** Серія 7 → flows.csv: змінюється лише потік C, по {@code runs} прогонів у точці. */
    public CsvTable flowTable(double simulationTime, double warmupTime, int runs) {
        CsvTable table = new CsvTable(FLOW_HEADER);
        int cell = 0;
        for (Strategy s : FLOW_STRATEGIES) {
            for (double factor : FLOW_C_FACTORS) {
                double rateC = factor * FLOW_GAMMA * TaskType.C.baseArrivalRate();
                ModelParameters parameters = ModelParameters.ofIntensityFactor(s, FLOW_GAMMA)
                        .withArrivalRate(TaskType.C, rateC);
                runCell(table, 7, cell++, parameters, FLOW_GAMMA, runs, simulationTime, warmupTime, factor, rateC);
            }
        }
        return table;
    }

    /**
     * seed прогону k у точці плану {@code cell} серії {@code series} (траєкторії — 11-13, 15, 16): усі прогони серій
     * мають різні seed (незалежні спостереження для дисперсійного аналізу).
     */
    static long seed(int series, int cell, int run) {
        return SEED + 1_000_000L * series + 1_000L * cell + run;
    }

    private static List<ResponseTrajectory.Point> trajectory(Strategy s, double gamma, int memory, double horizon,
                                                             double step, long seed) {
        ResponseTrajectory[] trajectory = new ResponseTrajectory[1];
        B14Simulation.run(ModelParameters.ofIntensityFactor(s, gamma).withMemory(memory),
                new RunSettings(horizon, 0, seed),
                net -> {
                    trajectory[0] = new ResponseTrajectory(net, step);
                    return List.of(trajectory[0]);
                });
        return trajectory[0].points();
    }

    private static void runCell(CsvTable table, int series, int cell, Strategy s, double gamma, int memory,
                                int runs, double simulationTime, double warmupTime) {
        runCell(table, series, cell, ModelParameters.ofIntensityFactor(s, gamma).withMemory(memory), gamma, runs,
                simulationTime, warmupTime);
    }

    /** Точка плану з довільними параметрами; {@code extra} — додаткові стовпці рядка. */
    private static void runCell(CsvTable table, int series, int cell, ModelParameters parameters, double gamma,
                                int runs, double simulationTime, double warmupTime, Object... extra) {
        Strategy s = parameters.strategy();
        int memory = parameters.memory();
        List<SimulationResult> results = IntStream.range(0, runs).parallel()
                .mapToObj(k -> B14Simulation.run(parameters,
                        new RunSettings(simulationTime, warmupTime, seed(series, cell, k))))
                .toList();
        for (int k = 0; k < runs; k++) {
            SimulationResult r = results.get(k);
            double lambdaHat = 0;
            for (TaskType t : TaskType.values()) {
                lambdaHat += r.of(t).arrivalRate();
            }
            List<Object> row = new ArrayList<>(List.of(s.number(), s.ordering(), gamma, k + 1, r.settings().seed(),
                    r.sojournTime(), r.queueLength(), r.waitingTime(),
                    r.of(TaskType.A).sojournTime(), r.of(TaskType.B).sojournTime(), r.of(TaskType.C).sojournTime(),
                    r.of(TaskType.A).waitingTime(), r.of(TaskType.B).waitingTime(), r.of(TaskType.C).waitingTime(),
                    r.of(TaskType.A).queueLength(), r.of(TaskType.B).queueLength(), r.of(TaskType.C).queueLength(),
                    r.utilization(), r.reservedIdle(), lambdaHat, simulationTime, warmupTime));
            if (memory != ModelParameters.DEFAULT_MEMORY) {
                row.add(memory);
            }
            row.addAll(List.of(extra));
            table.addRow(row.toArray());
        }
    }

    public void run(String series, Path outputDirectory, double simulationTime, double warmupTime, int runs) {
        long start = System.nanoTime();
        switch (series) {
            case "transient" -> {
                write(longTrajectories(), outputDirectory.resolve("trajectories_long.csv"));
                write(shortTrajectories(), outputDirectory.resolve("trajectories_short.csv"));
                write(unstableTrajectories(), outputDirectory.resolve("trajectories_unstable.csv"));
            }
            case "pilot" -> write(pilotTable(simulationTime, warmupTime), outputDirectory.resolve("pilot.csv"));
            case "factorial" -> write(factorialTable(simulationTime, warmupTime, runs),
                    outputDirectory.resolve("factorial.csv"));
            case "improvement" -> {
                write(improvedTrajectories(), outputDirectory.resolve("trajectories_improved.csv"));
                write(improvementTable(simulationTime, warmupTime, runs), outputDirectory.resolve("improvement.csv"));
            }
            case "boundary" -> {
                write(boundaryTrajectories(), outputDirectory.resolve("trajectories_boundary.csv"));
                write(boundaryTable(simulationTime, warmupTime), outputDirectory.resolve("boundary.csv"));
            }
            case "flows" -> write(flowTable(simulationTime, warmupTime, runs), outputDirectory.resolve("flows.csv"));
            default -> throw new IllegalArgumentException("Невідома серія: " + series);
        }
        String conditions = series.equals("transient")
                ? String.format("горизонти %.0f / %.0f / %.0f хв, статистика від 0",
                        LONG_HORIZON, SHORT_HORIZON, UNSTABLE_HORIZON)
                : String.format("T_мод = %.0f хв, T_п = %.0f хв", simulationTime, warmupTime);
        System.out.printf("Серія %s: %s, seed = %d + номер серії/точки/прогону, %.1f с%n",
                series, conditions, SEED, (System.nanoTime() - start) / 1e9);
    }

    private static void write(CsvTable table, Path file) {
        table.write(file);
        System.out.println("→ " + file);
    }
}
