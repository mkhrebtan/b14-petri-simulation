package ua.kpi.km.b14.model;

/**
 * Вихідні характеристики для завдань одного типу i (формули 2.3–2.4).
 *
 * @param queueLength    L_{q,i} — середня кількість завдань у черзі
 * @param inSystem       L_i — середня кількість завдань у системі (черга + виконання)
 * @param arrivalRate    λ̂_i — фактична інтенсивність надходження, завд./хв
 * @param waitingTime    W_{q,i} — середній час очікування в черзі, хв
 * @param sojournTime    W_i — середній час перебування в системі, хв
 * @param arrivals       N_Arr_i — кількість надходжень на інтервалі збору статистики
 * @param completed      кількість виконаних завдань на інтервалі збору статистики
 * @param maxQueueLength найбільша довжина черги за весь прогін, від моменту 0
 *                       ({@code PetriP.getObservedMax} ядра)
 */
public record TypeMetrics(double queueLength, double inSystem, double arrivalRate,
                          double waitingTime, double sojournTime,
                          long arrivals, long completed, int maxQueueLength) {
}
