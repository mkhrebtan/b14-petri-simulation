package ua.kpi.km.b14.petri;

import java.util.Objects;
import java.util.random.RandomGenerator;

import ua.stetsenkoinna.petriobj.PetriP;
import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Перехід стохастичної мережі Петрі — нащадок {@link PetriT} ядра {@code petri-math}.
 * Умову запуску, вхід і вихід маркерів, багатоканальність ({@code buffer}, список моментів
 * виходу) і середню кількість активних каналів ({@link #getMean()}) успадковано без змін.
 *
 * <p>Перевизначено:
 * <ul>
 *   <li>{@link #getTimeServ()} / {@link #generateTimeServ()} — затримка {@code d} генерується
 *       законом {@link Delay} з генератором прогону замість {@code FunRand} на
 *       {@code Math.random()}, тож прогін із тим самим seed відтворюється;</li>
 *   <li>{@link #actIn} / {@link #actOut} — додатково рахують запуски та завершення каналів
 *       (у ядрі лічильників немає); лічильники обнуляються на початку збору статистики;</li>
 *   <li>{@link #clone()} — заборонено: клон ядра створює звичайний {@code PetriT} і втратив би
 *       закон затримки.</li>
 * </ul>
 * Кожен виклик {@link #getTimeServ()} (як і в ядрі) генерує нове значення, тобто споживає
 * випадкове число; його викликає лише {@code actIn}.
 */
@SuppressWarnings("serial") // серіалізацію ядра (для сервера редактора) модель не використовує
public final class StochasticTransition extends PetriT {

    private final Delay delay;
    private final RandomGenerator random;
    private long starts;
    private long completions;

    /** Створюється лише через {@link PetriNetBuilder}, що задає номер, пріоритет і ймовірність. */
    StochasticTransition(String name, Delay delay, RandomGenerator random) {
        super(name, delay.mean()); // parametr = середня затримка; distribution = null → FunRand не викликається
        this.delay = Objects.requireNonNull(delay);
        this.random = Objects.requireNonNull(random);
    }

    public Delay delay() {
        return delay;
    }

    @Override
    public double getTimeServ() {
        return delay.sample(random);
    }

    @Override
    public double generateTimeServ() {
        return getTimeServ();
    }

    @Override
    public void actIn(PetriP[] places, double currentTime) {
        int channelsBefore = getBuffer();
        super.actIn(places, currentTime);
        if (getBuffer() > channelsBefore) {
            starts++;
        }
    }

    @Override
    public void actOut(PetriP[] places, double currentTime) {
        int channelsBefore = getBuffer();
        super.actOut(places, currentTime);
        if (getBuffer() < channelsBefore) {
            completions++;
        }
    }

    /** Кількість запусків (входів маркерів) на інтервалі збору статистики. */
    public long starts() {
        return starts;
    }

    /** Кількість завершень (виходів маркерів) на інтервалі збору статистики. */
    public long completions() {
        return completions;
    }

    void resetCounters() {
        starts = 0;
        completions = 0;
    }

    @Override
    public PetriT clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("Клон ядра втратив би закон затримки переходу " + getName());
    }

    @Override
    public String toString() {
        return getName() + "(r=" + getPriority() + ", b=" + getProbability() + ", n=" + getBuffer() + ")";
    }
}
