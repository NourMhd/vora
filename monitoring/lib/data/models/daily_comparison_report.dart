class DailyComparisonReport {
  const DailyComparisonReport({
    required this.sites,
    required this.startDate,
    required this.endDate,
    required this.historicalRows,
    required this.estimatedRows,
    required this.historicalTotals,
    required this.estimatedTotals,
  });

  final List<DailyComparisonSite> sites;
  final String? startDate;
  final String? endDate;
  final List<DailyComparisonRow> historicalRows;
  final List<DailyComparisonRow> estimatedRows;
  final DailyComparisonRow? historicalTotals;
  final DailyComparisonRow? estimatedTotals;

  factory DailyComparisonReport.empty() => const DailyComparisonReport(
        sites: [],
        startDate: null,
        endDate: null,
        historicalRows: [],
        estimatedRows: [],
        historicalTotals: null,
        estimatedTotals: null,
      );

  factory DailyComparisonReport.fromJson(Map<String, dynamic> json) {
    return DailyComparisonReport(
      sites: _list(json['sites'])
          .whereType<Map>()
          .map((item) => DailyComparisonSite.fromJson(item.cast<String, dynamic>()))
          .toList(),
      startDate: _nullableText(json['startDate']),
      endDate: _nullableText(json['endDate']),
      historicalRows: _list(json['historicalRows'])
          .whereType<Map>()
          .map((item) => DailyComparisonRow.fromJson(item.cast<String, dynamic>()))
          .toList(),
      estimatedRows: _list(json['estimatedRows'])
          .whereType<Map>()
          .map((item) => DailyComparisonRow.fromJson(item.cast<String, dynamic>()))
          .toList(),
      historicalTotals: json['historicalTotals'] is Map
          ? DailyComparisonRow.fromJson((json['historicalTotals'] as Map).cast<String, dynamic>())
          : null,
      estimatedTotals: json['estimatedTotals'] is Map
          ? DailyComparisonRow.fromJson((json['estimatedTotals'] as Map).cast<String, dynamic>())
          : null,
    );
  }
}

class DailyComparisonSite {
  const DailyComparisonSite({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.swapDate,
    required this.gridConnectionDate,
    required this.installedPowerKw,
    required this.governorate,
    required this.zone,
  });

  final String? brand;
  final String siteCode;
  final String siteName;
  final String? swapDate;
  final String? gridConnectionDate;
  final double? installedPowerKw;
  final String? governorate;
  final String? zone;

  String get label => siteName.trim().isEmpty ? siteCode : siteName;

  String get powerLabel => installedPowerKw == null || installedPowerKw! <= 0
      ? 'Puissance —'
      : '${_formatNumber(installedPowerKw!)} kWc';

  String get headerLabel => '$label\n$powerLabel';

  factory DailyComparisonSite.fromJson(Map<String, dynamic> json) {
    return DailyComparisonSite(
      brand: _nullableText(json['brand']),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: _asText(json['siteCode'], fallback: '-')),
      swapDate: _nullableText(json['swapDate']),
      gridConnectionDate: _nullableText(json['gridConnectionDate']),
      installedPowerKw: _nullableDouble(json['installedPowerKw']),
      governorate: _nullableText(json['governorate']),
      zone: _nullableText(json['zone']),
    );
  }
}

class DailyComparisonSelectableSite extends DailyComparisonSite {
  const DailyComparisonSelectableSite({
    required super.brand,
    required super.siteCode,
    required super.siteName,
    required super.swapDate,
    required super.gridConnectionDate,
    required super.installedPowerKw,
    required super.governorate,
    required super.zone,
  });

  factory DailyComparisonSelectableSite.fromJson(Map<String, dynamic> json) {
    return DailyComparisonSelectableSite(
      brand: _nullableText(json['brand']),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: _asText(json['siteCode'], fallback: '-')),
      swapDate: _nullableText(json['swapDate']),
      gridConnectionDate: _nullableText(json['gridConnectionDate']),
      installedPowerKw: _nullableDouble(json['installedPowerKw']),
      governorate: _nullableText(json['governorate']),
      zone: _nullableText(json['zone']),
    );
  }
}

class DailyComparisonRow {
  const DailyComparisonRow({
    required this.date,
    required this.label,
    required this.cells,
  });

  final String? date;
  final String label;
  final List<DailyComparisonCell> cells;

  factory DailyComparisonRow.fromJson(Map<String, dynamic> json) {
    return DailyComparisonRow(
      date: _nullableText(json['date']),
      label: _asText(json['label'], fallback: _nullableText(json['date']) ?? '—'),
      cells: _list(json['cells'])
          .whereType<Map>()
          .map((item) => DailyComparisonCell.fromJson(item.cast<String, dynamic>()))
          .toList(),
    );
  }
}

class DailyComparisonCell {
  const DailyComparisonCell({
    required this.siteCode,
    required this.value,
    required this.displayValue,
    required this.estimated,
    required this.status,
  });

  final String siteCode;
  final double? value;
  final String displayValue;
  final bool estimated;
  final String status;

  bool get hasValue => value != null;

  factory DailyComparisonCell.fromJson(Map<String, dynamic> json) {
    return DailyComparisonCell(
      siteCode: _asText(json['siteCode'], fallback: '-'),
      value: _nullableDouble(json['value']),
      displayValue: _asText(json['displayValue'], fallback: '—'),
      estimated: json['estimated'] == true,
      status: _asText(json['status'], fallback: 'UNKNOWN'),
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

double? _nullableDouble(dynamic value) {
  if (value == null) return null;
  if (value is num) return value.toDouble();
  return double.tryParse(value.toString().replaceAll(',', '.'));
}

String _formatNumber(double value) {
  final rounded = (value * 100).round() / 100;
  if (rounded == rounded.roundToDouble()) return rounded.toStringAsFixed(0);
  return rounded.toStringAsFixed(2).replaceAll(RegExp(r'0+$'), '').replaceAll(RegExp(r'\.$'), '');
}
