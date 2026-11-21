package ua.kpi.km.b14.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MmcQueueTest {

    @Test
    void singleServerMatchesClosedForm() {
        MmcQueue q = new MmcQueue(0.06, 0.1, 1); // ρ = 0,6: L_q = ρ²/(1−ρ) = 0,9
        assertEquals(0.9, q.queueLength(), 1e-12);
        assertEquals(15.0, q.waitingTime(), 1e-12);
        assertEquals(25.0, q.sojournTime(), 1e-12);
    }

    @Test
    void twoServersMatchClosedForm() {
        // M/M/2: L_q = 2ρ³/(1−ρ²), ρ = λ/(2μ) = 0,4
        MmcQueue q = new MmcQueue(0.2, 0.25, 2);
        double rho = 0.4;
        assertEquals(2 * Math.pow(rho, 3) / (1 - rho * rho), q.queueLength(), 1e-12);
    }

    @Test
    void rejectsUnstableSystem() {
        assertThrows(IllegalArgumentException.class, () -> new MmcQueue(0.5, 0.25, 2));
    }
}
