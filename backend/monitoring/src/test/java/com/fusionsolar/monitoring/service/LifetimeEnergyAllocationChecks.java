package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.util.LifetimeEnergyAllocation;
import java.time.LocalDate;
import java.util.function.ToDoubleBiFunction;

public class LifetimeEnergyAllocationChecks {
    private static int checks;
    private static void close(Double actual, double expected) {
        checks++;
        if (actual == null || Math.abs(actual - expected) > 1e-7)
            throw new AssertionError(actual + " != " + expected);
    }
    private static void absent(Double value) {
        checks++; if (value != null) throw new AssertionError("Expected fallback, got " + value);
    }
    public static void main(String[] args) {
        var service = new ProductionEstimationService();
        ToDoubleBiFunction<LocalDate, LocalDate> weights = (a,b) -> service.theoreticalYieldPerKwc("Zone A",a,b);
        LocalDate start = LocalDate.of(2025,1,1), end = LocalDate.of(2025,2,28);
        // Independently calculated theoretical weights: January 73.5; February 89.
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,start,LocalDate.of(2025,1,31),weights),735);
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,LocalDate.of(2025,2,1),end,weights),890);
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,LocalDate.of(2025,1,5),LocalDate.of(2025,1,15),weights),252);
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,start.minusYears(1),end.plusYears(1),weights),1625);
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,start.minusYears(1),start.minusDays(1),weights),0);
        close(LifetimeEnergyAllocation.allocate(1625.0,start,end,end.plusDays(1),end.plusMonths(1),weights),0);
        close(LifetimeEnergyAllocation.allocate(0.0,start,end,start,end,weights),0);
        absent(LifetimeEnergyAllocation.allocate(1625.0,null,end,start,end,weights));
        absent(LifetimeEnergyAllocation.allocate(Double.NaN,start,end,start,end,weights));
        absent(LifetimeEnergyAllocation.allocate(-1.0,start,end,start,end,weights));
        absent(LifetimeEnergyAllocation.allocate(1625.0,start,end,start,end,(a,b)->0));
        // Daily partitions conserve the full total, including the current observation day.
        for (String zone : new String[]{"Zone A","Zone B","Zone C"}) {
            ToDoubleBiFunction<LocalDate,LocalDate> theory = (a,b)->service.theoreticalYieldPerKwc(zone,a,b);
            LocalDate first = LocalDate.of(2024,2,15), today = LocalDate.of(2026,11,24);
            double sum=0;
            for (LocalDate d=first; !d.isAfter(today); d=d.plusDays(1))
                sum += LifetimeEnergyAllocation.allocate(10000.0,first,today,d,d,theory);
            close(sum,10000);
            close(LifetimeEnergyAllocation.allocate(10000.0,first,today,first,today,theory),10000);
        }
        System.out.println("Lifetime allocation: " + checks + " checks passed");
    }
}
