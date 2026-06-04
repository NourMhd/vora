class HistoryPoint {
  const HistoryPoint({
    required this.collectTime,
    required this.value,
    required this.status,
  });

  final int collectTime;
  final double value;
  final String status;

  factory HistoryPoint.fromJson(Map<String, dynamic> json) {
    return HistoryPoint(
      collectTime: _asInt(json['collectTime']),
      value: _asDouble(json['value']),
      status: json['status']?.toString() ?? 'OK',
    );
  }

  DateTime? get dateTime {
    if (collectTime <= 0) return null;
    final millis = collectTime < 1000000000000 ? collectTime * 1000 : collectTime;
    return DateTime.fromMillisecondsSinceEpoch(millis);
  }

  static int _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  static double _asDouble(dynamic value) {
    if (value is num) return value.toDouble();
    return double.tryParse(value?.toString() ?? '') ?? 0;
  }
}
