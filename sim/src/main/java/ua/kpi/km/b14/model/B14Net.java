package ua.kpi.km.b14.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.random.RandomGenerator;

import ua.kpi.km.b14.petri.Delay;
import ua.kpi.km.b14.petri.PetriNetBuilder;
import ua.kpi.km.b14.petri.StochasticTransition;
import ua.stetsenkoinna.petriobj.PetriNet;
import ua.stetsenkoinna.petriobj.PetriP;

/**
 * Стохастична мережа Петрі системи B14 (рисунок 2.1, табл. 2.1–2.3), побудована з елементів
 * ядра {@code petri-math}, з типізованим доступом до них. Назви позицій і переходів збігаються
 * з {@code model/B14.pnml} та текстом Р2.
 */
public final class B14Net {

    public static final String MEMORY = "Mem";
    public static final String RESERVED = "RC";
    public static final String RESERVE_C = "ResC";
    public static final String RESERVATION_LIMIT = "LimC";

    private final PetriNet net;
    private final Map<TaskType, PetriP> queues = new EnumMap<>(TaskType.class);
    private final Map<TaskType, StochasticTransition> arrivals = new EnumMap<>(TaskType.class);
    private final Map<TaskType, StochasticTransition> services = new EnumMap<>(TaskType.class);
    private final PetriP memory;
    private final PetriP reserved;
    private final StochasticTransition reservation;
    /** Ліміт резервування для C (лише за M &gt; v_C, пропозиція розділу 5), інакше null. */
    private final PetriP reservationLimit;
    private final int memoryCapacity;
    private final int reservationMultiplicity;

    private B14Net(ModelParameters parameters, RandomGenerator random) {
        Strategy strategy = parameters.strategy();
        memoryCapacity = parameters.memory();
        PetriNetBuilder builder = new PetriNetBuilder("B14 " + strategy, random);

        for (TaskType type : TaskType.values()) {
            double rate = parameters.arrivalRate(type);
            // Потік із λ_i = 0 вимкнено: умова роботи джерела Gen_i без маркера.
            PetriP source = builder.place("Gen" + type, rate > 0 ? 1 : 0);
            PetriP queue = builder.place(queueName(type), 0);
            Delay interArrival = rate > 0 ? Delay.exponential(1.0 / rate) : Delay.constant(0);
            arrivals.put(type, builder.transition(arrivalName(type)).delay(interArrival)
                    .input(source).output(source).output(queue).add());
            queues.put(type, queue);
        }
        memory = builder.place(MEMORY, memoryCapacity);
        reserved = builder.place(RESERVED, 0);

        int kC = strategy.reservationMultiplicity();
        reservationMultiplicity = kC;
        int vC = TaskType.C.memoryDemand();
        // За M > v_C вільна пам'ять може перевищувати потребу C: позиція LimC (m = v_C) не дає
        // зарезервувати більше v_C одиниць, інакше залишок лишався б у RC після старту C.
        // Оскільки 2 v_C > M (M = 3), одночасно резервується або виконується не більше одного C.
        reservationLimit = memoryCapacity > vC ? builder.place(RESERVATION_LIMIT, vC) : null;
        PetriNetBuilder.TransitionBuilder reserve = builder.transition(RESERVE_C).delay(Delay.constant(0))
                .priority(strategy.priority(TaskType.C))
                .informationalInput(queues.get(TaskType.C), 1)
                .input(memory, kC).output(reserved, kC);
        if (reservationLimit != null) {
            reserve.input(reservationLimit, kC);
        }
        reservation = reserve.add();

        for (TaskType type : TaskType.values()) {
            PetriP done = builder.place("Out" + type, 0);
            PetriP resource = type == TaskType.C ? reserved : memory;
            int v = type.memoryDemand();
            int priority = type == TaskType.C ? 0 : strategy.priority(type);
            PetriNetBuilder.TransitionBuilder service = builder.transition(serviceName(type))
                    .delay(Delay.exponential(parameters.meanServiceTime(type)))
                    .priority(priority).probability(strategy.launchProbability(type))
                    .input(queues.get(type)).input(resource, v)
                    .output(memory, v).output(done);
            if (type == TaskType.C && reservationLimit != null) {
                service.output(reservationLimit, v);
            }
            services.put(type, service.add());
        }
        net = builder.build();
    }

    /**
     * @param random генератор прогону: з нього переходи генерують затримки
     */
    public static B14Net create(ModelParameters parameters, RandomGenerator random) {
        return new B14Net(parameters, random);
    }

    public static String queueName(TaskType type) {
        return "Q" + type;
    }

    public static String arrivalName(TaskType type) {
        return "Arr" + type;
    }

    public static String serviceName(TaskType type) {
        return "Serv" + type;
    }

    public PetriNet petriNet() {
        return net;
    }

    public PetriP queue(TaskType type) {
        return queues.get(type);
    }

    public StochasticTransition arrival(TaskType type) {
        return arrivals.get(type);
    }

    public StochasticTransition service(TaskType type) {
        return services.get(type);
    }

    public PetriP memory() {
        return memory;
    }

    public PetriP reserved() {
        return reserved;
    }

    public int memoryCapacity() {
        return memoryCapacity;
    }

    /**
     * Чи дозволяє ліміт LimC резервувати ще k_C одиниць пам'яті для C (за M = v_C ліміту немає).
     */
    public boolean reservationAllowed() {
        return reservationLimit == null || reservationLimit.getMark() >= reservationMultiplicity;
    }

    /**
     * Ліва частина інваріанту ліміту резервування: m_LimC + m_RC + k_C n_ResC + v_C n_ServC;
     * має дорівнювати v_C (за M = v_C ліміту немає — повертає v_C).
     */
    public int reservationLimitBalance() {
        int vC = TaskType.C.memoryDemand();
        if (reservationLimit == null) {
            return vC;
        }
        return reservationLimit.getMark() + reserved.getMark() + reservationMultiplicity * reservation.getBuffer()
                + vC * services.get(TaskType.C).getBuffer();
    }

    /**
     * Ліва частина інваріанту пам'яті (формула 2.1):
     * m_Mem + m_RC + k_C n_ResC + Σ v_i n_Serv_i; має дорівнювати M у будь-який момент.
     */
    public int memoryBalance() {
        int balance = memory.getMark() + reserved.getMark() + reservationMultiplicity * reservation.getBuffer();
        for (TaskType type : TaskType.values()) {
            balance += type.memoryDemand() * services.get(type).getBuffer();
        }
        return balance;
    }
}
