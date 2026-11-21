package ua.kpi.km.b14.model;

import java.util.EnumMap;
import java.util.Map;

import ua.kpi.km.b14.petri.StochasticTransition;
import ua.stetsenkoinna.petriobj.PetriP;

/**
 * Обчислення вихідних характеристик зі статистики мережі за формулами 2.2–2.6 (розділ 2):
 * середні маркірування позицій черг ({@code PetriP.getMean}), середня кількість активних
 * каналів переходів виконання ({@code PetriT.getMean}) — обидві на інтервалі [T_п; T_мод],
 * кількість надходжень після перехідного періоду та закон Літтла.
 */
final class OutputCalculator {

    private OutputCalculator() {
    }

    static SimulationResult calculate(B14Net net, ModelParameters parameters, RunSettings settings) {
        double tau = settings.statisticsDuration();
        Map<TaskType, TypeMetrics> byType = new EnumMap<>(TaskType.class);
        double totalQueue = 0;
        double totalInSystem = 0;
        double totalArrivalRate = 0;
        double busyMemory = 0;

        for (TaskType type : TaskType.values()) {
            PetriP queue = net.queue(type);
            StochasticTransition service = net.service(type);
            StochasticTransition arrival = net.arrival(type);

            double lq = queue.getMean();
            double l = lq + service.getMean();
            long arrivals = arrival.completions();
            double lambda = arrivals / tau;
            byType.put(type, new TypeMetrics(lq, l, lambda, divide(lq, lambda), divide(l, lambda),
                    arrivals, service.completions(), queue.getObservedMax()));

            totalQueue += lq;
            totalInSystem += l;
            totalArrivalRate += lambda;
            busyMemory += type.memoryDemand() * service.getMean();
        }

        int capacity = net.memoryCapacity();
        return new SimulationResult(parameters, settings, byType,
                totalQueue,
                divide(totalQueue, totalArrivalRate),
                divide(totalInSystem, totalArrivalRate),
                busyMemory / capacity,
                net.reserved().getMean() / capacity);
    }

    /** Закон Літтла W = L / λ̂; для вимкненого потоку (λ̂ = 0) характеристика не визначена. */
    private static double divide(double numerator, double rate) {
        return rate > 0 ? numerator / rate : Double.NaN;
    }
}
