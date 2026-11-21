package ua.kpi.km.b14.petri;

import ua.stetsenkoinna.petriobj.PetriT;

/**
 * Спостерігач імітації {@link PetriSimulator}. {@link #onStateChanged} викликається після
 * кожного просування стану (виходу й входу маркерів у поточний момент часу), а також після
 * початкового входу маркерів у момент 0. Необов'язкові методи повідомляють про окремі
 * спрацьовування переходів (фази {@code AFTER_ACT_IN} / {@code AFTER_ACT_OUT} ядра).
 * Використовується для перевірки інваріантів під час верифікації, протоколу подій і запису
 * траєкторій відгуків для визначення перехідного періоду.
 */
@FunctionalInterface
public interface SimulationListener {

    void onStateChanged(double currentTime);

    /** Вхід маркерів у перехід (відкрито новий канал). */
    default void onTransitionStarted(double currentTime, PetriT transition) {
    }

    /** Вихід маркерів з переходу (канал завершено). */
    default void onTransitionCompleted(double currentTime, PetriT transition) {
    }
}
