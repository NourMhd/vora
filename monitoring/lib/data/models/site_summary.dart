class SiteSummary {
  const SiteSummary({
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

  factory SiteSummary.fromJson(Map<String, dynamic> json) {
    return SiteSummary(
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

  double energyForPeriod(String period) {
    switch (period) {
      case 'month':
        return monthEnergyKwh;
      case 'year':
        return yearEnergyKwh;
      case 'total':
        return totalEnergyKwh;
      case 'day':
      default:
        return dayEnergyKwh;
    }
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
