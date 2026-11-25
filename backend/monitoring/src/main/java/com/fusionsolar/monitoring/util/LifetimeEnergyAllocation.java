package com.fusionsolar.monitoring.util;

import java.time.LocalDate;
import java.util.function.ToDoubleBiFunction;

/** Allocates a cumulative yield by theoretical weights, without subtracting today's yield. */
public final class LifetimeEnergyAllocation {
    private LifetimeEnergyAllocation() {}

    /** Earliest available date; neither date has priority over the other. */
    public static LocalDate origin(LocalDate swap, LocalDate commissioning) {
        if (swap == null) return commissioning;
        if (commissioning == null) return swap;
        return swap.isBefore(commissioning) ? swap : commissioning;
    }

    public static Double allocate(Double totalKwh, LocalDate origin, LocalDate observedDay,
            LocalDate filterStart, LocalDate filterEnd,
            ToDoubleBiFunction<LocalDate, LocalDate> theoreticalYield) {
        if (totalKwh == null || !Double.isFinite(totalKwh) || totalKwh < 0
                || origin == null || observedDay == null
                || filterStart == null || filterEnd == null || filterEnd.isBefore(filterStart)) return null;
        if (filterEnd.isBefore(origin)) return 0.0;
        if (observedDay.isBefore(origin)) return null;
        LocalDate start = filterStart.isAfter(origin) ? filterStart : origin;
        LocalDate end = filterEnd.isBefore(observedDay) ? filterEnd : observedDay;
        if (end.isBefore(start)) return 0.0;
        double denominator = theoreticalYield.applyAsDouble(origin, observedDay);
        double numerator = theoreticalYield.applyAsDouble(start, end);
        if (!Double.isFinite(denominator) || denominator <= 0 || !Double.isFinite(numerator)
                || numerator < 0 || numerator > denominator + 1e-6) return null;
        double result = totalKwh * (numerator / denominator);
        return Double.isFinite(result) ? result : null;
    }
}
