package ua.kpi.km.b14.petri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

import ua.stetsenkoinna.petriobj.ArcIn;
import ua.stetsenkoinna.petriobj.FiringPhase;
import ua.stetsenkoinna.petriobj.PetriNet;
import ua.stetsenkoinna.petriobj.PetriP;
import ua.stetsenkoinna.petriobj.PetriSim;
import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Імітатор стохастичної мережі Петрі — нащадок {@link PetriSim} ядра {@code petri-math}.
 * Успадковано без змін: {@code input}, {@code output}, {@code eventMin}, {@code doStatistics}.
 * Цикл {@link #run} замінює {@code PetriObjModel.go} і {@code PetriSim.go} ядра для однієї мережі
 * (без перемішування Петрі-об'єктів і друку маркірування на кожному кроці). Перевизначено:
 * {@link #doConflikt} — генератор прогону замість {@code Math.random()}; {@link #findActiveT} —
 * той самий результат швидше; статистика — лише на {@code [T_п; T_мод]} (вага
 * {@code a = Δt / (t' − T_п)} замість {@code Δt / t'}, лічильники обнуляються в момент {@code T_п});
 * спостерігачі {@link SimulationListener} — після кожної події й реальних спрацьовувань переходів.
 * Один імітатор — один прогін мережі з початкового маркірування.
 */
@SuppressWarnings("serial") // серіалізацію ядра (для сервера редактора) модель не використовує
public final class PetriSimulator extends PetriSim {

    private final ConflictResolver conflictResolver;
    private final List<SimulationListener> listeners = new ArrayList<>();

    // Для findActiveT: переходи з b ≠ 0 за спаданням r і їхні вхідні дуги (з інформаційними).
    private final PetriP[] places;
    private final PetriT[] candidates;
    private final int[][] inputPlaces;
    private final int[][] inputQuantities;
    private final ArrayList<PetriT> active = new ArrayList<>();

    private double warmupTime;
    private boolean collectingStatistics;
    private boolean started;

    /**
     * @param net    мережа в початковому маркіруванні (зазвичай з {@link PetriNetBuilder})
     * @param random генератор прогону — той самий, з яким створено переходи мережі
     */
    public PetriSimulator(PetriNet net, RandomGenerator random) {
        super(net);
        this.conflictResolver = new ConflictResolver(random);
        setFiringListener(this::onFiringPhase);
        places = net.getListP();
        candidates = Arrays.stream(net.getListT())
                .filter(t -> t.getProbability() != 0)
                .sorted(Comparator.comparingInt(PetriT::getPriority).reversed()) // стійке, як у ядрі
                .toArray(PetriT[]::new);
        inputPlaces = new int[candidates.length][];
        inputQuantities = new int[candidates.length][];
        for (int i = 0; i < candidates.length; i++) {
            int number = candidates[i].getNumber();
            ArcIn[] arcs = Arrays.stream(net.getArcIn()).filter(a -> a.getNumT() == number).toArray(ArcIn[]::new);
            inputPlaces[i] = Arrays.stream(arcs).mapToInt(ArcIn::getNumP).toArray();
            inputQuantities[i] = Arrays.stream(arcs).mapToInt(ArcIn::getQuantity).toArray();
        }
    }

    public void addListener(SimulationListener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Той самий список, що в {@code PetriSim.findActiveT} ядра (умова запуску, {@code b ≠ 0}, спадання
     * {@code r}), без сортування й списків {@code Integer} на кожному виклику. Список використовується
     * повторно: {@code input} ядра читає його до наступного виклику.
     */
    @Override
    public ArrayList<PetriT> findActiveT() {
        active.clear();
        for (int i = 0; i < candidates.length; i++) {
            if (isEnabled(inputPlaces[i], inputQuantities[i])) {
                active.add(candidates[i]);
            }
        }
        return active;
    }

    private boolean isEnabled(int[] inputPlace, int[] quantity) {
        for (int j = 0; j < inputPlace.length; j++) {
            if (places[inputPlace[j]].getMark() < quantity[j]) {
                return false;
            }
        }
        return true;
    }

    /** Переможець конфлікту — за пріоритетом, далі за ймовірностями запуску (як у ядрі). */
    @Override
    public PetriT doConflikt(ArrayList<PetriT> enabled) {
        return conflictResolver.choose(enabled);
    }

    /**
     * Виконує прогін мережі від початкового маркірування.
     *
     * @param simulationTime час моделювання {@code T_мод}
     * @param warmupTime     тривалість перехідного періоду {@code T_п}, {@code 0 ≤ T_п < T_мод}
     */
    public void run(double simulationTime, double warmupTime) {
        if (!(warmupTime >= 0 && warmupTime < simulationTime)) {
            throw new IllegalArgumentException("Потрібно 0 ≤ T_п < T_мод");
        }
        if (started) {
            throw new IllegalStateException("Мережу вже імітовано; для нового прогону потрібна нова мережа");
        }
        started = true;
        setSimulationTime(simulationTime);
        setTimeCurr(0);
        this.warmupTime = warmupTime;
        if (warmupTime == 0) {
            startStatistics();
        }

        input();
        notifyStateChanged();
        while (true) {
            double nextEvent = getTimeMin(); // оновлюється в input(); MAX_VALUE — подій немає
            if (nextEvent > simulationTime) {
                advanceTimeTo(simulationTime);
                return;
            }
            advanceTimeTo(nextEvent);
            output();
            input();
            notifyStateChanged();
        }
    }

    /** Просування часу зі збором статистики; перетин {@code T_п} запускає збір. */
    private void advanceTimeTo(double time) {
        if (!collectingStatistics && time >= warmupTime) {
            setTimeCurr(warmupTime);
            startStatistics();
        }
        if (collectingStatistics) {
            double dt = time - getCurrentTime();
            if (dt > 0) {
                doStatistics(dt / (time - warmupTime));
            }
        }
        setTimeCurr(time);
    }

    private void startStatistics() {
        collectingStatistics = true;
        for (PetriT transition : getNet().getListT()) {
            if (transition instanceof StochasticTransition stochastic) {
                stochastic.resetCounters();
            }
        }
    }

    private void onFiringPhase(FiringPhase phase, PetriT transition, double time) {
        switch (phase) {
            case AFTER_ACT_IN -> listeners.forEach(l -> l.onTransitionStarted(time, transition));
            case AFTER_ACT_OUT -> listeners.forEach(l -> l.onTransitionCompleted(time, transition));
            default -> {
                // фази BEFORE_* потрібні анімації редактора, моделі — ні
            }
        }
    }

    private void notifyStateChanged() {
        double time = getCurrentTime();
        for (SimulationListener listener : listeners) {
            listener.onStateChanged(time);
        }
    }
}
