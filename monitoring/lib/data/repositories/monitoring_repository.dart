import 'dart:typed_data';

import '../../core/network/api_client.dart';
import '../models/ai_report.dart';
import '../models/alarm_report.dart';
import '../models/daily_comparison_report.dart';
import '../models/dashboard_summary.dart';
import '../models/device_item.dart';
import '../models/history_point.dart';
import '../models/performance_report.dart';
import '../models/site_description.dart';
import '../models/site_detail.dart';
import '../models/site_summary.dart';
import '../models/site_swap_date.dart';
import '../models/system_status.dart';

class MonitoringRepository {
  const MonitoringRepository(this._apiClient);

  final ApiClient _apiClient;

  Future<Map<String, String>> getLatestCollectedPeriods({required String brand, required String siteCode}) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/history/latest-periods',
    );
    if (data is! Map) return {};
    return data.map((key, value) => MapEntry(key.toString(), value.toString()));
  }

  Future<List<String>> getBrands() async {
    final data = await _apiClient.get('/api/brands');
    if (data is! List) return const [];
    return data.map((item) => item.toString()).toList();
  }

  Future<DashboardSummary> getDashboardSummary({String? brand}) async {
    final path = brand == null || brand.isEmpty || brand == 'ALL'
        ? '/api/dashboard/summary'
        : '/api/brands/${Uri.encodeComponent(brand)}/dashboard/summary';
    final data = await _apiClient.get(path);
    return DashboardSummary.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<List<SiteSummary>> getSites({String? brand}) async {
    final path = brand == null || brand.isEmpty || brand == 'ALL'
        ? '/api/sites'
        : '/api/brands/${Uri.encodeComponent(brand)}/sites';
    final data = await _apiClient.get(path);
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((item) => SiteSummary.fromJson(item.cast<String, dynamic>()))
        .toList();
  }

  Future<SiteDetail> getSiteDetail({
    required String brand,
    required String siteCode,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}',
    );
    return SiteDetail.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<SiteDetailFull> getSiteFull({
    required String brand,
    required String siteCode,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/full',
    );
    return SiteDetailFull.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<List<HistoryPoint>> getHistory({
    required String brand,
    required String siteCode,
    required String type,
    required String value,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/history',
      queryParameters: {
        'type': type,
        'value': value,
      },
    );
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((item) => HistoryPoint.fromJson(item.cast<String, dynamic>()))
        .toList();
  }

  Future<List<HistoryPoint>> getAllTimeHistory({
    required String brand,
    required String siteCode,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/history/all-time',
    );
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((item) => HistoryPoint.fromJson(item.cast<String, dynamic>()))
        .toList();
  }


  Future<SiteDescription> getSiteDescription({
    required String brand,
    required String siteCode,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/description',
    );
    return SiteDescription.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<SiteDescription> saveSiteDescription({
    required String brand,
    required String siteCode,
    required String note,
    required String orientationDeg,
    List<String> retainedPhotoIds = const [],
    List<MultipartFilePayload> photosToAdd = const [],
  }) async {
    final data = await _apiClient.putMultipart(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/description',
      fields: {
        'note': note,
        'orientationDeg': orientationDeg,
        'retainedPhotoIds': retainedPhotoIds.isEmpty ? [''] : retainedPhotoIds,
      },
      files: photosToAdd,
    );
    return SiteDescription.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<SiteDevicesResponse> getDevices({
    required String brand,
    required String siteCode,
  }) async {
    final data = await _apiClient.get(
      '/api/brands/${Uri.encodeComponent(brand)}/sites/${Uri.encodeComponent(siteCode)}/devices',
    );
    return SiteDevicesResponse.fromJson((data as Map).cast<String, dynamic>());
  }


  Future<List<PerformanceReportRow>> getPerformanceReport({
    required String period,
    required int year,
    required int month,
    required int decade,
    String? startDate,
    String? endDate,
  }) async {
    final data = await _apiClient.get(
      '/api/performance',
      queryParameters: {
        'period': period,
        'year': year,
        if (period != 'YEAR') 'month': month,
        if (period == 'TEN_DAYS') 'decade': decade,
        if (startDate != null && startDate.isNotEmpty) 'startDate': startDate,
        if (endDate != null && endDate.isNotEmpty) 'endDate': endDate,
      },
    );

    if (data is! List) return const [];

    return data
        .whereType<Map>()
        .map((item) => PerformanceReportRow.fromJson(item.cast<String, dynamic>()))
        .toList();
  }

  Future<DailyComparisonReport> getAriDailyComparison({
    List<String>? siteCodes,
    String? startDate,
    String? endDate,
  }) async {
    final data = await _apiClient.get(
      '/api/comparatif/ari',
      queryParameters: {
        if (siteCodes != null && siteCodes.isNotEmpty) 'siteCodes': siteCodes.join(','),
        if (startDate != null && startDate.isNotEmpty) 'startDate': startDate,
        if (endDate != null && endDate.isNotEmpty) 'endDate': endDate,
      },
    );
    return DailyComparisonReport.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<List<DailyComparisonSelectableSite>> getAriComparisonSites() async {
    final data = await _apiClient.get('/api/comparatif/ari/sites');
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((item) => DailyComparisonSelectableSite.fromJson(item.cast<String, dynamic>()))
        .toList();
  }


  Future<AlarmReport> getAlarmReport() async {
    final data = await _apiClient.get('/api/alarms');
    return AlarmReport.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<AiReportPreview> getAiReportPreview({
    required String startDate,
    required String endDate,
    required String scope,
    String? scopeValue,
    required String type,
  }) async {
    final data = await _apiClient.get(
      '/api/ai-report/preview',
      queryParameters: {
        'startDate': startDate,
        'endDate': endDate,
        'scope': scope,
        if (scopeValue != null && scopeValue.trim().isNotEmpty) 'scopeValue': scopeValue.trim(),
        'type': type,
      },
    );
    return AiReportPreview.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<Uint8List> downloadAiReportPdf({
    required String startDate,
    required String endDate,
    required String scope,
    String? scopeValue,
    required String type,
  }) {
    return _apiClient.getBytes(
      '/api/ai-report/pdf',
      queryParameters: {
        'startDate': startDate,
        'endDate': endDate,
        'scope': scope,
        if (scopeValue != null && scopeValue.trim().isNotEmpty) 'scopeValue': scopeValue.trim(),
        'type': type,
      },
    );
  }

  Future<HealthStatus> getHealth() async {
    final data = await _apiClient.get('/api/health');
    return HealthStatus.fromJson((data as Map).cast<String, dynamic>());
  }

  Future<List<SyncStatus>> getSyncStatus() async {
    final data = await _apiClient.get('/api/sync/status');
    if (data is! List) return const [];
    return data
        .whereType<Map>()
        .map((item) => SyncStatus.fromJson(item.cast<String, dynamic>()))
        .toList();
  }

Future<SiteSwapDatePage> getSiteSwapDates({
  required int page,
  int size = 50,
  String? search,
  String swapFilter = 'all',
}) async {
  final data = await _apiClient.get(
    '/api/sites/swap-dates',
    queryParameters: {
      'page': page,
      'size': size,
      if (search != null && search.trim().isNotEmpty) 'search': search.trim(),
      'swapFilter': swapFilter,
    },
  );

  return SiteSwapDatePage.fromJson((data as Map).cast<String, dynamic>());
}
  Future<int> updateSiteSwapDates({
    required List<SiteSwapDateUpdate> updates,
  }) async {
    final data = await _apiClient.put(
      '/api/sites/swap-dates',
      data: {
        'updates': updates.map((item) => item.toJson()).toList(),
      },
    );

    final map = (data as Map).cast<String, dynamic>();
    final value = map['updatedCount'];

    if (value is int) return value;
    if (value is num) return value.toInt();

    return int.tryParse(value?.toString() ?? '') ?? updates.length;
  }
}