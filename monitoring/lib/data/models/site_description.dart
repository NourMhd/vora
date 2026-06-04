class SiteDescriptionPhoto {
  const SiteDescriptionPhoto({
    required this.id,
    required this.filename,
    required this.dataUrl,
    this.updatedAt,
  });

  final String id;
  final String filename;
  final String dataUrl;
  final String? updatedAt;

  factory SiteDescriptionPhoto.fromJson(Map<String, dynamic> json) {
    return SiteDescriptionPhoto(
      id: json['id']?.toString() ?? '',
      filename: json['filename']?.toString() ?? 'site-photo.jpg',
      dataUrl: json['dataUrl']?.toString() ?? '',
      updatedAt: json['updatedAt']?.toString(),
    );
  }
}

class SiteDescription {
  const SiteDescription({
    required this.brand,
    required this.siteCode,
    required this.siteName,
    this.notice,
    this.comparisonPeriod,
    this.governorate,
    this.comparableSitesCount,
    this.bestSiteName,
    this.bestSiteCode,
    this.currentSpecificYieldKwhPerKwp,
    this.bestSpecificYieldKwhPerKwp,
    this.gapPercent,
    this.comparisonPercent,
    this.swapDate,
    this.gridConnectionDate,
    this.totalEnergyKwh,
    this.performanceIndexDate,
    this.performanceIndexPercent,
    this.performanceActualKwh,
    this.performanceTheoreticalKwh,
    this.performanceStatus,
    this.note,
    this.orientationDeg,
    required this.hasPhoto,
    this.photoDataUrl,
    this.photoFilename,
    this.photoUpdatedAt,
    this.photos = const [],
  });

  final String brand;
  final String siteCode;
  final String siteName;
  final String? notice;
  final String? comparisonPeriod;
  final String? governorate;
  final int? comparableSitesCount;
  final String? bestSiteName;
  final String? bestSiteCode;
  final double? currentSpecificYieldKwhPerKwp;
  final double? bestSpecificYieldKwhPerKwp;
  final double? gapPercent;
  final double? comparisonPercent;
  final String? swapDate;
  final String? gridConnectionDate;
  final double? totalEnergyKwh;
  final String? performanceIndexDate;
  final double? performanceIndexPercent;
  final double? performanceActualKwh;
  final double? performanceTheoreticalKwh;
  final String? performanceStatus;
  final String? note;
  final double? orientationDeg;
  final bool hasPhoto;
  final String? photoDataUrl;
  final String? photoFilename;
  final String? photoUpdatedAt;
  final List<SiteDescriptionPhoto> photos;

  factory SiteDescription.fromJson(Map<String, dynamic> json) {
    final parsedPhotos = _photos(json['photos']);
    final legacyDataUrl = json['photoDataUrl']?.toString();
    final legacyPhoto = legacyDataUrl == null || legacyDataUrl.isEmpty
        ? null
        : SiteDescriptionPhoto(
            id: 'legacy-photo',
            filename: json['photoFilename']?.toString() ?? 'site-photo.jpg',
            dataUrl: legacyDataUrl,
            updatedAt: json['photoUpdatedAt']?.toString(),
          );

    final photos = parsedPhotos.isNotEmpty
        ? parsedPhotos
        : legacyPhoto == null
            ? const <SiteDescriptionPhoto>[]
            : <SiteDescriptionPhoto>[legacyPhoto];

    return SiteDescription(
      brand: json['brand']?.toString() ?? '',
      siteCode: json['siteCode']?.toString() ?? '',
      siteName: json['siteName']?.toString() ?? '',
      notice: json['notice']?.toString(),
      comparisonPeriod: json['comparisonPeriod']?.toString(),
      governorate: json['governorate']?.toString(),
      comparableSitesCount: _toInt(json['comparableSitesCount']),
      bestSiteName: json['bestSiteName']?.toString(),
      bestSiteCode: json['bestSiteCode']?.toString(),
      currentSpecificYieldKwhPerKwp: _toDouble(json['currentSpecificYieldKwhPerKwp']),
      bestSpecificYieldKwhPerKwp: _toDouble(json['bestSpecificYieldKwhPerKwp']),
      gapPercent: _toDouble(json['gapPercent']),
      comparisonPercent: _toDouble(json['comparisonPercent']),
      swapDate: json['swapDate']?.toString(),
      gridConnectionDate: json['gridConnectionDate']?.toString(),
      totalEnergyKwh: _toDouble(json['totalEnergyKwh']),
      performanceIndexDate: json['performanceIndexDate']?.toString(),
      performanceIndexPercent: _toDouble(json['performanceIndexPercent']),
      performanceActualKwh: _toDouble(json['performanceActualKwh']),
      performanceTheoreticalKwh: _toDouble(json['performanceTheoreticalKwh']),
      performanceStatus: json['performanceStatus']?.toString(),
      note: json['note']?.toString(),
      orientationDeg: _toDouble(json['orientationDeg']),
      hasPhoto: json['hasPhoto'] == true || photos.isNotEmpty,
      photoDataUrl: json['photoDataUrl']?.toString(),
      photoFilename: json['photoFilename']?.toString(),
      photoUpdatedAt: json['photoUpdatedAt']?.toString(),
      photos: photos,
    );
  }

  static List<SiteDescriptionPhoto> _photos(dynamic value) {
    if (value is! List) return const [];
    return value
        .whereType<Map>()
        .map((item) => SiteDescriptionPhoto.fromJson(item.cast<String, dynamic>()))
        .where((photo) => photo.dataUrl.isNotEmpty)
        .toList();
  }

  static double? _toDouble(dynamic value) {
    if (value == null) return null;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString().replaceAll(',', '.'));
  }

  static int? _toInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}
