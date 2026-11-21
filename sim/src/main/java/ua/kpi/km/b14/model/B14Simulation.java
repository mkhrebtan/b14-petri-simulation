package ua.kpi.km.b14.model;

import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;

import ua.kpi.km.b14.petri.PetriSimulator;
import ua.kpi.km.b14.petri.SimulationListener;

/**
 * Точка входу в імітаційну модель B14: один прогін = новий генератор із заданим seed, нова
 * мережа в початковому стані (затримки переходів і розв'язання конфліктів — з цього
 * генератора), імітація до T_мод, обчислення відгуків.
 */
public final class B14Simulation {

    private B14Simulation() {
    }

    public static SimulationResult run(ModelParameters parameters, RunSettings settings) {
        return run(parameters, settings, net -> List.of());
    }

    /**
     * @param listenerFactory створює спостерігачів для мережі прогону (перевірка інваріанту,
     *                        запис траєкторій відгуків тощо)
     */
    public static SimulationResult run(ModelParameters parameters, RunSettings settings,
                                       Function<B14Net, List<SimulationListener>> listenerFactory) {
        SplittableRandom random = new SplittableRandom(settings.seed());
        B14Net net = B14Net.create(parameters, random);
        PetriSimulator simulator = new PetriSimulator(net.petriNet(), random);
        listenerFactory.apply(net).forEach(simulator::addListener);
        simulator.run(settings.simulationTime(), settings.warmupTime());
        return OutputCalculator.calculate(net, parameters, settings);
    }
}
