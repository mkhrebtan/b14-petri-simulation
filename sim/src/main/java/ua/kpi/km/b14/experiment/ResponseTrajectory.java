package ua.kpi.km.b14.experiment;

import java.util.ArrayList;
import java.util.List;

import ua.kpi.km.b14.model.B14Net;
import ua.kpi.km.b14.model.TaskType;
import ua.kpi.km.b14.petri.SimulationListener;

/**
 * Траєкторія відгуків прогону для визначення перехідного періоду (формули 3.1-3.2 посібника):
 * у моменти t = h, 2h, ... записуються середні значення W(t), L_q(t), W_q(t), обчислені на
 * інтервалі [0; t], а також поточна довжина черги L_q^*(t) (сума маркерів у позиціях черг) — для
 * усереднення за ансамблем прогонів. Прогін має виконуватися з T_п = 0, тоді середні ядра ({@code getMean})
 * і лічильники надходжень охоплюють саме [0; t]. Значення записується в першій події, що
 * настала не раніше чергового моменту t: середні вже перераховано до цієї події, а поточна
 * черга береться зі стану до неї (між подіями стан мережі не змінюється).
 */
public final class ResponseTrajectory implements SimulationListener {

    /** Точка траєкторії: середні відгуки на [0; time]. */
    public record Point(double time, double sojournTime, double queueLength, double waitingTime,
                        int currentQueue) {
    }

    private final B14Net net;
    private final double step;
    private final List<Point> points = new ArrayList<>();
    private double nextCheckpoint;
    private int queueBeforeEvent;

    /**
     * @param net  мережа прогону
     * @param step крок h між моментами запису, хв
     */
    public ResponseTrajectory(B14Net net, double step) {
        if (!(step > 0)) {
            throw new IllegalArgumentException("Крок має бути додатним");
        }
        this.net = net;
        this.step = step;
        this.nextCheckpoint = step;
    }

    @Override
    public void onStateChanged(double currentTime) {
        while (currentTime >= nextCheckpoint) {
            points.add(snapshot(nextCheckpoint));
            nextCheckpoint += step;
        }
        queueBeforeEvent = currentQueue();
    }

    private int currentQueue() {
        int current = 0;
        for (TaskType type : TaskType.values()) {
            current += net.queue(type).getMark();
        }
        return current;
    }

    private Point snapshot(double time) {
        double queue = 0;
        double inSystem = 0;
        long arrivals = 0;
        for (TaskType type : TaskType.values()) {
            double lq = net.queue(type).getMean();
            queue += lq;
            inSystem += lq + net.service(type).getMean();
            arrivals += net.arrival(type).completions();
        }
        double rate = arrivals / time;
        return rate > 0
                ? new Point(time, inSystem / rate, queue, queue / rate, queueBeforeEvent)
                : new Point(time, Double.NaN, queue, Double.NaN, queueBeforeEvent);
    }

    public List<Point> points() {
        return List.copyOf(points);
    }
}
