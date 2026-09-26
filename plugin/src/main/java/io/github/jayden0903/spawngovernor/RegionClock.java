package io.github.jayden0903.spawngovernor;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * Minimal read-only bridge to Folia's region scheduler, isolated here because it relies on
 * non-API classes ({@code io.papermc.paper.threadedregions}). Every call must be made on a region
 * tick thread; off-region calls return {@code null}/{@code NaN} instead of throwing.
 */
final class RegionClock {
    private final Class<?> handleClass;
    private final MethodHandle currentTask, currentTick, report5s, timePerTick, segmentAll, average;

    RegionClock() throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        Class<?> scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
        handleClass = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler$RegionScheduleHandle");
        currentTask = lookup.unreflect(scheduler.getMethod("getCurrentTickingTask"));
        currentTick = lookup.unreflect(handleClass.getMethod("getCurrentTick"));
        var reportMethod = handleClass.getMethod("getTickReport5s", long.class);
        report5s = lookup.unreflect(reportMethod);
        var tickTime = reportMethod.getReturnType().getMethod("timePerTickData");
        timePerTick = lookup.unreflect(tickTime);
        var segment = tickTime.getReturnType().getMethod("segmentAll");
        segmentAll = lookup.unreflect(segment);
        average = lookup.unreflect(segment.getReturnType().getMethod("average"));
    }

    /** The schedule handle of the region ticking on this thread, or {@code null}. Identity-stable per region. */
    Object currentRegion() {
        try {
            Object handle = currentTask.invoke();
            return handleClass.isInstance(handle) ? handle : null;
        } catch (Throwable t) {
            return null;
        }
    }

    long tick(Object region) {
        try {
            return (long) currentTick.invoke(region);
        } catch (Throwable t) {
            return Long.MIN_VALUE;
        }
    }

    /** 5-second average tick time of the region in milliseconds, or NaN when unavailable. */
    double averageMspt(Object region) {
        try {
            Object report = report5s.invoke(region, System.nanoTime());
            if (report == null) return Double.NaN;
            return (double) average.invoke(segmentAll.invoke(timePerTick.invoke(report))) / 1_000_000.0;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }
}
