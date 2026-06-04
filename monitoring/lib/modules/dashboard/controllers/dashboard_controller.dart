import 'dart:async';

import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/sync/front_sync_policy.dart';
import '../../../data/models/dashboard_summary.dart';
import '../../../data/models/site_summary.dart';
import '../../../data/models/system_status.dart';
import '../../../data/repositories/monitoring_repository.dart';

class DashboardController extends GetxController {
  DashboardController({required MonitoringRepository repository})
      : _repository = repository;

  final MonitoringRepository _repository;

  static const int pageSize = 10;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final errorMessage = RxnString();
  final lastRefresh = Rxn<DateTime>();
  final autoRefreshEnabled = true.obs;

  final summary = DashboardSummary.empty().obs;
  final sites = <SiteSummary>[].obs;
  final brands = <String>[].obs;
  final health = Rxn<HealthStatus>();
  final syncStatuses = <SyncStatus>[].obs;

  final selectedStatus = RxnString();
  final siteSortMetric = 'day'.obs;
  final siteSortDirection = 'desc'.obs;
  final sitePage = 0.obs;

  Timer? _autoRefreshTimer;
  bool _fetchInFlight = false;

  @override
  void onInit() {
    super.onInit();
    load();
    _startAutoRefresh();
  }

  @override
  void onClose() {
    _autoRefreshTimer?.cancel();
    super.onClose();
  }

  Future<void> load() async {
    try {
      isLoading.value = true;
      errorMessage.value = null;
      await _fetchAll();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger le dashboard VORA.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;
      await _fetchAll();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Refresh impossible.';
    } finally {
      isRefreshing.value = false;
    }
  }

  Future<void> _fetchAll({bool silent = false}) async {
    if (_fetchInFlight) return;

    _fetchInFlight = true;

    try {
      if (!silent) {
        errorMessage.value = null;
      }

      final responses = await Future.wait<dynamic>([
        _repository.getDashboardSummary(),
        _repository.getSites(),
        _repository.getBrands().catchError((_) => <String>[]),
        _safeHealth(),
        _repository.getSyncStatus().catchError((_) => <SyncStatus>[]),
      ]);

      summary.value = responses[0] as DashboardSummary;
      sites.assignAll(responses[1] as List<SiteSummary>);

      final uniqueBrands = (responses[2] as List<String>).toSet().toList()
        ..sort();
      brands.assignAll(uniqueBrands);

      health.value = responses[3] as HealthStatus?;
      syncStatuses.assignAll(responses[4] as List<SyncStatus>);

      _clampSitePage();
      lastRefresh.value = DateTime.now();
    } finally {
      _fetchInFlight = false;
    }
  }

  Future<void> refreshLiveSilently() async {
    if (!autoRefreshEnabled.value) return;

    try {
      await _fetchAll(silent: true);
    } catch (_) {
      // Refresh silencieux : on garde l'écran stable.
    }
  }

  void _startAutoRefresh() {
    _autoRefreshTimer?.cancel();

    _autoRefreshTimer = Timer.periodic(
      FrontSyncPolicy.liveFast,
      (_) => refreshLiveSilently(),
    );
  }

  Future<HealthStatus?> _safeHealth() async {
    try {
      return await _repository.getHealth();
    } catch (_) {
      return null;
    }
  }

  List<SiteSummary> get filteredSites {
    final status = selectedStatus.value;

    if (status == null || status.isBlank == true) {
      return sites;
    }

    return sites
        .where(
          (site) => site.status.toLowerCase() == status.toLowerCase(),
        )
        .toList();
  }

  List<SiteSummary> get sortedSites {
    final sorted = filteredSites.toList();

    sorted.sort((a, b) {
      final comparison = _siteSortValue(a).compareTo(_siteSortValue(b));

      if (comparison != 0) {
        return siteSortDirection.value == 'asc' ? comparison : -comparison;
      }

      return a.siteName.compareTo(b.siteName);
    });

    return sorted;
  }

  List<SiteSummary> get pagedSites {
    final sorted = sortedSites;

    if (sorted.isEmpty) {
      return const [];
    }

    final safePage = _safePageIndex(sorted.length);
    final start = safePage * pageSize;
    final end = start + pageSize > sorted.length ? sorted.length : start + pageSize;

    return sorted.sublist(start, end);
  }

