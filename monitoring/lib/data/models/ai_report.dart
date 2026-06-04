class AiReportPreview {
  const AiReportPreview({
    required this.period,
    required this.scope,
    required this.scopeValue,
    required this.reportType,
    required this.generatedAt,
    required this.globalKpis,
    required this.productionSummary,
    required this.dataQuality,
    required this.zoneAnalysis,
    required this.governorateAnalysis,
    required this.bestSites,
    required this.worstSites,
    required this.alarms,
    required this.recommendations,
    required this.technicalAppendix,
    required this.aiNarrative,
    required this.aiEnabled,
    required this.aiStatus,
  });

  final ReportPeriod period;
  final String scope;
  final String? scopeValue;
  final String reportType;
  final DateTime? generatedAt;
  final ReportGlobalKpis globalKpis;
  final ReportProductionSummary productionSummary;
  final ReportDataQuality dataQuality;
  final List<ReportZoneAnalysis> zoneAnalysis;
  final List<ReportGovernorateAnalysis> governorateAnalysis;
  final List<ReportSiteRanking> bestSites;
  final List<ReportSiteRanking> worstSites;
  final ReportAlarmSummary alarms;
  final List<ReportRecommendation> recommendations;
  final List<ReportTechnicalRow> technicalAppendix;
  final AiNarrative aiNarrative;
  final bool aiEnabled;
  final String aiStatus;

  factory AiReportPreview.empty() =>
      AiReportPreview.fromJson(const <String, dynamic>{});

  factory AiReportPreview.fromJson(Map<String, dynamic> json) {
    return AiReportPreview(
      period: ReportPeriod.fromJson(_map(json['period'])),
      scope: _text(json['scope'], 'ALL'),
      scopeValue: _nullableText(json['scopeValue']),
      reportType: _text(json['reportType'], 'STANDARD'),
      generatedAt: DateTime.tryParse(json['generatedAt']?.toString() ?? ''),
      globalKpis: ReportGlobalKpis.fromJson(_map(json['globalKpis'])),
      productionSummary: ReportProductionSummary.fromJson(
        _map(json['productionSummary']),
      ),
      dataQuality: ReportDataQuality.fromJson(_map(json['dataQuality'])),
      zoneAnalysis: _list(
        json['zoneAnalysis'],
      ).map(ReportZoneAnalysis.fromJson).toList(),
      governorateAnalysis: _list(
        json['governorateAnalysis'],
      ).map(ReportGovernorateAnalysis.fromJson).toList(),
      bestSites: _list(
        json['bestSites'],
      ).map(ReportSiteRanking.fromJson).toList(),
      worstSites: _list(
        json['worstSites'],
      ).map(ReportSiteRanking.fromJson).toList(),
      alarms: ReportAlarmSummary.fromJson(_map(json['alarms'])),
      recommendations: _list(
        json['recommendations'],
      ).map(ReportRecommendation.fromJson).toList(),
      technicalAppendix: _list(
        json['technicalAppendix'],
      ).map(ReportTechnicalRow.fromJson).toList(),
      aiNarrative: AiNarrative.fromJson(_map(json['aiNarrative'])),
      aiEnabled: json['aiEnabled'] == true,
      aiStatus: _text(json['aiStatus'], 'GEMINI_REQUIRED'),
    );
  }
}

class ReportPeriod {
  const ReportPeriod({
    required this.startDate,
    required this.endDate,
    required this.daysCount,
  });
  final String startDate;
  final String endDate;
  final int daysCount;
  factory ReportPeriod.fromJson(Map<String, dynamic> json) => ReportPeriod(
    startDate: _text(json['startDate'], ''),
    endDate: _text(json['endDate'], ''),
    daysCount: _int(json['daysCount']),
  );
}

class ReportGlobalKpis {
  const ReportGlobalKpis({
    required this.totalSites,
    required this.healthySites,
    required this.faultySites,
    required this.disconnectedSites,
    required this.missingDataSites,
    required this.totalInstalledPowerKw,
    required this.activeAlarms,
    required this.globalDifferencePercent,
    required this.globalStatus,
  });
  final int totalSites;
  final int healthySites;
  final int faultySites;
  final int disconnectedSites;
  final int missingDataSites;
  final double? totalInstalledPowerKw;
  final int activeAlarms;
  final double? globalDifferencePercent;
  final String globalStatus;
  factory ReportGlobalKpis.fromJson(Map<String, dynamic> json) =>
      ReportGlobalKpis(
        totalSites: _int(json['totalSites']),
        healthySites: _int(json['healthySites']),
        faultySites: _int(json['faultySites']),
        disconnectedSites: _int(json['disconnectedSites']),
        missingDataSites: _int(json['missingDataSites']),
        totalInstalledPowerKw: _double(json['totalInstalledPowerKw']),
        activeAlarms: _int(json['activeAlarms']),
        globalDifferencePercent: _double(json['globalDifferencePercent']),
        globalStatus: _text(json['globalStatus'], 'NORMAL'),
      );
}

