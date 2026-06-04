class DashboardSummary {
  const DashboardSummary({
    required this.totalSites,
    required this.totalInstalledPowerKw,
    required this.totalDayEnergyKwh,
    required this.totalMonthEnergyKwh,
    required this.totalYearEnergyKwh,
    required this.totalEnergyKwh,
    required this.healthySites,
    required this.faultySites,
    required this.disconnectedSites,
  });

  final int totalSites;
  final double totalInstalledPowerKw;
  final double totalDayEnergyKwh;
  final double totalMonthEnergyKwh;
  final double totalYearEnergyKwh;
  final double totalEnergyKwh;
  final int healthySites;
  final int faultySites;
  final int disconnectedSites;

  factory DashboardSummary.empty() => const DashboardSummary(
        totalSites: 0,
        totalInstalledPowerKw: 0,
        totalDayEnergyKwh: 0,
        totalMonthEnergyKwh: 0,
        totalYearEnergyKwh: 0,
        totalEnergyKwh: 0,
        healthySites: 0,
        faultySites: 0,
        disconnectedSites: 0,
      );

  factory DashboardSummary.fromJson(Map<String, dynamic> json) {
    return DashboardSummary(
      totalSites: _asInt(json['totalSites']),
      totalInstalledPowerKw: _asDouble(json['totalInstalledPowerKw']),
      totalDayEnergyKwh: _asDouble(json['totalDayEnergyKwh']),
      totalMonthEnergyKwh: _asDouble(json['totalMonthEnergyKwh']),
      totalYearEnergyKwh: _asDouble(json['totalYearEnergyKwh']),
      totalEnergyKwh: _asDouble(json['totalEnergyKwh']),
      healthySites: _asInt(json['healthySites']),
      faultySites: _asInt(json['faultySites']),
      disconnectedSites: _asInt(json['disconnectedSites']),
    );
  }

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString().trim() ?? '') ?? 0;
  }

  static double _asDouble(dynamic value) {
    if (value is num) return value.toDouble();

    final text = value?.toString().trim().replaceAll(',', '.') ?? '';
    return double.tryParse(text) ?? 0;
  }
}