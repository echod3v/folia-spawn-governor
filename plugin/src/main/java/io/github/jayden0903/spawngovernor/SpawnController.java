package io.github.jayden0903.spawngovernor;

/**
 * Per-region feedback controller. Pure logic with no server dependencies so it can be unit tested.
 *
 * <p>{@link #fraction()} is the share of natural-spawn ticks the region is allowed to run: {@code 1.0} means
 * untouched vanilla behaviour. Once per second the owner calls {@link #sample(double)} with the region's
 * 5-second average tick time. Above the target the fraction is cut in proportion to the overshoot; once the
 * region is comfortably below the target (by the hold band) it is handed back gradually. Every tick the owner
 * asks {@link #allowSpawnTick()}, which spreads the allowed ticks evenly (error accumulation), making this an
 * adaptive, per-region equivalent of Bukkit's {@code ticks-per} spawn setting.</p>
 *
 * <p>Instances are confined to the owning Folia region thread; they are not thread safe.</p>
 */
public final class SpawnController {
    /** A multiplicative cut never reaches zero on its own; below this share we snap to the floor. */
    static final double SNAP_TO_FLOOR = 0.02;

    public record Settings(double targetMspt, double holdBandMspt, double minimumFraction,
                           double maxDecreasePerSecond, double recoverStepPerSecond) {
        public Settings {
            if (!(targetMspt > 0 && targetMspt < 50)) throw new IllegalArgumentException("target must be in (0, 50) ms");
            if (!(holdBandMspt > 0 && holdBandMspt < targetMspt)) throw new IllegalArgumentException("band must be in (0, target)");
            if (!(minimumFraction >= 0 && minimumFraction <= 1)) throw new IllegalArgumentException("minimum must be in [0, 1]");
            if (!(maxDecreasePerSecond > 0 && maxDecreasePerSecond < 1)) throw new IllegalArgumentException("decrease must be in (0, 1)");
            if (!(recoverStepPerSecond > 0 && recoverStepPerSecond <= 1)) throw new IllegalArgumentException("recover step must be in (0, 1]");
        }

        public static Settings defaults() {
            return new Settings(42.0, 4.0, 0.0, 0.25, 0.02);
        }
    }

    private final Settings settings;
    private double fraction = 1.0;
    private double accumulator;
    private double lastMspt = Double.NaN;

    public SpawnController(Settings settings) {
        this.settings = settings;
    }

    /** Feeds one 5-second-average tick time sample (milliseconds). Non-finite samples are ignored. */
    public void sample(double mspt) {
        if (!Double.isFinite(mspt)) return;
        lastMspt = mspt;
        double target = settings.targetMspt();
        if (mspt > target) {
            double factor = Math.max(settings.maxDecreasePerSecond(), Math.min(0.95, target / mspt));
            double next = fraction * factor;
            fraction = next < SNAP_TO_FLOOR ? settings.minimumFraction() : Math.max(settings.minimumFraction(), next);
        } else if (mspt < target - settings.holdBandMspt() && fraction < 1.0) {
            double headroom = (target - mspt) / target;
            fraction = Math.min(1.0, fraction + settings.recoverStepPerSecond() + 0.05 * headroom + fraction * 0.02);
        }
    }

    /** Decides whether natural spawning may run in this region tick. Call exactly once per region tick. */
    public boolean allowSpawnTick() {
        if (fraction >= 1.0) {
            accumulator = 0.0;
            return true;
        }
        accumulator += fraction;
        if (accumulator >= 1.0) {
            accumulator -= 1.0;
            return true;
        }
        return false;
    }

    /** Adopts a stricter throttle, used when players carry state into a freshly created or merged region. */
    public void inheritFrom(SpawnController other) {
        if (other.fraction < fraction) {
            fraction = other.fraction;
            if (Double.isFinite(other.lastMspt)) lastMspt = Double.isFinite(lastMspt) ? Math.max(lastMspt, other.lastMspt) : other.lastMspt;
        }
    }

    public boolean atFloor() {
        return fraction <= settings.minimumFraction() + 1e-9;
    }

    public double fraction() { return fraction; }

    public double lastMspt() { return lastMspt; }

    public boolean governed() { return fraction < 1.0; }
}
