class AlarmReport {
  const AlarmReport({
    required this.generatedAt,
    required this.activeCount,
    required this.historyCount,
    required this.activeSections,
    required this.history,
  });

  final DateTime? generatedAt;
  final int activeCount;
  final int historyCount;
  final List<AlarmSection> activeSections;
  final List<AlarmItem> history;

  factory AlarmReport.fromJson(Map<String, dynamic> json) {
    return AlarmReport(
      generatedAt: DateTime.tryParse(json['generatedAt']?.toString() ?? ''),
      activeCount: _asInt(json['activeCount']),
      historyCount: _asInt(json['historyCount']),
      activeSections: (json['activeSections'] as List? ?? const [])
          .whereType<Map>()
          .map((item) => AlarmSection.fromJson(item.cast<String, dynamic>()))
          .toList(),
      history: (json['history'] as List? ?? const [])
          .whereType<Map>()
          .map((item) => AlarmItem.fromJson(item.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class AlarmSection {
  const AlarmSection({
    required this.type,
    required this.title,
    required this.description,
    required this.count,
    required this.alarms,
  });

  final String type;
  final String title;
  final String description;
  final int count;
  final List<AlarmItem> alarms;

  factory AlarmSection.fromJson(Map<String, dynamic> json) {
    return AlarmSection(
      type: _asText(json['type'], fallback: 'UNKNOWN'),
      title: _asText(json['title'], fallback: 'Alarmes'),
      description: _asText(json['description'], fallback: ''),
      count: _asInt(json['count']),
      alarms: (json['alarms'] as List? ?? const [])
          .whereType<Map>()
          .map((item) => AlarmItem.fromJson(item.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class AlarmItem {
  const AlarmItem({
    required this.id,
    required this.alarmKey,
    required this.type,
    required this.active,
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.siteStatus,
    required this.governorate,
    required this.title,
    required this.message,
    required this.severity,
    required this.metricValue,
    required this.thresholdValue,
    required this.unit,
    required this.bestSiteName,
    required this.bestSiteCode,
    required this.bestValue,
    required this.monitoredDate,
    required this.lastMonitoringAt,
    required this.firstDetectedAt,
    required this.lastDetectedAt,
    required this.clearedAt,
  });

  final String id;
  final String alarmKey;
  final String type;
  final bool active;
  final String brand;
  final String siteCode;
  final String siteName;
  final String siteStatus;
  final String governorate;
  final String title;
  final String message;
  final String severity;
  final double? metricValue;
  final double? thresholdValue;
  final String? unit;
  final String? bestSiteName;
  final String? bestSiteCode;
  final double? bestValue;
  final DateTime? monitoredDate;
  final DateTime? lastMonitoringAt;
  final DateTime? firstDetectedAt;
  final DateTime? lastDetectedAt;
  final DateTime? clearedAt;

  factory AlarmItem.fromJson(Map<String, dynamic> json) {
    return AlarmItem(
      id: _asText(json['id'], fallback: ''),
      alarmKey: _asText(json['alarmKey'], fallback: ''),
      type: _asText(json['type'], fallback: 'UNKNOWN'),
      active: json['active'] == true,
      brand: _asText(json['brand'], fallback: 'OTHER'),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: 'Unnamed site'),
      siteStatus: _asText(json['siteStatus'], fallback: 'UNKNOWN'),
      governorate: _asText(json['governorate'], fallback: '—'),
      title: _asText(json['title'], fallback: 'Alarme'),
      message: _asText(json['message'], fallback: ''),
      severity: _asText(json['severity'], fallback: 'INFO'),
      metricValue: _asNullableDouble(json['metricValue']),
      thresholdValue: _asNullableDouble(json['thresholdValue']),
      unit: json['unit']?.toString(),
      bestSiteName: json['bestSiteName']?.toString(),
      bestSiteCode: json['bestSiteCode']?.toString(),
      bestValue: _asNullableDouble(json['bestValue']),
      monitoredDate: _parseDateOnly(json['monitoredDate']),
      lastMonitoringAt: DateTime.tryParse(json['lastMonitoringAt']?.toString() ?? ''),
      firstDetectedAt: DateTime.tryParse(json['firstDetectedAt']?.toString() ?? ''),
      lastDetectedAt: DateTime.tryParse(json['lastDetectedAt']?.toString() ?? ''),
      clearedAt: DateTime.tryParse(json['clearedAt']?.toString() ?? ''),
    );
  }

  String get displaySite => siteName == siteCode || siteCode == '-' ? siteName : '$siteName · $siteCode';
}

int _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '') ?? 0;
}

double? _asNullableDouble(dynamic value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse(value.toString().replaceAll(',', '.'));
}

String _asText(dynamic value, {required String fallback}) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? fallback : text;
}

DateTime? _parseDateOnly(dynamic value) {
  final raw = value?.toString().trim();
  if (raw == null || raw.isEmpty) return null;
  final parsed = DateTime.tryParse(raw);
  if (parsed != null) return parsed;
  final parts = raw.split('-').map((part) => int.tryParse(part)).toList();
  if (parts.length == 3 && parts.every((part) => part != null)) {
    return DateTime(parts[0]!, parts[1]!, parts[2]!);
  }
  return null;
}
