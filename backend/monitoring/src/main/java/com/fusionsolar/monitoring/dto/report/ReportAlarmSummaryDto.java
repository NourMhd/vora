package com.fusionsolar.monitoring.dto.report;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportAlarmSummaryDto {
    private Integer activeCount;
    private Integer criticalCount;
    private Integer warningCount;
    private Integer historyCount;
    private List<ReportAlarmTypeDto> byType;
    private List<ReportAlarmItemDto> topActiveAlarms;
}
