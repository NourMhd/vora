class DeviceItem {
  const DeviceItem({
    required this.id,
    required this.devDn,
    required this.devName,
    required this.stationCode,
    required this.esnCode,
    required this.devTypeId,
    required this.model,
    required this.invType,
  });

  final String id;
  final String devDn;
  final String devName;
  final String stationCode;
  final String esnCode;
  final int? devTypeId;
  final String model;
  final String invType;

  factory DeviceItem.fromJson(Map<String, dynamic> json) {
    return DeviceItem(
      id: json['id']?.toString() ?? '',
      devDn: json['devDn']?.toString() ?? '',
      devName: json['devName']?.toString() ?? 'Device',
      stationCode: json['stationCode']?.toString() ?? '',
      esnCode: json['esnCode']?.toString() ?? '',
      devTypeId: _asIntOrNull(json['devTypeId']),
      model: json['model']?.toString() ?? '',
      invType: json['invType']?.toString() ?? '',
    );
  }

  static int? _asIntOrNull(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }
}

class SiteDevicesResponse {
  const SiteDevicesResponse({
    required this.stationCode,
    required this.devices,
  });

  final String stationCode;
  final List<DeviceItem> devices;

  factory SiteDevicesResponse.fromJson(Map<String, dynamic> json) {
    final rawDevices = json['devices'];
    return SiteDevicesResponse(
      stationCode: json['stationCode']?.toString() ?? '',
      devices: rawDevices is List
          ? rawDevices
              .whereType<Map>()
              .map((item) => DeviceItem.fromJson(item.cast<String, dynamic>()))
              .toList()
          : const [],
    );
  }
}