class ReportProductionSummary {
  const ReportProductionSummary({
    required this.expectedKwh,
    required this.capturedKwh,
    required this.estimatedKwh,
    required this.displayedKwh,
    required this.differenceKwh,
    required this.differencePercent,
    required this.bonusSites,
    required this.malusSites,
  });
  final double? expectedKwh;
  final double? capturedKwh;
  final double? estimatedKwh;
  final double? displayedKwh;
  final double? differenceKwh;
  final double? differencePercent;
  final int bonusSites;
  final int malusSites;
  factory ReportProductionSummary.fromJson(Map<String, dynamic> json) =>
      ReportProductionSummary(
        expectedKwh: _double(json['expectedKwh']),
        capturedKwh: _double(json['capturedKwh']),
        estimatedKwh: _double(json['estimatedKwh']),
        displayedKwh: _double(json['displayedKwh']),
        differenceKwh: _double(json['differenceKwh']),
        differencePercent: _double(json['differencePercent']),
        bonusSites: _int(json['bonusSites']),
        malusSites: _int(json['malusSites']),
      );
}

class ReportDataQuality {
  const ReportDataQuality({
    required this.expectedProductionDays,
    required this.capturedDays,
    required this.estimatedDays,
    required this.missingRealKwhSites,
    required this.missingExpectedKwhSites,
    required this.reliabilityPercent,
    required this.estimatedSharePercent,
    required this.status,
  });
  final int expectedProductionDays;
  final int capturedDays;
  final int estimatedDays;
  final int missingRealKwhSites;
  final int missingExpectedKwhSites;
  final double? reliabilityPercent;
  final double? estimatedSharePercent;
  final String status;
  factory ReportDataQuality.fromJson(Map<String, dynamic> json) =>
      ReportDataQuality(
        expectedProductionDays: _int(json['expectedProductionDays']),
        capturedDays: _int(json['capturedDays']),
        estimatedDays: _int(json['estimatedDays']),
        missingRealKwhSites: _int(json['missingRealKwhSites']),
        missingExpectedKwhSites: _int(json['missingExpectedKwhSites']),
        reliabilityPercent: _double(json['reliabilityPercent']),
        estimatedSharePercent: _double(json['estimatedSharePercent']),
        status: _text(json['status'], 'UNKNOWN'),
      );
}

class ReportZoneAnalysis {
  const ReportZoneAnalysis({
    required this.zone,
    required this.sitesCount,
    required this.actualKwh,
    required this.differencePercent,
    required this.activeAlarms,
  });
  final String zone;
  final int sitesCount;
  final double? actualKwh;
  final double? differencePercent;
  final int activeAlarms;
  factory ReportZoneAnalysis.fromJson(Map<String, dynamic> json) =>
      ReportZoneAnalysis(
        zone: _text(json['zone'], 'UNKNOWN'),
        sitesCount: _int(json['sitesCount']),
        actualKwh: _double(json['actualKwh']),
        differencePercent: _double(json['differencePercent']),
        activeAlarms: _int(json['activeAlarms']),
      );
}

class ReportGovernorateAnalysis {
  const ReportGovernorateAnalysis({
    required this.governorate,
    required this.zone,
    required this.sitesCount,
    required this.actualKwh,
    required this.differencePercent,
    required this.activeAlarms,
  });
  final String governorate;
  final String zone;
  final int sitesCount;
  final double? actualKwh;
  final double? differencePercent;
  final int activeAlarms;
  factory ReportGovernorateAnalysis.fromJson(Map<String, dynamic> json) =>
      ReportGovernorateAnalysis(
        governorate: _text(json['governorate'], 'UNKNOWN'),
        zone: _text(json['zone'], 'UNKNOWN'),
        sitesCount: _int(json['sitesCount']),
        actualKwh: _double(json['actualKwh']),
        differencePercent: _double(json['differencePercent']),
        activeAlarms: _int(json['activeAlarms']),
      );
}

class ReportSiteRanking {
  const ReportSiteRanking({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.status,
    required this.zone,
    required this.governorate,
    required this.actualKwh,
    required this.differencePercent,
    required this.priority,
    required this.reason,
  });
  final String brand;
  final String siteCode;
  final String siteName;
  final String status;
  final String zone;
  final String governorate;
  final double? actualKwh;
  final double? differencePercent;
  final String priority;
  final String reason;
  factory ReportSiteRanking.fromJson(Map<String, dynamic> json) =>
      ReportSiteRanking(
        brand: _text(json['brand'], '-'),
        siteCode: _text(json['siteCode'], '-'),
        siteName: _text(json['siteName'], 'Unnamed site'),
        status: _text(json['status'], 'UNKNOWN'),
        zone: _text(json['zone'], 'UNKNOWN'),
        governorate: _text(json['governorate'], 'UNKNOWN'),
        actualKwh: _double(json['actualKwh']),
        differencePercent: _double(json['differencePercent']),
        priority: _text(json['priority'], '-'),
        reason: _text(json['reason'], ''),
      );
}

