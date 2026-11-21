package ua.kpi.km.b14.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ua.kpi.km.b14.model.B14Simulation;
import ua.kpi.km.b14.model.ModelParameters;
import ua.kpi.km.b14.model.RunSettings;
import ua.kpi.km.b14.model.SimulationResult;
import ua.kpi.km.b14.model.Strategy;

class ExperimentRunnerTest {

    @Test
    void seedsOfAllRunsAreDistinct() {
        Set<Long> seeds = new HashSet<>();
        int count = 0;
        for (int series : new int[]{2, 3, 5, 6, 7, 11, 12, 13, 15, 16}) {
            for (int cell = 0; cell < 35; cell++) {
                for (int run = 0; run < 100; run++) {
                    seeds.add(ExperimentRunner.seed(series, cell, run));
                    count++;
                }
            }
        }
        assertEquals(count, seeds.size());
    }

    /**
     * Траєкторія: точка на кожному кроці h до T_мод; середні в останній точці близькі до
     * результату прогону (той самий інтервал [0; T] без кількох останніх подій).
     */
    @Test
    void trajectoryRecordsMeansOnGrowingIntervals() {
        ModelParameters parameters = ModelParameters.ofIntensityFactor(Strategy.S1, 0.5);
        RunSettings settings = new RunSettings(50_000, 0, 7);
        ResponseTrajectory[] trajectory = new ResponseTrajectory[1];
        SimulationResult result = B14Simulation.run(parameters, settings, net -> {
            trajectory[0] = new ResponseTrajectory(net, 1_000);
            return List.of(trajectory[0]);
        });
        List<ResponseTrajectory.Point> points = trajectory[0].points();
        assertEquals(49, points.size());
        for (int k = 0; k < points.size(); k++) {
            assertEquals(1_000.0 * (k + 1), points.get(k).time());
            assertTrue(points.get(k).currentQueue() >= 0);
        }
        ResponseTrajectory.Point last = points.get(points.size() - 1);
        assertEquals(result.sojournTime(), last.sojournTime(), 0.05 * result.sojournTime());
        assertEquals(result.queueLength(), last.queueLength(), 0.05 * result.queueLength());
    }
}
