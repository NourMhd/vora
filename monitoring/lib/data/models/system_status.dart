class HealthStatus {
  const HealthStatus({
    required this.status,
    this.checkedAt,
    required this.checks,
  });

  final String status;
  final DateTime? checkedAt;
  final Map<String, dynamic> checks;

  factory HealthStatus.fromJson(Map<String, dynamic> json) {
    return HealthStatus(
      status: json['status']?.toString() ?? 'UNKNOWN',
      checkedAt: DateTime.tryParse(json['checkedAt']?.toString() ?? ''),
      checks: (json['checks'] as Map?)?.cast<String, dynamic>() ?? const {},
    );
  }
}

class SyncStatus {
  const SyncStatus({
    this.brand,
    this.type,
    required this.status,
    this.message,
    this.startedAt,
    this.finishedAt,
    required this.stats,
  });

  final String? brand;
  final String? type;
  final String status;
  final String? message;
  final DateTime? startedAt;
  final DateTime? finishedAt;
  final Map<String, dynamic> stats;

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      brand: json['brand']?.toString(),
      type: json['type']?.toString(),
      status: json['status']?.toString() ?? 'UNKNOWN',
      message: json['message']?.toString(),
      startedAt: DateTime.tryParse(json['startedAt']?.toString() ?? ''),
      finishedAt: DateTime.tryParse(json['finishedAt']?.toString() ?? ''),
      stats: (json['stats'] as Map?)?.cast<String, dynamic>() ?? const {},
    );
  }
}
