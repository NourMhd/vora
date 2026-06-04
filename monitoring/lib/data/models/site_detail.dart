import 'history_point.dart';

class SiteDetail {
  const SiteDetail({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    required this.status,
    required this.installedPowerKw,
    required this.gridConnectionDate,
    this.swapDate,
    required this.dayEnergyKwh,
    required this.monthEnergyKwh,
    required this.yearEnergyKwh,
    required this.totalEnergyKwh,
  });

  final String brand;
  final String siteCode;
  final String siteName;
  final String status;
  final double installedPowerKw;
  final String? gridConnectionDate;
  final String? swapDate;
  final double dayEnergyKwh;
  final double monthEnergyKwh;
  final double yearEnergyKwh;
  final double totalEnergyKwh;

  factory SiteDetail.fromJson(Map<String, dynamic> json) {
    return SiteDetail(
      brand: _asText(json['brand'], fallback: 'OTHER'),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: 'Unnamed site'),
      status: _asText(json['status'], fallback: 'unknown'),
      installedPowerKw: _asDouble(json['installedPowerKw']),
      gridConnectionDate: json['gridConnectionDate']?.toString(),
      swapDate: json['swapDate']?.toString(),
      dayEnergyKwh: _asDouble(json['dayEnergyKwh']),
      monthEnergyKwh: _asDouble(json['monthEnergyKwh']),
      yearEnergyKwh: _asDouble(json['yearEnergyKwh']),
      totalEnergyKwh: _asDouble(json['totalEnergyKwh']),
    );
  }

  static String _asText(dynamic value, {required String fallback}) {
    final text = value?.toString().trim();
    return text == null || text.isEmpty ? fallback : text;
  }

  static double _asDouble(dynamic value) {
    if (value is num) return value.toDouble();
    return double.tryParse(value?.toString() ?? '') ?? 0;
  }
}

class SiteDetailFull {
  const SiteDetailFull({
    required this.summary,
    required this.dayHistory,
    required this.monthHistory,
    required this.yearHistory,
    required this.allTimeHistory,
  });

  final SiteDetail summary;
  final List<HistoryPoint> dayHistory;
  final List<HistoryPoint> monthHistory;
  final List<HistoryPoint> yearHistory;
  final List<HistoryPoint> allTimeHistory;

  factory SiteDetailFull.fromJson(Map<String, dynamic> json) {
    return SiteDetailFull(
      summary: SiteDetail.fromJson((json['summary'] as Map?)?.cast<String, dynamic>() ?? const {}),
      dayHistory: _points(json['dayHistory']),
      monthHistory: _points(json['monthHistory']),
      yearHistory: _points(json['yearHistory']),
      allTimeHistory: _points(json['allTimeHistory']),
    );
  }

  static List<HistoryPoint> _points(dynamic value) {
    if (value is! List) return const [];
    return value
        .whereType<Map>()
        .map((item) => HistoryPoint.fromJson(item.cast<String, dynamic>()))
        .toList();
  }
}
