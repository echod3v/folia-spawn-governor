package io.github.jayden0903.spawngovernor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SpawnControllerTest {
    private static SpawnController controller() {
        return new SpawnController(SpawnController.Settings.defaults());
    }

    private static int allowedOf(SpawnController c, int ticks) {
        int allowed = 0;
        for (int i = 0; i < ticks; i++) if (c.allowSpawnTick()) allowed++;
        return allowed;
    }

    @Test
    void healthyRegionIsExactlyVanilla() {
        SpawnController c = controller();
        for (int s = 0; s < 120; s++) {
            c.sample(15.0 + (s % 20));      // anywhere at or below target
            assertEquals(20, allowedOf(c, 20), "every spawn tick must run while healthy");
        }
        assertFalse(c.governed());
        assertEquals(1.0, c.fraction());
    }

    @Test
    void holdsInsideTheBandWithoutOscillating() {
        SpawnController c = controller();
        c.sample(60.0);
        double cut = c.fraction();
        for (int s = 0; s < 30; s++) c.sample(40.0); // inside [target - band, target]
        assertEquals(cut, c.fraction(), 1e-12);
    }

    @Test
    void overloadCutsProportionallyAndReachesTheFloor() {
        SpawnController c = controller();
        c.sample(84.0);                    // 2x the target
        assertEquals(0.5, c.fraction(), 1e-9);
        c.sample(250.0);                   // factor bounded by max decrease per second
        assertEquals(0.125, c.fraction(), 1e-9);
        for (int s = 0; s < 5; s++) c.sample(250.0);
        assertTrue(c.atFloor(), "a multiplicative cut must snap to the floor instead of creeping towards it");
        assertEquals(0, allowedOf(c, 200));
    }

    @Test
    void allowedTicksMatchTheFractionEvenly() {
        SpawnController c = controller();
        c.sample(84.0);                    // fraction 0.5
        int allowed = allowedOf(c, 1000);
        assertEquals(500, allowed);
    }

    @Test
    void recoversGraduallyOnceWellBelowTarget() {
        SpawnController c = controller();
        for (int s = 0; s < 10; s++) c.sample(250.0);
        assertTrue(c.atFloor());
        int seconds = 0;
        while (c.governed() && seconds < 600) { c.sample(20.0); seconds++; }
        assertTrue(seconds > 5, "recovery must not be a step back to vanilla");
        assertTrue(seconds < 120, "recovery must complete within a couple of minutes, took " + seconds);
        assertEquals(1.0, c.fraction());
    }

    @Test
    void ignoresMissingSamples() {
        SpawnController c = controller();
        c.sample(Double.NaN);
        c.sample(Double.POSITIVE_INFINITY);
        assertEquals(1.0, c.fraction());
        assertTrue(Double.isNaN(c.lastMspt()));
    }

    @Test
    void newRegionInheritsTheStricterThrottleOnly() {
        SpawnController strict = controller();
        for (int s = 0; s < 10; s++) strict.sample(250.0);
        SpawnController fresh = controller();
        fresh.inheritFrom(strict);
        assertTrue(fresh.atFloor());
        assertEquals(250.0, fresh.lastMspt());

        SpawnController loose = controller();
        strict.inheritFrom(loose);        // never relax through inheritance
        assertTrue(strict.atFloor());
    }

    @Test
    void rejectsNonsenseSettings() {
        assertThrows(IllegalArgumentException.class, () -> new SpawnController.Settings(60, 4, 0, 0.25, 0.02));
        assertThrows(IllegalArgumentException.class, () -> new SpawnController.Settings(42, 50, 0, 0.25, 0.02));
        assertThrows(IllegalArgumentException.class, () -> new SpawnController.Settings(42, 4, 2, 0.25, 0.02));
    }
}
