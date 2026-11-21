package ua.kpi.km.b14.petri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ua.kpi.km.b14.model.B14Net;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.Strategy;

import ua.stetsenkoinna.petriobj.PetriNet;
import ua.stetsenkoinna.petriobj.PetriObjModel;
import ua.stetsenkoinna.petriobj.PetriP;
import ua.stetsenkoinna.petriobj.PetriSim;
import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Еквівалентність {@link PetriSimulator} і незміненого циклу {@code PetriObjModel.go} ядра:
 * та сама мережа без розігрування конфліктів (детерміновані затримки, різні пріоритети,
 * інформаційна дуга, кратні дуги, багатоканальність) за {@code T_п = 0} дає однакові
 * маркірування, кількості активних каналів і середні значення. Перевизначений
 * {@link PetriSimulator#findActiveT} у мережі B14 повертає той самий список, що й метод ядра.
 */
class KernelEquivalenceTest {

    private static final double SIMULATION_TIME = 1_000;

    @Test
    void runMatchesKernelPetriObjModel() {
        PetriNet kernelNet = buildNet();
        PetriObjModel model = new PetriObjModel(new ArrayList<>(List.of(new PetriSim(kernelNet))));
        model.setIsProtokol(false);
        model.go(SIMULATION_TIME);

        PetriNet ourNet = buildNet();
        new PetriSimulator(ourNet, new SplittableRandom(1)).run(SIMULATION_TIME, 0);

        assertSameState(kernelNet, ourNet);
        for (PetriT t : kernelNet.getListT()) { // мережа справді працювала: кожен перехід мав активні канали
            assertTrue(t.getMean() > 0 || t.getParameter() == 0, t.getName());
        }
    }

    /**
     * Після кожного входу й виходу маркерів список активних переходів {@link PetriSimulator}
     * збігається зі списком {@code PetriSim.findActiveT} ядра для тієї самої мережі (ті самі
     * переходи в тому самому порядку) — для всіх стратегій і обох обсягів пам'яті.
     */
    @ParameterizedTest
    @CsvSource({"1,2", "2,2", "3,2", "4,2", "5,2", "1,3", "3,3", "5,3"})
    void findActiveTMatchesKernel(int strategy, int memory) {
        SplittableRandom random = new SplittableRandom(5);
        PetriNet net = B14Net.create(ModelParameters.ofIntensityFactor(Strategy.byNumber(strategy), 0.7)
                .withMemory(memory), random).petriNet();
        PetriSimulator simulator = new PetriSimulator(net, random);
        PetriSim kernel = new PetriSim(net); // спільні з simulator масиви позицій і переходів
        int[] nonEmpty = {0};
        Runnable compare = () -> {
            List<PetriT> expected = kernel.findActiveT();
            assertEquals(expected, simulator.findActiveT());
            nonEmpty[0] += expected.isEmpty() ? 0 : 1;
        };
        simulator.addListener(new SimulationListener() {
            @Override
            public void onStateChanged(double t) {
                compare.run();
            }

            @Override
            public void onTransitionStarted(double t, PetriT transition) {
                compare.run();
            }

            @Override
            public void onTransitionCompleted(double t, PetriT transition) {
                compare.run();
            }
        });
        simulator.run(20_000, 0);
        assertTrue(nonEmpty[0] > 1_000, "мало станів з активними переходами: " + nonEmpty[0]);
    }

    private static void assertSameState(PetriNet expected, PetriNet actual) {
        PetriP[] ep = expected.getListP();
        PetriP[] ap = actual.getListP();
        for (int i = 0; i < ep.length; i++) {
            assertEquals(ep[i].getMark(), ap[i].getMark(), "маркірування " + ep[i].getName());
            assertEquals(ep[i].getMean(), ap[i].getMean(), 1e-9, "середнє " + ep[i].getName());
        }
        PetriT[] et = expected.getListT();
        PetriT[] at = actual.getListT();
        for (int i = 0; i < et.length; i++) {
            assertEquals(et[i].getBuffer(), at[i].getBuffer(), "канали " + et[i].getName());
            assertEquals(et[i].getMean(), at[i].getMean(), 1e-9, "середнє каналів " + et[i].getName());
        }
    }

    /**
     * Два потоки (A кожні 3 хв, C кожні 7 хв) на пам'ять із 3 одиниць: A займає 1 одиницю на
     * 4 хв (пріоритет 2), C — 2 одиниці на 5 хв через резервування ResC (пріоритет 1) з
     * інформаційною дугою з черги QC.
     */
    private static PetriNet buildNet() {
        PetriNetBuilder b = new PetriNetBuilder("equivalence", new SplittableRandom(1));
        PetriP genA = b.place("GenA", 1);
        PetriP qa = b.place("QA", 0);
        PetriP genC = b.place("GenC", 1);
        PetriP qc = b.place("QC", 0);
        PetriP mem = b.place("Mem", 3);
        PetriP rc = b.place("RC", 0);
        PetriP outA = b.place("OutA", 0);
        PetriP outC = b.place("OutC", 0);
        b.transition("ArrA").delay(Delay.constant(3)).input(genA).output(genA).output(qa).add();
        b.transition("ArrC").delay(Delay.constant(7)).input(genC).output(genC).output(qc).add();
        b.transition("ResC").priority(1).informationalInput(qc, 1).input(mem, 2).output(rc, 2).add();
        b.transition("ServA").delay(Delay.constant(4)).priority(2).input(qa).input(mem).output(mem).output(outA).add();
        b.transition("ServC").delay(Delay.constant(5)).input(qc).input(rc, 2).output(mem, 2).output(outC).add();
        return b.build();
    }
}