class ReportAlarmSummary {
  const ReportAlarmSummary({
    required this.activeCount,
    required this.criticalCount,
    required this.warningCount,
    required this.historyCount,
    required this.byType,
    required this.topActiveAlarms,
  });
  final int activeCount;
  final int criticalCount;
  final int warningCount;
  final int historyCount;
  final List<ReportAlarmType> byType;
  final List<ReportAlarmItem> topActiveAlarms;
  factory ReportAlarmSummary.fromJson(Map<String, dynamic> json) =>
      ReportAlarmSummary(
        activeCount: _int(json['activeCount']),
        criticalCount: _int(json['criticalCount']),
        warningCount: _int(json['warningCount']),
        historyCount: _int(json['historyCount']),
        byType: _list(json['byType']).map(ReportAlarmType.fromJson).toList(),
        topActiveAlarms: _list(
          json['topActiveAlarms'],
        ).map(ReportAlarmItem.fromJson).toList(),
      );
}

class ReportAlarmType {
  const ReportAlarmType({
    required this.type,
    required this.title,
    required this.count,
  });
  final String type;
  final String title;
  final int count;
  factory ReportAlarmType.fromJson(Map<String, dynamic> json) =>
      ReportAlarmType(
        type: _text(json['type'], 'UNKNOWN'),
        title: _text(json['title'], 'Alarmes'),
        count: _int(json['count']),
      );
}

class ReportAlarmItem {
  const ReportAlarmItem({
    required this.severity,
    required this.siteName,
    required this.title,
    required this.message,
  });
  final String severity;
  final String siteName;
  final String title;
  final String message;
  factory ReportAlarmItem.fromJson(Map<String, dynamic> json) =>
      ReportAlarmItem(
        severity: _text(json['severity'], 'INFO'),
        siteName: _text(json['siteName'], 'Unnamed site'),
        title: _text(json['title'], 'Alarme'),
        message: _text(json['message'], ''),
      );
}

class ReportRecommendation {
  const ReportRecommendation({
    required this.priority,
    required this.target,
    required this.issue,
    required this.action,
    required this.justification,
  });
  final String priority;
  final String target;
  final String issue;
  final String action;
  final String justification;
  factory ReportRecommendation.fromJson(Map<String, dynamic> json) =>
      ReportRecommendation(
        priority: _text(json['priority'], '-'),
        target: _text(json['target'], '-'),
        issue: _text(json['issue'], ''),
        action: _text(json['action'], ''),
        justification: _text(json['justification'], ''),
      );
}

class ReportTechnicalRow {
  const ReportTechnicalRow({
    required this.siteName,
    required this.status,
    required this.governorate,
    required this.displayedKwh,
    required this.differencePercent,
    required this.dataStatus,
  });
  final String siteName;
  final String status;
  final String governorate;
  final double? displayedKwh;
  final double? differencePercent;
  final String dataStatus;
  factory ReportTechnicalRow.fromJson(Map<String, dynamic> json) =>
      ReportTechnicalRow(
        siteName: _text(json['siteName'], 'Unnamed site'),
        status: _text(json['status'], 'UNKNOWN'),
        governorate: _text(json['governorate'], 'UNKNOWN'),
        displayedKwh: _double(json['displayedKwh']),
        differencePercent: _double(json['differencePercent']),
        dataStatus: _text(json['dataStatus'], 'OK'),
      );
}

class AiNarrative {
  const AiNarrative({
    required this.executiveSummary,
    required this.mainFindings,
    required this.riskAnalysis,
    required this.recommendedActions,
    required this.conclusion,
  });
  final String executiveSummary;
  final List<String> mainFindings;
  final String riskAnalysis;
  final List<String> recommendedActions;
  final String conclusion;
  factory AiNarrative.fromJson(Map<String, dynamic> json) => AiNarrative(
    executiveSummary: _text(json['executiveSummary'], ''),
    mainFindings: _stringList(json['mainFindings']),
    riskAnalysis: _text(json['riskAnalysis'], ''),
    recommendedActions: _stringList(json['recommendedActions']),
    conclusion: _text(json['conclusion'], ''),
  );
}

Map<String, dynamic> _map(dynamic value) =>
    value is Map ? value.cast<String, dynamic>() : <String, dynamic>{};
List<Map<String, dynamic>> _list(dynamic value) => value is List
    ? value
          .whereType<Map>()
          .map((item) => item.cast<String, dynamic>())
          .toList()
    : const <Map<String, dynamic>>[];
List<String> _stringList(dynamic value) => value is List
    ? value
          .map((item) => item.toString())
          .where((item) => item.trim().isNotEmpty)
          .toList()
    : const <String>[];
String _text(dynamic value, String fallback) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? fallback : text;
}

String? _nullableText(dynamic value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

int _int(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

double? _double(dynamic value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse(value.toString().replaceAll(',', '.'));
}
