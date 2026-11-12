class PerformanceReportRow {
  const PerformanceReportRow({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.siteStatus,
    required this.installedPowerKw,
    required this.daysCount,
    required this.filterDaysCount,
    required this.productionDaysCount,
    required this.capturedDaysCount,
    required this.estimatedDaysCount,
    required this.filterStartDate,
    required this.filterEndDate,
    required this.swapDate,
    required this.gridConnectionDate,
    required this.productionStartDate,
    required this.productionEndDate,
    required this.plantType,
    required this.zone,
    required this.governorate,
    required this.expectedKwh,
    required this.capturedKwh,
    required this.estimatedMissingKwh,
    required this.actualKwh,
    required this.differenceKwh,
    required this.differencePercent,
    required this.bonusMalus,
    required this.dataStatus,
  });

  final String brand;
  final String siteCode;
  final String siteName;
  final String siteStatus;
  final double? installedPowerKw;
  final int daysCount;
  final int filterDaysCount;
  final int productionDaysCount;
  final int capturedDaysCount;
  final int estimatedDaysCount;
  final String? filterStartDate;
  final String? filterEndDate;
  final String? swapDate;
  final String? gridConnectionDate;
  final String? productionStartDate;
  final String? productionEndDate;
  final String plantType;
  final String? zone;
  final String? governorate;
  final double? expectedKwh;
  final double? capturedKwh;
  final double? estimatedMissingKwh;
  final double? actualKwh;
  final double? differenceKwh;
  final double? differencePercent;
  final String bonusMalus;
  final String dataStatus;

  bool get isBonus => bonusMalus.toUpperCase() == 'BONUS';
  bool get isMalus => bonusMalus.toUpperCase() == 'MALUS';
  bool get hasMissingSwapDate => dataStatus == 'MISSING_SWAP_DATE';
  bool get hasMissingData => hasMissingSwapDate || dataStatus == 'MISSING_EXPECTED_KWH' || dataStatus == 'MISSING_REAL_KWH';
  bool get hasEstimatedSwapGap => estimatedDaysCount > 0 && (estimatedMissingKwh ?? 0) > 0;

  factory PerformanceReportRow.fromJson(Map<String, dynamic> json) {
    final legacyDays = _asInt(json['daysCount']);

    return PerformanceReportRow(
      brand: _asText(json['brand'], fallback: 'OTHER'),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: 'Unnamed site'),
      siteStatus: _asText(
        json['siteStatus'] ?? json['status'],
        fallback: 'UNKNOWN',
      ),
      installedPowerKw: _nullableDouble(json['installedPowerKw']),
      daysCount: legacyDays,
      filterDaysCount: _asInt(json['filterDaysCount'], fallback: legacyDays),
      productionDaysCount: _asInt(json['productionDaysCount'], fallback: legacyDays),
      capturedDaysCount: _asInt(json['capturedDaysCount']),
      estimatedDaysCount: _asInt(json['estimatedDaysCount']),
      filterStartDate: _nullableText(json['filterStartDate']),
      filterEndDate: _nullableText(json['filterEndDate']),
      swapDate: _nullableText(json['swapDate']),
      gridConnectionDate: _nullableText(json['gridConnectionDate']),
      productionStartDate: _nullableText(json['productionStartDate']),
      productionEndDate: _nullableText(json['productionEndDate']),
      plantType: _asText(json['plantType'], fallback: 'UNKNOWN'),
      zone: _nullableText(json['zone']),
      governorate: _nullableText(json['governorate']),
      expectedKwh: _nullableDouble(json['expectedKwh']),
      capturedKwh: _nullableDouble(json['capturedKwh']),
      estimatedMissingKwh: _nullableDouble(json['estimatedMissingKwh']),
      actualKwh: _nullableDouble(json['actualKwh']),
      differenceKwh: _nullableDouble(json['differenceKwh']),
      differencePercent: _nullableDouble(json['differencePercent']),
      bonusMalus: _asText(json['bonusMalus'], fallback: 'N/A'),
      dataStatus: _asText(json['dataStatus'], fallback: 'OK'),
    );
  }

  static String _asText(dynamic value, {required String fallback}) {
    final text = value?.toString().trim();
    return text == null || text.isEmpty ? fallback : text;
  }

  static String? _nullableText(dynamic value) {
    final text = value?.toString().trim();
    return text == null || text.isEmpty ? null : text;
  }

  static int _asInt(dynamic value, {int fallback = 0}) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? fallback;
  }

  static double? _nullableDouble(dynamic value) {
    if (value == null) return null;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString());
  }
}
