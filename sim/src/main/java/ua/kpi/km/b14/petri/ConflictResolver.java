package ua.kpi.km.b14.petri;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Вибір переходу, що запускається, серед активних — алгоритм {@code PetriSim.doConflikt}
 * ядра {@code petri-math}, який перевизначає {@link PetriSimulator}.
 *
 * <ol>
 *   <li>Перемагає група переходів із найвищим пріоритетом {@code r}.</li>
 *   <li>Якщо в групі один перехід — запускається він.</li>
 *   <li>Інакше розігрується рівномірне {@code u з [0; 1)}: перехід із {@code b = 1} отримує
 *       ймовірність {@code 1/n} ({@code n} — розмір групи), інший — свою {@code b}; обирається
 *       перший, для якого накопичена сума ймовірностей перевищує {@code u}.</li>
 * </ol>
 *
 * Відмінність від ядра одна: замість {@code Math.random()} — генератор прогону з seed.
 */
public final class ConflictResolver {

    private final RandomGenerator random;

    public ConflictResolver(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    /**
     * @param enabled активні переходи, відсортовані за спаданням пріоритету (непорожній список,
     *                як його формує {@code PetriSim.findActiveT})
     * @return перехід — переможець конфлікту
     */
    public <T extends PetriT> T choose(List<T> enabled) {
        T first = enabled.getFirst();
        int groupSize = topPriorityGroupSize(enabled);
        if (groupSize == 1) {
            return first;
        }
        double u = random.nextDouble();
        double cumulative = 0;
        for (int j = 0; j < groupSize; j++) {
            T candidate = enabled.get(j);
            cumulative += candidate.getProbability() == 1.0 ? 1.0 / groupSize : candidate.getProbability();
            if (u < cumulative) {
                return candidate;
            }
        }
        return first; // сума ймовірностей групи < 1 і u її перевищило — як у ядрі
    }

    private static int topPriorityGroupSize(List<? extends PetriT> sorted) {
        int top = sorted.getFirst().getPriority();
        int size = 0;
        while (size < sorted.size() && sorted.get(size).getPriority() == top) {
            size++;
        }
        return size;
    }
}
