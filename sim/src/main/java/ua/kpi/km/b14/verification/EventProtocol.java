package ua.kpi.km.b14.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ua.kpi.km.b14.model.B14Net;
import ua.kpi.km.b14.model.TaskType;
import ua.kpi.km.b14.petri.SimulationListener;
import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Протокол подій: для кожного моменту часу — переходи, з яких вийшли маркери («−»), і
 * переходи, у які увійшли маркери («+»), та стан після події (довжини черг, вільна й
 * зарезервована пам'ять, кількість завдань на виконанні). Записується до моменту {@code until}.
 */
public final class EventProtocol implements SimulationListener {

    private final B14Net net;
    private final double until;
    private final List<String> lines = new ArrayList<>();
    private final List<String> completed = new ArrayList<>();
    private final List<String> started = new ArrayList<>();

    public EventProtocol(B14Net net, double until) {
        this.net = net;
        this.until = until;
        lines.add(String.format("%10s | %-28s | %-34s | %s", "t, хв", "вихід маркерів (−)",
                "вхід маркерів (+)", "QA QB QC Mem RC | nA nB nC"));
    }

    @Override
    public void onTransitionCompleted(double currentTime, PetriT transition) {
        completed.add(transition.getName());
    }

    @Override
    public void onTransitionStarted(double currentTime, PetriT transition) {
        started.add(transition.getName());
    }

    @Override
    public void onStateChanged(double currentTime) {
        if (currentTime <= until) {
            lines.add(String.format(Locale.ROOT, "%10.4f | %-28s | %-34s | %2d %2d %2d %3d %2d | %2d %2d %2d",
                    currentTime, String.join(" ", completed), String.join(" ", started),
                    net.queue(TaskType.A).getMark(), net.queue(TaskType.B).getMark(),
                    net.queue(TaskType.C).getMark(), net.memory().getMark(), net.reserved().getMark(),
                    net.service(TaskType.A).getBuffer(), net.service(TaskType.B).getBuffer(),
                    net.service(TaskType.C).getBuffer()));
        }
        completed.clear();
        started.clear();
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }
}
