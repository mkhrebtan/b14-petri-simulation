package ua.kpi.km.b14.verification;

import java.util.Arrays;
import java.util.stream.Collectors;

import ua.kpi.km.b14.model.B14Net;
import ua.kpi.km.b14.model.Strategy;
import ua.kpi.km.b14.model.TaskType;
import ua.kpi.km.b14.petri.SimulationListener;

/**
 * Перевіряє після кожної події стани, які вимагає концептуальна модель:
 * <ul>
 *   <li>інваріант пам'яті (формула 2.1) — заборонені комбінації завдань неможливі;</li>
 *   <li>пам'ять не простоює, коли в черзі є A або B: (m_QA &gt; 0 або m_QB &gt; 0) => m_Mem = 0;</li>
 *   <li>стратегії 3–5 (C має вищий пріоритет): m_QC &gt; 0 => m_Mem = 0 — уся вільна пам'ять
 *       резервується для C;</li>
 *   <li>стратегії 1–2 (C має нижчий пріоритет): m_QC &gt; 0 => m_Mem ≤ 1 — C не чекає за двох
 *       вільних одиниць.</li>
 *   <li>за M &gt; v_C (пропозиція розділу 5) — інваріант ліміту резервування LimC; дві останні
 *       перевірки — лише коли ліміт дозволяє резервування.</li>
 * </ul>
 * Порушення зупиняє прогін винятком {@link IllegalStateException}.
 */
public final class InvariantMonitor implements SimulationListener {

    private final B14Net net;
    private final boolean highPriorityC;
    private long checks;

    public InvariantMonitor(B14Net net, Strategy strategy) {
        this.net = net;
        this.highPriorityC = strategy.priority(TaskType.C) > strategy.priority(TaskType.A)
                && strategy.priority(TaskType.C) > strategy.priority(TaskType.B);
    }

    @Override
    public void onStateChanged(double currentTime) {
        checks++;
        int balance = net.memoryBalance();
        if (balance != net.memoryCapacity()) {
            fail(currentTime, "порушено інваріант пам'яті: " + balance + " ≠ " + net.memoryCapacity());
        }
        int limitBalance = net.reservationLimitBalance();
        if (limitBalance != TaskType.C.memoryDemand()) {
            fail(currentTime, "порушено інваріант ліміту резервування: " + limitBalance);
        }
        int freeMemory = net.memory().getMark();
        boolean abWaiting = net.queue(TaskType.A).getMark() > 0 || net.queue(TaskType.B).getMark() > 0;
        if (abWaiting && freeMemory > 0) {
            fail(currentTime, "A/B у черзі за вільної пам'яті");
        }
        boolean cWaiting = net.queue(TaskType.C).getMark() > 0 && net.reservationAllowed();
        if (cWaiting && highPriorityC && freeMemory > 0) {
            fail(currentTime, "C вищого пріоритету чекає, але пам'ять не зарезервовано");
        }
        if (cWaiting && !highPriorityC && freeMemory >= TaskType.C.memoryDemand()) {
            fail(currentTime, "C чекає за двох вільних одиниць пам'яті");
        }
    }

    /** Кількість виконаних перевірок (подій прогону). */
    public long checks() {
        return checks;
    }

    private void fail(double time, String message) {
        String marking = Arrays.stream(net.petriNet().getListP())
                .map(p -> p.getName() + "[" + p.getMark() + "]")
                .collect(Collectors.joining(" "));
        throw new IllegalStateException(String.format("t = %.4f: %s; %s", time, message, marking));
    }
}
