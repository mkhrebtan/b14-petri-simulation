package ua.kpi.km.b14.petri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import ua.stetsenkoinna.petriobj.PetriNet;
import ua.stetsenkoinna.petriobj.PetriP;
import ua.stetsenkoinna.petriobj.PetriT;

class PetriSimulatorTest {

    private static final SplittableRandom ANY = new SplittableRandom(1);

    @Test
    void deterministicCycleProducesExactCountsAndMeans() {
        // Джерело з d = 2 і обслуговування з d = 1: кожні 2 хв одне завдання, канал зайнятий половину часу.
        PetriNetBuilder b = new PetriNetBuilder("cycle", ANY);
        PetriP gen = b.place("Gen", 1);
        PetriP queue = b.place("Q", 0);
        PetriP free = b.place("Free", 1);
        PetriP done = b.place("Done", 0);
        StochasticTransition arrive = b.transition("Arr").delay(Delay.constant(2))
                .input(gen).output(gen).output(queue).add();
        StochasticTransition serve = b.transition("Serv").delay(Delay.constant(1))
                .input(queue).input(free).output(free).output(done).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);

        sim.run(100, 0);

        assertEquals(50, arrive.completions());
        assertEquals(49, serve.completions()); // завдання, що надійшло в t = 100, ще не почало обслуговування
        assertEquals(0.49, serve.getMean(), 1e-12); // зайнято [2;3], [4;5], …, [98;99]
        assertEquals(0.0, queue.getMean(), 1e-12);
        assertEquals(49, done.getMark());
        assertEquals(100.0, sim.getCurrentTime());
    }

    @Test
    void statisticsStartAfterWarmup() {
        PetriNetBuilder b = new PetriNetBuilder("warmup", ANY);
        PetriP gen = b.place("Gen", 1);
        PetriP sink = b.place("Sink", 0);
        StochasticTransition arrive = b.transition("Arr").delay(Delay.constant(1))
                .input(gen).output(gen).output(sink).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);

        sim.run(10, 4.5);

        assertEquals(6, arrive.completions()); // моменти 5…10
        assertEquals(1.0, arrive.getMean(), 1e-12);
        // на [4,5; 10]: маркірування 4 протягом 0,5 хв, далі 5…9 по 1 хв
        assertEquals((4 * 0.5 + 5 + 6 + 7 + 8 + 9) / 5.5, sink.getMean(), 1e-12);
    }

    @Test
    void higherPriorityWinsConflict() {
        PetriNetBuilder b = new PetriNetBuilder("priority", ANY);
        PetriP token = b.place("P", 1);
        PetriP low = b.place("Low", 0);
        PetriP high = b.place("High", 0);
        b.transition("TLow").priority(1).input(token).output(low).add();
        b.transition("THigh").priority(2).input(token).output(high).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);

        sim.run(1, 0);

        assertEquals(1, high.getMark());
        assertEquals(0, low.getMark());
    }

    @Test
    void informationalArcChecksButDoesNotConsume() {
        PetriNetBuilder b = new PetriNetBuilder("informational", ANY);
        PetriP flag = b.place("Flag", 1);
        PetriP pool = b.place("Pool", 3);
        PetriP out = b.place("Out", 0);
        b.transition("T").informationalInput(flag, 1).input(pool).output(out).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);

        sim.run(1, 0);

        assertEquals(1, flag.getMark());
        assertEquals(3, out.getMark());
    }

    @Test
    void equalPriorityConflictFollowsLaunchProbabilities() {
        ConflictResolver resolver = new ConflictResolver(new SplittableRandom(42));
        PetriNetBuilder b = new PetriNetBuilder("probability", ANY);
        PetriP p = b.place("P", 1);
        PetriP out = b.place("Out", 0);
        StochasticTransition first = b.transition("T1").priority(1).probability(0.25).input(p).output(out).add();
        StochasticTransition second = b.transition("T2").priority(1).probability(0.75).input(p).output(out).add();
        List<StochasticTransition> group = List.of(first, second);

        int firstWins = 0;
        int trials = 200_000;
        for (int i = 0; i < trials; i++) {
            if (resolver.choose(group) == first) {
                firstWins++;
            }
        }
        assertEquals(0.25, firstWins / (double) trials, 0.005);
    }

    @Test
    void singleTopPriorityTransitionIsChosenWithoutDraw() {
        ConflictResolver resolver = new ConflictResolver(new SplittableRandom(1));
        PetriNetBuilder b = new PetriNetBuilder("top", ANY);
        PetriP p = b.place("P", 1);
        PetriP out = b.place("Out", 0);
        StochasticTransition top = b.transition("Top").priority(5).probability(0.1).input(p).output(out).add();
        StochasticTransition other = b.transition("Other").priority(1).input(p).output(out).add();
        assertEquals(top, resolver.choose(List.of(top, other)));
    }

    @Test
    void listenersSeeEveryStateChangeAndFiring() {
        PetriNetBuilder b = new PetriNetBuilder("listener", ANY);
        PetriP gen = b.place("Gen", 1);
        b.transition("Arr").delay(Delay.constant(1)).input(gen).output(gen).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);
        List<Double> states = new ArrayList<>();
        List<String> firings = new ArrayList<>();
        sim.addListener(new SimulationListener() {
            @Override
            public void onStateChanged(double t) {
                states.add(t);
            }

            @Override
            public void onTransitionStarted(double t, PetriT tr) {
                firings.add("+" + tr.getName() + "@" + t);
            }

            @Override
            public void onTransitionCompleted(double t, PetriT tr) {
                firings.add("-" + tr.getName() + "@" + t);
            }
        });

        sim.run(2, 0);

        assertEquals(List.of(0.0, 1.0, 2.0), states);
        assertEquals(List.of("+Arr@0.0", "-Arr@1.0", "+Arr@1.0", "-Arr@2.0", "+Arr@2.0"), firings);
    }

    @Test
    void sameSeedGivesSameRunWithoutMathRandom() {
        assertEquals(randomRun(7), randomRun(7));
    }

    /** Мережа з експоненційними затримками й рівнопріоритетним конфліктом: λ = 1, два «сервери». */
    private static List<Long> randomRun(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        PetriNetBuilder b = new PetriNetBuilder("random", random);
        PetriP gen = b.place("Gen", 1);
        PetriP queue = b.place("Q", 0);
        PetriP done = b.place("Done", 0);
        b.transition("Arr").delay(Delay.exponential(1)).input(gen).output(gen).output(queue).add();
        StochasticTransition s1 = b.transition("S1").delay(Delay.exponential(0.9)).probability(0.5)
                .input(queue).output(done).add();
        StochasticTransition s2 = b.transition("S2").delay(Delay.exponential(0.9)).probability(0.5)
                .input(queue).output(done).add();
        new PetriSimulator(b.build(), random).run(1_000, 0);
        return List.of(s1.completions(), s2.completions(), (long) done.getMark());
    }

    @Test
    void numberingDoesNotDependOnKernelCounters() {
        new PetriP("stray", 0); // зсуває статичний лічильник номерів позицій ядра
        PetriNetBuilder b = new PetriNetBuilder("numbering", ANY);
        PetriP a = b.place("A", 2);
        PetriP c = b.place("C", 0);
        b.transition("Move").input(a).output(c).add();
        PetriSimulator sim = new PetriSimulator(b.build(), ANY);

        sim.run(1, 0);

        assertEquals(0, a.getMark());
        assertEquals(2, c.getMark());
    }

    @Test
    void builderRejectsInvalidStructure() {
        PetriNetBuilder other = new PetriNetBuilder("other", ANY);
        PetriP foreign = other.place("X", 1);
        PetriNetBuilder b = new PetriNetBuilder("invalid", ANY);
        PetriP p = b.place("P", 1);

        assertThrows(IllegalArgumentException.class, () -> b.place("p", 0)); // назви без урахування регістру
        assertThrows(IllegalArgumentException.class, () -> b.transition("T").input(foreign));
        assertThrows(IllegalArgumentException.class, () -> b.transition("T").input(p, 0));
        assertThrows(IllegalArgumentException.class, () -> b.transition("T").probability(1.5));
        assertThrows(IllegalArgumentException.class, () -> b.transition("T").informationalInput(p, 1).add());
        b.build();
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void simulatorRunsOnceAndTransitionsAreNotCloneable() {
        PetriNetBuilder b = new PetriNetBuilder("once", ANY);
        PetriP p = b.place("P", 1);
        StochasticTransition t = b.transition("T").input(p).output(p).delay(Delay.constant(1)).add();
        PetriNet net = b.build();
        PetriSimulator sim = new PetriSimulator(net, ANY);
        sim.run(1, 0);

        assertThrows(IllegalStateException.class, () -> sim.run(1, 0));
        assertThrows(CloneNotSupportedException.class, t::clone);
    }

    @Test
    void exponentialDelayHasRequestedMean() {
        Delay delay = Delay.exponential(4);
        SplittableRandom random = new SplittableRandom(3);
        double sum = 0;
        int n = 500_000;
        for (int i = 0; i < n; i++) {
            sum += delay.sample(random);
        }
        assertEquals(4.0, sum / n, 0.03);
    }
}