  int get totalFilteredSites => filteredSites.length;

  int get totalSitePages {
    final count = totalFilteredSites;

    if (count == 0) {
      return 1;
    }

    return ((count - 1) ~/ pageSize) + 1;
  }

  int get pageStartIndex {
    final count = totalFilteredSites;

    if (count == 0) {
      return 0;
    }

    final safePage = _safePageIndex(count);
    return safePage * pageSize + 1;
  }

  int get pageEndIndex {
    final count = totalFilteredSites;

    if (count == 0) {
      return 0;
    }

    final safePage = _safePageIndex(count);
    final end = (safePage + 1) * pageSize;

    return end > count ? count : end;
  }

  SiteSummary? get topProductiveSite {
    final candidates = sites.toList()
      ..sort((a, b) {
        final comparison = b.dayEnergyKwh.compareTo(a.dayEnergyKwh);

        if (comparison != 0) {
          return comparison;
        }

        return a.siteName.compareTo(b.siteName);
      });

    return candidates.isEmpty ? null : candidates.first;
  }

  SiteSummary? get lowestDayEnergySite {
    final candidates = sites.toList()
      ..sort((a, b) {
        final comparison = a.dayEnergyKwh.compareTo(b.dayEnergyKwh);

        if (comparison != 0) {
          return comparison;
        }

        return a.siteName.compareTo(b.siteName);
      });

    return candidates.isEmpty ? null : candidates.first;
  }

  Map<String, int> get statusCounts => {
        'healthy': summary.value.healthySites,
        'faulty': summary.value.faultySites,
        'disconnected': summary.value.disconnectedSites,
      };

  Map<String, int> get brandCounts {
    final values = <String, int>{};

    for (final site in sites) {
      values.update(
        site.brand,
        (count) => count + 1,
        ifAbsent: () => 1,
      );
    }

    return values;
  }

  SyncStatus? get latestProviderSync {
    if (syncStatuses.isEmpty) {
      return null;
    }

    final sorted = syncStatuses.toList()
      ..sort((a, b) {
        final left = a.finishedAt ??
            a.startedAt ??
            DateTime.fromMillisecondsSinceEpoch(0);

        final right = b.finishedAt ??
            b.startedAt ??
            DateTime.fromMillisecondsSinceEpoch(0);

        return right.compareTo(left);
      });

    return sorted.first;
  }

  void setSiteSortMetric(String metric) {
    siteSortMetric.value = metric;
    sitePage.value = 0;
  }

  void setSiteSortDirection(String direction) {
    siteSortDirection.value = direction;
    sitePage.value = 0;
  }

  void previousSitePage() {
    if (sitePage.value > 0) {
      sitePage.value -= 1;
    }
  }

  void nextSitePage() {
    if (sitePage.value < totalSitePages - 1) {
      sitePage.value += 1;
    }
  }

  void selectStatus(String? status) {
    selectedStatus.value = selectedStatus.value == status ? null : status;
    sitePage.value = 0;
  }

  double _siteSortValue(SiteSummary site) {
    switch (siteSortMetric.value) {
      case 'month':
        return site.monthEnergyKwh;
      case 'year':
        return site.yearEnergyKwh;
      case 'total':
        return site.totalEnergyKwh;
      case 'day':
      default:
        return site.dayEnergyKwh;
    }
  }

  int _safePageIndex(int itemCount) {
    if (itemCount <= 0) {
      return 0;
    }

    final lastPage = ((itemCount - 1) ~/ pageSize);

    if (sitePage.value < 0) {
      return 0;
    }

    if (sitePage.value > lastPage) {
      return lastPage;
    }

    return sitePage.value;
  }

  void _clampSitePage() {
    final count = totalFilteredSites;

    if (count <= 0) {
      sitePage.value = 0;
      return;
    }

    final lastPage = ((count - 1) ~/ pageSize);

    if (sitePage.value > lastPage) {
      sitePage.value = lastPage;
    }

    if (sitePage.value < 0) {
      sitePage.value = 0;
    }
  }
}