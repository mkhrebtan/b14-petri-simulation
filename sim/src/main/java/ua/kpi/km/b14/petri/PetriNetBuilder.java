package ua.kpi.km.b14.petri;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.random.RandomGenerator;

import ua.stetsenkoinna.petriobj.ArcIn;
import ua.stetsenkoinna.petriobj.ArcOut;
import ua.stetsenkoinna.petriobj.ExceptionInvalidTimeDelay;
import ua.stetsenkoinna.petriobj.PetriIdGenerator;
import ua.stetsenkoinna.petriobj.PetriNet;
import ua.stetsenkoinna.petriobj.PetriP;
import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Покрокова побудова мережі {@link PetriNet} ядра {@code petri-math} з його ж елементів:
 * позицій {@link PetriP}, переходів {@link StochasticTransition} і дуг {@link ArcIn} /
 * {@link ArcOut}.
 *
 * <pre>{@code
 * PetriNetBuilder b = new PetriNetBuilder("M/M/1", random);
 * PetriP queue = b.place("Q", 0);
 * PetriP server = b.place("Free", 1);
 * b.transition("Serve").delay(Delay.exponential(4))
 *         .input(queue).input(server).output(server).add();
 * PetriNet net = b.build();
 * }</pre>
 *
 * Що будівник бере на себе замість користувача ядра:
 * <ul>
 *   <li><b>Нумерація.</b> Дуги ядра посилаються на позиції й переходи за номерами, а умова
 *       запуску звертається до масиву позицій за номером, тож номер має збігатися з індексом
 *       у мережі. Ядро бере номер зі статичного лічильника ({@code initNext()} перед кожною
 *       мережею); будівник призначає номери явно, незалежно від стану лічильників.</li>
 *   <li><b>Спільний стан ядра.</b> Конструктори елементів змінюють статичні лічильники та
 *       реєстр ідентифікаторів {@link PetriIdGenerator} (непотокобезпечний {@code HashSet}),
 *       тому створення елементів синхронізовано, а реєстр очищується після побудови мережі:
 *       ідентифікатори потрібні лише редактору й серверу, а серія з тисяч прогонів інакше
 *       накопичувала б їх у пам'яті.</li>
 *   <li><b>Перевірки</b>, яких ядро не робить: кратність дуг {@code k >= 1}, {@code 0 <= b <= 1},
 *       унікальність назв (без урахування регістру, як пошук {@code strToNumP} ядра), дуги лише
 *       до позицій цієї мережі, хоча б одна вхідна дуга, що вилучає маркери.</li>
 * </ul>
 * Будівник одноразовий: після {@link #build()} додавати елементи не можна.
 */
public final class PetriNetBuilder {

    /** Захищає статичний стан ядра, який змінюють конструктори його елементів. */
    private static final Object KERNEL_STATE_LOCK = new Object();

    private final String name;
    private final RandomGenerator random;
    private final List<PetriP> places = new ArrayList<>();
    private final List<PetriT> transitions = new ArrayList<>();
    private final List<ArcIn> inputArcs = new ArrayList<>();
    private final List<ArcOut> outputArcs = new ArrayList<>();
    private final Set<String> names = new HashSet<>();
    private boolean built;

    /**
     * @param random генератор прогону, з якого переходи генерують затримки
     */
    public PetriNetBuilder(String name, RandomGenerator random) {
        this.name = Objects.requireNonNull(name);
        this.random = Objects.requireNonNull(random);
    }

    public PetriP place(String placeName, int initialMarking) {
        ensureNotBuilt();
        if (initialMarking < 0) {
            throw new IllegalArgumentException("Від'ємне маркірування позиції " + placeName);
        }
        reserveName(placeName);
        PetriP place;
        synchronized (KERNEL_STATE_LOCK) {
            place = new PetriP(placeName, initialMarking);
        }
        place.setNumber(places.size());
        places.add(place);
        return place;
    }

    public TransitionBuilder transition(String transitionName) {
        ensureNotBuilt();
        return new TransitionBuilder(transitionName);
    }

    /** Створює мережу ядра: кожен перехід отримує списки вхідних і вихідних позицій за дугами. */
    public PetriNet build() {
        ensureNotBuilt();
        built = true;
        try {
            PetriNet net = new PetriNet(name, new ArrayList<>(places), new ArrayList<>(transitions),
                    new ArrayList<>(inputArcs), new ArrayList<>(outputArcs));
            net.validateStructure();
            return net;
        } catch (ExceptionInvalidTimeDelay e) {
            throw new IllegalStateException("Некоректна структура мережі " + name + ": " + e.getMessage(), e);
        } finally {
            synchronized (KERNEL_STATE_LOCK) {
                PetriIdGenerator.reset();
            }
        }
    }

    private void reserveName(String elementName) {
        Objects.requireNonNull(elementName);
        if (!names.add(elementName.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Повторна назва елемента мережі: " + elementName);
        }
    }

    private void ensureNotBuilt() {
        if (built) {
            throw new IllegalStateException("Мережу " + name + " уже побудовано");
        }
    }

    private void requireOwnPlace(PetriP place) {
        int number = place.getNumber();
        if (number < 0 || number >= places.size() || places.get(number) != place) {
            throw new IllegalArgumentException("Позиція " + place.getName() + " не належить мережі " + name);
        }
    }

    private static void requirePositive(int multiplicity) {
        if (multiplicity < 1) {
            throw new IllegalArgumentException("Кратність дуги має бути ≥ 1: " + multiplicity);
        }
    }

    private record InputSpec(PetriP place, int multiplicity, boolean informational) {
    }

    private record OutputSpec(PetriP place, int multiplicity) {
    }

    /** Параметри та дуги одного переходу; {@link #add()} додає його до мережі. */
    public final class TransitionBuilder {

        private final String transitionName;
        private Delay delay = Delay.constant(0);
        private int priority;
        private double probability = 1.0;
        private final List<InputSpec> inputs = new ArrayList<>();
        private final List<OutputSpec> outputs = new ArrayList<>();

        private TransitionBuilder(String transitionName) {
            this.transitionName = Objects.requireNonNull(transitionName);
        }

        public TransitionBuilder delay(Delay value) {
            this.delay = Objects.requireNonNull(value);
            return this;
        }

        public TransitionBuilder priority(int value) {
            this.priority = value;
            return this;
        }

        public TransitionBuilder probability(double value) {
            if (!(value >= 0 && value <= 1)) {
                throw new IllegalArgumentException("Ймовірність запуску поза [0; 1]: " + transitionName);
            }
            this.probability = value;
            return this;
        }

        public TransitionBuilder input(PetriP place) {
            return input(place, 1);
        }

        public TransitionBuilder input(PetriP place, int multiplicity) {
            return addInput(place, multiplicity, false);
        }

        /** Інформаційна дуга: умова «не менше {@code multiplicity} маркерів» без їх вилучення. */
        public TransitionBuilder informationalInput(PetriP place, int multiplicity) {
            return addInput(place, multiplicity, true);
        }

        public TransitionBuilder output(PetriP place) {
            return output(place, 1);
        }

        public TransitionBuilder output(PetriP place, int multiplicity) {
            requireOwnPlace(place);
            requirePositive(multiplicity);
            outputs.add(new OutputSpec(place, multiplicity));
            return this;
        }

        private TransitionBuilder addInput(PetriP place, int multiplicity, boolean informational) {
            requireOwnPlace(place);
            requirePositive(multiplicity);
            inputs.add(new InputSpec(place, multiplicity, informational));
            return this;
        }

        /** Створює перехід і його дуги в мережі. */
        public StochasticTransition add() {
            ensureNotBuilt();
            if (inputs.stream().allMatch(InputSpec::informational)) {
                throw new IllegalArgumentException("Перехід без вхідної дуги, що вилучає маркери: " + transitionName);
            }
            reserveName(transitionName);
            StochasticTransition transition;
            synchronized (KERNEL_STATE_LOCK) {
                transition = new StochasticTransition(transitionName, delay, random);
                transition.setNumber(transitions.size());
                transition.setPriority(priority);
                transition.setProbability(probability);
                // Дуги ядра копіюють номери позиції й переходу в момент створення.
                for (InputSpec in : inputs) {
                    inputArcs.add(new ArcIn(in.place(), transition, in.multiplicity(), in.informational()));
                }
                for (OutputSpec out : outputs) {
                    outputArcs.add(new ArcOut(transition, out.place(), out.multiplicity()));
                }
            }
            transitions.add(transition);
            return transition;
        }
    }
}
