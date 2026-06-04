class SiteSwapDate {
  const SiteSwapDate({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    this.gridConnectionDate,
    this.swapDate,
  });

  final String brand;
  final String siteCode;
  final String siteName;
  final String? gridConnectionDate;
  final String? swapDate;

  String get key => '$brand::$siteCode';

  factory SiteSwapDate.fromJson(Map<String, dynamic> json) {
    return SiteSwapDate(
      brand: _asText(json['brand'], fallback: 'OTHER'),
      siteCode: _asText(json['siteCode'], fallback: '-'),
      siteName: _asText(json['siteName'], fallback: 'Unnamed site'),
      gridConnectionDate: _nullableText(json['gridConnectionDate']),
      swapDate: _nullableText(json['swapDate']),
    );
  }

  SiteSwapDate copyWith({
    String? brand,
    String? siteCode,
    String? siteName,
    String? gridConnectionDate,
    String? swapDate,
  }) {
    return SiteSwapDate(
      brand: brand ?? this.brand,
      siteCode: siteCode ?? this.siteCode,
      siteName: siteName ?? this.siteName,
      gridConnectionDate: gridConnectionDate ?? this.gridConnectionDate,
      swapDate: swapDate,
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
}
class SiteSwapDatePage {
  const SiteSwapDatePage({
    required this.items,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  final List<SiteSwapDate> items;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;

  factory SiteSwapDatePage.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];

    return SiteSwapDatePage(
      items: rawItems is List
          ? rawItems
              .whereType<Map>()
              .map((item) => SiteSwapDate.fromJson(item.cast<String, dynamic>()))
              .toList()
          : const [],
      page: _asInt(json['page']),
      size: _asInt(json['size'], fallback: 50),
      totalElements: _asInt(json['totalElements']),
      totalPages: _asInt(json['totalPages']),
    );
  }

  static int _asInt(dynamic value, {int fallback = 0}) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? fallback;
  }
}

class SiteSwapDateUpdate {
  const SiteSwapDateUpdate({
    required this.brand,
    required this.siteCode,
    required this.swapDate,
  });

  final String brand;
  final String siteCode;
  final String? swapDate;

  Map<String, dynamic> toJson() {
    return {
      'brand': brand,
      'siteCode': siteCode,
      'swapDate': swapDate,
    };
  }
}