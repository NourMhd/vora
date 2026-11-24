package com.fusionsolar.monitoring.util;

import java.time.LocalDate;
import java.util.function.ToDoubleBiFunction;

/** Allocates a cumulative yield by theoretical weights, without subtracting today's yield. */
public final class LifetimeEnergyAllocation {
    private LifetimeEnergyAllocation() {}

    public static Double allocate(Double totalKwh, LocalDate commissioned, LocalDate observedDay,
            LocalDate filterStart, LocalDate filterEnd,
            ToDoubleBiFunction<LocalDate, LocalDate> theoreticalYield) {
        if (totalKwh == null || !Double.isFinite(totalKwh) || totalKwh < 0
                || commissioned == null || observedDay == null || observedDay.isBefore(commissioned)
                || filterStart == null || filterEnd == null || filterEnd.isBefore(filterStart)) return null;
        LocalDate start = filterStart.isAfter(commissioned) ? filterStart : commissioned;
        LocalDate end = filterEnd.isBefore(observedDay) ? filterEnd : observedDay;
        if (end.isBefore(start)) return 0.0;
        double denominator = theoreticalYield.applyAsDouble(commissioned, observedDay);
        double numerator = theoreticalYield.applyAsDouble(start, end);
        if (!Double.isFinite(denominator) || denominator <= 0 || !Double.isFinite(numerator)
                || numerator < 0 || numerator > denominator + 1e-6) return null;
        double result = totalKwh * (numerator / denominator);
        return Double.isFinite(result) ? result : null;
    }
}
