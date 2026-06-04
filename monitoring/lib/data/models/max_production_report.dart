class MaxProductionReport {
  const MaxProductionReport({
    required this.startDate,
    required this.endDate,
    required this.sitesCount,
    required this.sitesWithMonitoredValueCount,
    required this.rows,
  });

  final String? startDate;
  final String? endDate;
  final int sitesCount;
  final int sitesWithMonitoredValueCount;
  final List<MaxProductionRow> rows;

  factory MaxProductionReport.empty() => const MaxProductionReport(
        startDate: null,
        endDate: null,
        sitesCount: 0,
        sitesWithMonitoredValueCount: 0,
        rows: [],
      );

  factory MaxProductionReport.fromJson(Map<String, dynamic> json) {
    return MaxProductionReport(
      startDate: _nullableText(json['startDate']),
      endDate: _nullableText(json['endDate']),
      sitesCount: _asInt(json['sitesCount']),
      sitesWithMonitoredValueCount: _asInt(json['sitesWithMonitoredValueCount']),
      rows: _list(json['rows'])
          .whereType<Map>()
          .map((item) => MaxProductionRow.fromJson(item.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class MaxProductionRow {
  const MaxProductionRow({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.installedPowerKw,
    required this.maxProductionKwh,
    required this.maxProductionDate,
    required this.kwhPerKwc,
    required this.dataStatus,
  });

  final String? brand;
  final String siteCode;
  final String siteName;
  final double? installedPowerKw;
  final double? maxProductionKwh;
  final String? maxProductionDate;
  final double? kwhPerKwc;
  final String dataStatus;

  String get label => siteName.trim().isEmpty ? siteCode : siteName;
  bool get hasMaxProduction => maxProductionKwh != null;

  factory MaxProductionRow.fromJson(Map<String, dynamic> json) {
    return MaxProductionRow(
      brand: _nullableText(json['brand']),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: _asText(json['siteCode'], fallback: '-')),
      installedPowerKw: _nullableDouble(json['installedPowerKw']),
      maxProductionKwh: _nullableDouble(json['maxProductionKwh']),
      maxProductionDate: _nullableText(json['maxProductionDate']),
      kwhPerKwc: _nullableDouble(json['kwhPerKwc']),
      dataStatus: _asText(json['dataStatus'], fallback: 'UNKNOWN'),
    );
  }
}

List<dynamic> _list(dynamic value) => value is List ? value : const [];

String _asText(dynamic value, {required String fallback}) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? fallback : text;
}

String? _nullableText(dynamic value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

int _asInt(dynamic value) {
  if (value == null) return 0;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString()) ?? 0;
}

double? _nullableDouble(dynamic value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse(value.toString().replaceAll(',', '.'));
}
