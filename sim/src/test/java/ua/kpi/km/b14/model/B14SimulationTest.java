package ua.kpi.km.b14.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ua.kpi.km.b14.verification.InvariantMonitor;

class B14SimulationTest {

    private static final RunSettings SHORT_RUN = new RunSettings(50_000, 5_000, 11);
    private static final RunSettings LONG_RUN = new RunSettings(300_000, 10_000, 11);

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void invariantsHoldAndUtilizationMatchesOfferedLoad(Strategy strategy) {
        ModelParameters p = ModelParameters.ofIntensityFactor(strategy, 0.6);
        SimulationResult r = B14Simulation.run(p, LONG_RUN,
                net -> List.of(new InvariantMonitor(net, strategy)));

        assertEquals(p.offeredLoad(), r.utilization(), 0.02);
        for (TaskType t : TaskType.values()) {
            TypeMetrics m = r.of(t);
            assertEquals(p.arrivalRate(t), m.arrivalRate(), 0.05 * p.arrivalRate(t));
            assertEquals(m.waitingTime() + t.meanServiceTime(), m.sojournTime(), 0.1 * m.sojournTime());
        }
    }

    @Test
    void sameSeedReproducesRunExactly() {
        ModelParameters p = ModelParameters.ofIntensityFactor(Strategy.S5, 0.6);
        assertEquals(B14Simulation.run(p, SHORT_RUN), B14Simulation.run(p, SHORT_RUN));
    }

    @Test
    void differentSeedsGiveDifferentRuns() {
        ModelParameters p = ModelParameters.ofIntensityFactor(Strategy.S5, 0.6);
        assertTrue(B14Simulation.run(p, SHORT_RUN).sojournTime()
                != B14Simulation.run(p, SHORT_RUN.withSeed(12)).sojournTime());
    }

    @Test
    void priorityOrderBetweenAandBIsRespected() {
        SimulationResult s1 = B14Simulation.run(ModelParameters.ofIntensityFactor(Strategy.S1, 0.6), SHORT_RUN);
        SimulationResult s2 = B14Simulation.run(ModelParameters.ofIntensityFactor(Strategy.S2, 0.6), SHORT_RUN);
        assertTrue(s1.of(TaskType.A).waitingTime() < s1.of(TaskType.B).waitingTime(), "s = 1: A > B");
        assertTrue(s2.of(TaskType.B).waitingTime() < s2.of(TaskType.A).waitingTime(), "s = 2: B > A");
    }

    @Test
    void disabledStreamProducesNoArrivals() {
        SimulationResult r = B14Simulation.run(ModelParameters.ofRates(Strategy.S3, 0, 0, 0.06), SHORT_RUN);
        assertEquals(0, r.of(TaskType.A).arrivals());
        assertTrue(Double.isNaN(r.of(TaskType.A).waitingTime()));
        assertEquals(0.6, r.utilization(), 0.03);
    }

    /** Пропозиція розділу 5: M = 3, γ = 1 — інваріанти (зокрема ліміту LimC) і стійкий режим. */
    @ParameterizedTest
    @EnumSource(Strategy.class)
    void extraMemoryKeepsInvariantsAndMakesBaseLoadStable(Strategy strategy) {
        ModelParameters p = ModelParameters.ofIntensityFactor(strategy, 1.0).withMemory(3);
        SimulationResult r = B14Simulation.run(p, LONG_RUN,
                net -> List.of(new InvariantMonitor(net, strategy)));

        assertEquals(p.offeredLoad(), r.utilization(), 0.02);
        assertTrue(r.queueLength() < 10, "черга стабілізується за M = 3");
        for (TaskType t : TaskType.values()) {
            assertEquals(p.arrivalRate(t), r.of(t).arrivalRate(), 0.05 * p.arrivalRate(t));
        }
    }

    /** Верифікація: 1/μ_C ×2 збільшує ρ = U, а W_C — щонайменше на приріст часу виконання. */
    @Test
    void longerServiceOfCIncreasesLoadAndSojournTime() {
        ModelParameters base = ModelParameters.ofIntensityFactor(Strategy.S1, 0.4);
        ModelParameters slowC = base.withMeanServiceTime(TaskType.C, 20);
        assertEquals(0.71, slowC.offeredLoad(), 1e-9);
        assertEquals(10, base.meanServiceTime(TaskType.C));

        SimulationResult r0 = B14Simulation.run(base, LONG_RUN);
        SimulationResult r1 = B14Simulation.run(slowC, LONG_RUN,
                net -> List.of(new InvariantMonitor(net, Strategy.S1)));
        assertEquals(slowC.offeredLoad(), r1.utilization(), 0.02);
        assertTrue(r1.of(TaskType.C).sojournTime() > r0.of(TaskType.C).sojournTime() + 10);
        assertTrue(r1.queueLength() > r0.queueLength());
    }

    @Test
    void singleChangeKeepsOtherVariables() {
        ModelParameters base = ModelParameters.ofIntensityFactor(Strategy.S3, 0.4);
        ModelParameters p = base.withArrivalRate(TaskType.C, 0.048);
        assertEquals(0.048, p.arrivalRate(TaskType.C), 1e-12);
        assertEquals(base.arrivalRate(TaskType.A), p.arrivalRate(TaskType.A));
        assertEquals(base.meanServiceTimes(), p.meanServiceTimes());
        assertEquals(base.memory(), p.memory());
    }
}
