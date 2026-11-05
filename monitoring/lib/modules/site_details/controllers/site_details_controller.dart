import 'dart:async';
import 'package:flutter/material.dart';

import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/sync/front_sync_policy.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/utils/status_utils.dart';
import '../../../data/models/device_item.dart';
import '../../../data/models/history_point.dart';
import '../../../data/models/site_description.dart';
import '../../../data/models/site_detail.dart';
import '../../../data/models/site_summary.dart';
import '../../../data/repositories/monitoring_repository.dart';

class SiteDetailsController extends GetxController {
  SiteDetailsController({
    required MonitoringRepository repository,
    String? brand,
    String? siteCode,
  })  : _repository = repository,
        _initialBrand = brand,
        _initialSiteCode = siteCode;

  final MonitoringRepository _repository;
  final String? _initialBrand;
  final String? _initialSiteCode;

  late String brand;
  late String siteCode;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final isHistoryLoading = false.obs;
  final errorMessage = RxnString();
  final lastRefresh = Rxn<DateTime>();
  final autoRefreshEnabled = true.obs;

  final detail = Rxn<SiteDetail>();
  final devices = <DeviceItem>[].obs;
  final siteDescription = Rxn<SiteDescription>();
  final isDescriptionLoading = false.obs;
  final isDescriptionSaving = false.obs;
  final descriptionErrorMessage = RxnString();

  final dayHistory = <HistoryPoint>[].obs;
  final monthHistory = <HistoryPoint>[].obs;
  final yearHistory = <HistoryPoint>[].obs;

  final selectedHistory = <HistoryPoint>[].obs;
  final activeHistoryMode = ''.obs;
  final activeHistoryBucket = ''.obs;
  final historyUiVersion = 0.obs;

  final chartMode = 'month'.obs;
  final chartCursor = DateTime.now().obs;

  final navigationSites = <SiteSummary>[].obs;
  final navSearch = ''.obs;

  Timer? _autoRefreshTimer;

  bool _fetchInFlight = false;
  DateTime? _lastSilentBaseRefreshAt;

  bool get _isSolis => brand.toUpperCase() == 'SOLIS';

  Duration get _detailAutoRefreshPeriod {
    return _isSolis ? FrontSyncPolicy.solisLive : FrontSyncPolicy.liveFast;
  }

  bool _historyDefaultApplied = false;
  int _siteRevision = 0;
  int _historyRequestVersion = 0;

  final Map<String, List<HistoryPoint>> _frontHistoryCache = {};

  @override
  void onInit() {
    super.onInit();

    final parameters = Get.parameters;
    final args = Get.arguments;

    brand = _initialBrand ??
        parameters['brand'] ??
        (args is Map ? args['brand']?.toString() : null) ??
        'HUAWEI';

    siteCode = _initialSiteCode ??
        parameters['siteCode'] ??
        (args is Map ? args['siteCode']?.toString() : null) ??
        '';

    brand = Uri.decodeComponent(brand).trim().isEmpty
        ? 'HUAWEI'
        : Uri.decodeComponent(brand).trim();

    siteCode = Uri.decodeComponent(siteCode).trim();

    if (siteCode.isEmpty) {
      Future.microtask(() => Get.offNamed(AppRoutes.sites));
      return;
    }

    chartMode.value = 'month';
    chartCursor.value = _normalizedCurrentCursor('month');
    _prepareVisibleHistory('month', _bucketFor(chartCursor.value, 'month'));

    load(force: true);

    debounce<String>(
      navSearch,
      (_) => _applyNavSearch(),
      time: const Duration(milliseconds: 250),
    );

    _startAutoRefresh();
  }

  @override
  void onClose() {
    _autoRefreshTimer?.cancel();
    super.onClose();
  }

  Future<void> openSite({
    required String brand,
    required String siteCode,
  }) async {
    final nextBrand = Uri.decodeComponent(brand).trim().isEmpty
        ? 'HUAWEI'
        : Uri.decodeComponent(brand).trim();

    final nextSiteCode = Uri.decodeComponent(siteCode).trim();

    if (this.brand == nextBrand &&
        this.siteCode == nextSiteCode &&
        detail.value != null) {
      return;
    }

    _siteRevision++;
    _historyDefaultApplied = false;
    _historyRequestVersion++;
    _frontHistoryCache.clear();

    this.brand = nextBrand;
    this.siteCode = nextSiteCode;

    errorMessage.value = null;
    detail.value = null;
    devices.clear();

    dayHistory.clear();
    monthHistory.clear();
    yearHistory.clear();
    selectedHistory.clear();
    siteDescription.value = null;
    descriptionErrorMessage.value = null;

    navigationSites.clear();

    chartMode.value = 'month';
    chartCursor.value = _normalizedCurrentCursor('month');
    _prepareVisibleHistory('month', _bucketFor(chartCursor.value, 'month'));

    if (this.siteCode.isEmpty) {
      Future.microtask(() => Get.offNamed(AppRoutes.sites));
      return;
    }

    _startAutoRefresh();
    await load(force: true);
  }

  Future<void> load({bool force = false}) async {
    try {
      isLoading.value = true;
      errorMessage.value = null;

      await _fetchBase(
        silent: false,
        force: force,
        applyCurrentHistoryFromFull: true,
      );

      if (!_historyDefaultApplied) {
        final revision = _siteRevision;
        final latest = await _latestCollectedCursor(chartMode.value);
        if (revision != _siteRevision) return;
        if (latest != null) chartCursor.value = latest;
        _historyDefaultApplied = true;
      }

      await _loadSelectedHistory(
        exposeErrors: false,
        useFrontCache: true,
      );

      await loadSiteDescription(
        force: force,
        showFeedback: false,
      );
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger le détail du site.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> loadSiteDescription({
    bool force = false,
    bool showFeedback = true,
  }) async {
    if (!force && siteDescription.value != null) return;

    try {
      isDescriptionLoading.value = true;
      descriptionErrorMessage.value = null;

      siteDescription.value = await _repository.getSiteDescription(
        brand: brand,
        siteCode: siteCode,
      );
    } on ApiException catch (e) {
      descriptionErrorMessage.value = e.message;

      if (showFeedback) {
        Get.snackbar(
          'Description site indisponible',
          e.message,
          snackPosition: SnackPosition.BOTTOM,
          margin: const EdgeInsets.all(16),
          borderRadius: 16,
        );
      }
    } catch (_) {
      const message = 'Impossible de charger la description du site.';
      descriptionErrorMessage.value = message;

      if (showFeedback) {
        Get.snackbar(
          'Description site indisponible',
          message,
          snackPosition: SnackPosition.BOTTOM,
          margin: const EdgeInsets.all(16),
          borderRadius: 16,
        );
      }
    } finally {
      isDescriptionLoading.value = false;
    }
  }

  Future<bool> saveSiteDescription({
    required String note,
    required String orientationDeg,
    List<String> retainedPhotoIds = const [],
    List<MultipartFilePayload> photosToAdd = const [],
  }) async {
    try {
      isDescriptionSaving.value = true;
      descriptionErrorMessage.value = null;

      siteDescription.value = await _repository.saveSiteDescription(
        brand: brand,
        siteCode: siteCode,
        note: note,
        orientationDeg: orientationDeg,
        retainedPhotoIds: retainedPhotoIds,
        photosToAdd: photosToAdd,
      );

      Get.snackbar(
        'Description enregistrée',
        'Les informations du site ont été mises à jour.',
        snackPosition: SnackPosition.BOTTOM,
        margin: const EdgeInsets.all(16),
        borderRadius: 16,
      );

      return true;
    } on ApiException catch (e) {
      descriptionErrorMessage.value = e.message;

      Get.snackbar(
        'Enregistrement impossible',
        e.message,
        snackPosition: SnackPosition.BOTTOM,
        margin: const EdgeInsets.all(16),
        borderRadius: 16,
      );
    } catch (_) {
      const message = 'Impossible d’enregistrer la description du site.';
      descriptionErrorMessage.value = message;

      Get.snackbar(
        'Enregistrement impossible',
        message,
        snackPosition: SnackPosition.BOTTOM,
        margin: const EdgeInsets.all(16),
        borderRadius: 16,
      );
    } finally {
      isDescriptionSaving.value = false;
    }

    return false;
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;

      await _fetchBase(
        silent: true,
        force: true,
        applyCurrentHistoryFromFull: false,
      );

      await _loadSelectedHistory(
        exposeErrors: true,
        useFrontCache: false,
      );
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Refresh impossible.';
    } finally {
      isRefreshing.value = false;
    }
  }

  Future<void> refreshLiveSilently() async {
    if (!autoRefreshEnabled.value) return;

    final now = DateTime.now();

    if (_lastSilentBaseRefreshAt != null &&
        now.difference(_lastSilentBaseRefreshAt!) < _detailAutoRefreshPeriod) {
      return;
    }

    _lastSilentBaseRefreshAt = now;

    try {
      await _fetchBase(
        silent: true,
        force: false,
        applyCurrentHistoryFromFull: false,
      );
      // Missing periods are now collected asynchronously by the backend.
      if (!isHistoryLoading.value) {
        await _loadSelectedHistory(exposeErrors: false, useFrontCache: false);
      }
    } catch (_) {
      // Refresh silencieux : on garde les dernières données visibles.
    }
  }

  Future<void> _fetchBase({
    bool silent = false,
    bool force = false,
    bool applyCurrentHistoryFromFull = false,
  }) async {
    if (_fetchInFlight && !force) return;

    final revision = _siteRevision;
    final currentBrand = brand;
    final currentSiteCode = siteCode;

    _fetchInFlight = true;

    try {
      if (!silent) errorMessage.value = null;

      final Future<dynamic> siteFuture = silent && !applyCurrentHistoryFromFull
          ? _repository.getSiteDetail(
              brand: currentBrand,
              siteCode: currentSiteCode,
            )
          : _repository.getSiteFull(
              brand: currentBrand,
              siteCode: currentSiteCode,
            );

      final responses = await Future.wait<dynamic>([
        siteFuture,
        _repository
            .getDevices(
              brand: currentBrand,
              siteCode: currentSiteCode,
            )
            .catchError(
              (_) => const SiteDevicesResponse(
                stationCode: '',
                devices: <DeviceItem>[],
              ),
            ),
        _repository.getSites().catchError((_) => <SiteSummary>[]),
      ]);

      if (revision != _siteRevision ||
          currentBrand != brand ||
          currentSiteCode != siteCode) {
        return;
      }

      final siteResponse = responses[0];
      final devicesResponse = responses[1] as SiteDevicesResponse;
      final allSites = responses[2] as List<SiteSummary>;

      if (siteResponse is SiteDetailFull) {
        detail.value = siteResponse.summary;

        if (applyCurrentHistoryFromFull) {
          _applyCurrentHistoryFromFull(siteResponse);
        }
      } else if (siteResponse is SiteDetail) {
        detail.value = siteResponse;
      }

      devices.assignAll(devicesResponse.devices);
      navigationSites.assignAll(_activeFirst(allSites));

      lastRefresh.value = DateTime.now();
    } finally {
      _fetchInFlight = false;
    }
  }

  void _cacheHistoryIfNotEmpty(
    String type,
    String bucket,
    List<HistoryPoint> points,
  ) {
    final key = _cacheKeyFor(type, bucket);

    if (points.isNotEmpty) {
      _frontHistoryCache[key] = List<HistoryPoint>.from(points);
    } else {
      _frontHistoryCache.remove(key);
    }
  }

  void _applyCurrentHistoryFromFull(SiteDetailFull siteFull) {
    final currentMonth = _bucketFor(
      _normalizedCurrentCursor('month'),
      'month',
    );

    final currentYear = _bucketFor(
      _normalizedCurrentCursor('year'),
      'year',
    );

    final currentDay = _bucketFor(
      _normalizedCurrentCursor('day'),
      'day',
    );

    _cacheHistoryIfNotEmpty('month', currentMonth, siteFull.monthHistory);
    _cacheHistoryIfNotEmpty('year', currentYear, siteFull.yearHistory);
    _cacheHistoryIfNotEmpty('day', currentDay, siteFull.dayHistory);

    final selectedMode = chartMode.value;
    final selectedBucket = _bucketFor(chartCursor.value, selectedMode);

    if (selectedMode == 'month' &&
        selectedBucket == currentMonth &&
        siteFull.monthHistory.isNotEmpty) {
      _setVisibleHistory('month', selectedBucket, siteFull.monthHistory);
    }

    if (selectedMode == 'year' &&
        selectedBucket == currentYear &&
        siteFull.yearHistory.isNotEmpty) {
      _setVisibleHistory('year', selectedBucket, siteFull.yearHistory);
    }

    if (selectedMode == 'day' &&
        selectedBucket == currentDay &&
        siteFull.dayHistory.isNotEmpty) {
      _setVisibleHistory('day', selectedBucket, siteFull.dayHistory);
    }
  }

  Future<DateTime?> _latestCollectedCursor(String mode) async {
    try {
      final buckets = await _repository.getLatestCollectedPeriods(brand: brand, siteCode: siteCode);
      final bucket = buckets[mode];
      if (bucket == null || bucket.isEmpty) return null;
      return DateTime.tryParse(mode == 'year' ? '$bucket-01-01' : mode == 'month' ? '$bucket-01' : bucket);
    } catch (_) {
      // Older backend or no collected data: keep the existing current-period fallback.
      return null;
    }
  }

  Future<void> loadChartMode(String mode) async {
    if (mode != 'day' && mode != 'month' && mode != 'year') return;
    if (isHistoryLoading.value) return;

    final oldMode = chartMode.value;

    if (oldMode == mode) {
      return;
    }

    final revision = _siteRevision;
    final latest = await _latestCollectedCursor(mode);
    if (revision != _siteRevision || chartMode.value != oldMode) return;
    final nextCursor = latest ?? _normalizedCurrentCursor(mode);
    final nextBucket = _bucketFor(nextCursor, mode);

    chartMode.value = mode;
    chartCursor.value = nextCursor;

    _prepareVisibleHistory(mode, nextBucket);

    await _loadSelectedHistory(
      exposeErrors: true,
      useFrontCache: true,
    );
  }

  Future<void> shiftChartPeriod(int direction) async {
    if (direction == 0) return;
    if (isHistoryLoading.value) return;

    final mode = chartMode.value;

    final next = _shiftedCursor(
      chartCursor.value,
      mode,
      direction,
    );

    final capped = _capFutureCursor(next, mode);

    if (_sameBucket(chartCursor.value, capped, mode)) {
      return;
    }

    chartCursor.value = capped;
    _prepareVisibleHistory(mode, _bucketFor(capped, mode));

    await _loadSelectedHistory(
      exposeErrors: true,
      useFrontCache: true,
    );
  }

  Future<void> selectChartPeriod(DateTime selected) async {
    if (isHistoryLoading.value) return;

    final mode = chartMode.value;
    final normalized = _normalizeCursorForMode(selected, mode);
    final capped = _capFutureCursor(normalized, mode);

    if (_sameBucket(chartCursor.value, capped, mode)) {
      return;
    }

    chartCursor.value = capped;
    _prepareVisibleHistory(mode, _bucketFor(capped, mode));

    await _loadSelectedHistory(
      exposeErrors: true,
      useFrontCache: true,
    );
  }

  Future<void> _loadSelectedHistory({
    bool exposeErrors = true,
    bool useFrontCache = true,
  }) async {
    final revision = _siteRevision;
    final requestVersion = ++_historyRequestVersion;

    final currentBrand = brand;
    final currentSiteCode = siteCode;
    final type = chartMode.value;
    final value = _bucketFor(chartCursor.value, type);
    final cacheKey = _cacheKeyForSnapshot(
      currentBrand,
      currentSiteCode,
      type,
      value,
    );

    final cached = _frontHistoryCache[cacheKey];

    if (useFrontCache && cached != null && cached.isNotEmpty) {
      _setVisibleHistory(type, value, cached);

      if (type == 'day') {
        await _ensureMonthCacheForDay(
          brandValue: currentBrand,
          siteCodeValue: currentSiteCode,
          selectedDay: chartCursor.value,
          revision: revision,
          requestVersion: requestVersion,
        );
      }

      return;
    }

    _prepareVisibleHistory(type, value);

    try {
      isHistoryLoading.value = true;

      if (exposeErrors) {
        errorMessage.value = null;
      }

      final history = await _repository.getHistory(
        brand: currentBrand,
        siteCode: currentSiteCode,
        type: type,
        value: value,
      );

      if (revision != _siteRevision ||
          requestVersion != _historyRequestVersion ||
          currentBrand != brand ||
          currentSiteCode != siteCode ||
          type != chartMode.value ||
          value != _bucketFor(chartCursor.value, chartMode.value)) {
        return;
      }

      if (history.isNotEmpty) {
        _frontHistoryCache[cacheKey] = List<HistoryPoint>.from(history);
      } else {
        _frontHistoryCache.remove(cacheKey);
      }

      _setVisibleHistory(type, value, history);

      if (type == 'day') {
        await _ensureMonthCacheForDay(
          brandValue: currentBrand,
          siteCodeValue: currentSiteCode,
          selectedDay: chartCursor.value,
          revision: revision,
          requestVersion: requestVersion,
        );
      }

      lastRefresh.value = DateTime.now();
    } on ApiException catch (e) {
      if (exposeErrors) {
        errorMessage.value = e.message;
      }

      final cached = _frontHistoryCache[cacheKey];

      if (cached != null && cached.isNotEmpty) {
        _setVisibleHistory(type, value, cached);
      }
    } catch (_) {
      if (exposeErrors) {
        errorMessage.value = 'Impossible de charger l’historique sélectionné.';
      }

      final cached = _frontHistoryCache[cacheKey];

      if (cached != null && cached.isNotEmpty) {
        _setVisibleHistory(type, value, cached);
      }
    } finally {
      if (requestVersion == _historyRequestVersion) {
        isHistoryLoading.value = false;
      }
    }
  }

  Future<void> _ensureMonthCacheForDay({
    required String brandValue,
    required String siteCodeValue,
    required DateTime selectedDay,
    required int revision,
    required int requestVersion,
  }) async {
    final monthBucket = Formatters.apiMonth(selectedDay);
    final monthCacheKey = _cacheKeyForSnapshot(
      brandValue,
      siteCodeValue,
      'month',
      monthBucket,
    );

    final existing = _frontHistoryCache[monthCacheKey];

    if (existing != null && existing.isNotEmpty) {
      return;
    }

    try {
      final history = await _repository.getHistory(
        brand: brandValue,
        siteCode: siteCodeValue,
        type: 'month',
        value: monthBucket,
      );

      if (revision != _siteRevision ||
          requestVersion != _historyRequestVersion ||
          brandValue != brand ||
          siteCodeValue != siteCode ||
          chartMode.value != 'day' ||
          !_sameBucket(chartCursor.value, selectedDay, 'day')) {
        return;
      }

      if (history.isNotEmpty) {
        _frontHistoryCache[monthCacheKey] = List<HistoryPoint>.from(history);
        historyUiVersion.value = historyUiVersion.value + 1;
      } else {
        _frontHistoryCache.remove(monthCacheKey);
      }
    } catch (_) {
      // On ne bloque pas la courbe Day si la source KPI mensuelle n'est pas disponible.
    }
  }

  void _prepareVisibleHistory(String type, String bucket) {
    activeHistoryMode.value = type;
    activeHistoryBucket.value = bucket;
    selectedHistory.clear();
    historyUiVersion.value = historyUiVersion.value + 1;
  }

  void _setVisibleHistory(
    String type,
    String bucket,
    List<HistoryPoint> values,
  ) {
    activeHistoryMode.value = type;
    activeHistoryBucket.value = bucket;
    selectedHistory.assignAll(values);

    switch (type) {
      case 'month':
        monthHistory.assignAll(values);
        break;
      case 'year':
        yearHistory.assignAll(values);
        break;
      case 'day':
      default:
        dayHistory.assignAll(values);
        break;
    }

    historyUiVersion.value = historyUiVersion.value + 1;
  }

  void _startAutoRefresh() {
    _autoRefreshTimer?.cancel();

    _autoRefreshTimer = Timer.periodic(
      _detailAutoRefreshPeriod,
      (_) => refreshLiveSilently(),
    );
  }

  void _applyNavSearch() {
    final query = navSearch.value.trim().toLowerCase();

    _repository.getSites().then((sites) {
      final filtered = query.isEmpty
          ? sites
          : sites
              .where(
                (site) =>
                    site.siteName.toLowerCase().contains(query) ||
                    site.siteCode.toLowerCase().contains(query) ||
                    site.brand.toLowerCase().contains(query),
              )
              .toList();

      navigationSites.assignAll(_activeFirst(filtered));
    }).catchError((_) {});
  }

  List<HistoryPoint> get activeHistory {
    if (!isHistorySynced) {
      return const <HistoryPoint>[];
    }

    return selectedHistory;
  }

  bool get isHistorySynced {
    return activeHistoryMode.value == chartMode.value &&
        activeHistoryBucket.value == activeBucket;
  }

  String get activeBucket {
    return _bucketFor(chartCursor.value, chartMode.value);
  }

  String get chartIdentity {
    final total = selectedPeriodEnergyKwh.toStringAsFixed(4);

    return [
      brand.toUpperCase(),
      siteCode,
      chartMode.value,
      activeBucket,
      activeHistoryMode.value,
      activeHistoryBucket.value,
      selectedHistory.length.toString(),
      historyUiVersion.value.toString(),
      total,
    ].join('__');
  }

  double get selectedPeriodEnergyKwh {
    if (!isHistorySynced) return 0;

    /*
     * DAY = courbe puissance kW.
     * Donc on ne somme jamais les points de la courbe Day.
     * Le KPI jour doit rester une énergie kWh.
     */
    if (chartMode.value == 'day') {
      return selectedDayEnergyKwh ?? 0;
    }

    return selectedHistory.fold<double>(
      0,
      (total, point) => total + _pointValue(point),
    );
  }

  double? get selectedDayEnergyKwh {
    final selected = DateTime(
      chartCursor.value.year,
      chartCursor.value.month,
      chartCursor.value.day,
    );

    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);

    /*
     * Aujourd'hui: la source la plus directe est le détail du site.
     */
    if (selected == today) {
      final value = detail.value?.dayEnergyKwh;

      if (value == null || value < 0) {
        return null;
      }

      return value;
    }

    /*
     * Ancien jour: la bonne source est le point journalier dans l'historique du mois.
     */
    final monthBucket = Formatters.apiMonth(selected);
    final monthCacheKey = _cacheKeyForSnapshot(
      brand,
      siteCode,
      'month',
      monthBucket,
    );

    final cachedMonth = _frontHistoryCache[monthCacheKey];

    final monthPoints = cachedMonth != null && cachedMonth.isNotEmpty
        ? cachedMonth
        : activeHistoryMode.value == 'month' &&
                activeHistoryBucket.value == monthBucket
            ? monthHistory
            : const <HistoryPoint>[];

    for (final point in monthPoints) {
      final date = point.dateTime;

      if (date == null) continue;

      if (date.year == selected.year &&
          date.month == selected.month &&
          date.day == selected.day) {
        return _pointValue(point);
      }
    }

    return null;
  }

  String get selectedPeriodProductionLabel {
    if (!isHistorySynced) return '—';

    if (chartMode.value == 'day') {
      final value = selectedDayEnergyKwh;

      if (value != null) {
        return Formatters.energy(value);
      }

      return isHistoryLoading.value ? 'Chargement...' : '—';
    }

    if (selectedHistory.isEmpty) {
      return isHistoryLoading.value ? 'Chargement...' : '—';
    }

    return Formatters.energy(selectedPeriodEnergyKwh);
  }

  String get selectedPeriodKpiTitle {
    switch (chartMode.value) {
      case 'month':
        return 'Production du mois sélectionné';
      case 'year':
        return 'Production de l’année sélectionnée';
      case 'day':
      default:
        return 'Production du jour sélectionné';
    }
  }

  String get activeHistoryTitle {
    switch (chartMode.value) {
      case 'month':
        return 'Production du mois ${Formatters.apiMonth(chartCursor.value)}';
      case 'year':
        return 'Production de l’année ${Formatters.apiYear(chartCursor.value)}';
      case 'day':
      default:
        return 'Production du ${Formatters.date(chartCursor.value)}';
    }
  }

  String get chartPeriodLabel {
    switch (chartMode.value) {
      case 'month':
        return Formatters.apiMonth(chartCursor.value);
      case 'year':
        return Formatters.apiYear(chartCursor.value);
      case 'day':
      default:
        return Formatters.date(chartCursor.value);
    }
  }

  DateTime get chartPickerInitialDate => chartCursor.value;

  DateTime get chartPickerFirstDate => DateTime(2000, 1, 1);

  DateTime get chartPickerLastDate => _normalizedCurrentCursor(chartMode.value);

  bool get isCurrentChartPeriod {
    final current = _normalizedCurrentCursor(chartMode.value);
    return _sameBucket(chartCursor.value, current, chartMode.value);
  }

  bool get shouldShowSelectedPeriodKpi {
    if (chartMode.value == 'day') {
      return true;
    }

    return !isCurrentChartPeriod;
  }

  bool get canMoveToNextPeriod {
    final current = _normalizedCurrentCursor(chartMode.value);
    final selected = chartCursor.value;

    switch (chartMode.value) {
      case 'month':
        return selected.year < current.year ||
            (selected.year == current.year && selected.month < current.month);

      case 'year':
        return selected.year < current.year;

      case 'day':
      default:
        final selectedDay = DateTime(
          selected.year,
          selected.month,
          selected.day,
        );

        final currentDay = DateTime(
          current.year,
          current.month,
          current.day,
        );

        return selectedDay.isBefore(currentDay);
    }
  }

  DateTime _normalizedCurrentCursor(String mode) {
    final now = DateTime.now();

    switch (mode) {
      case 'month':
        return DateTime(now.year, now.month, 1);
      case 'year':
        return DateTime(now.year, 1, 1);
      case 'day':
      default:
        return DateTime(now.year, now.month, now.day);
    }
  }

  DateTime _normalizeCursorForMode(DateTime cursor, String mode) {
    switch (mode) {
      case 'month':
        return DateTime(cursor.year, cursor.month, 1);

      case 'year':
        return DateTime(cursor.year, 1, 1);

      case 'day':
      default:
        return DateTime(cursor.year, cursor.month, cursor.day);
    }
  }

  DateTime _shiftedCursor(
    DateTime cursor,
    String mode,
    int direction,
  ) {
    switch (mode) {
      case 'month':
        return DateTime(cursor.year, cursor.month + direction, 1);
      case 'year':
        return DateTime(cursor.year + direction, 1, 1);
      case 'day':
      default:
        return DateTime(
          cursor.year,
          cursor.month,
          cursor.day,
        ).add(Duration(days: direction));
    }
  }

  DateTime _capFutureCursor(
    DateTime cursor,
    String mode,
  ) {
    final current = _normalizedCurrentCursor(mode);

    switch (mode) {
      case 'month':
        if (cursor.year > current.year ||
            (cursor.year == current.year && cursor.month > current.month)) {
          return current;
        }

        return DateTime(cursor.year, cursor.month, 1);

      case 'year':
        if (cursor.year > current.year) return current;
        return DateTime(cursor.year, 1, 1);

      case 'day':
      default:
        final day = DateTime(
          cursor.year,
          cursor.month,
          cursor.day,
        );

        final currentDay = DateTime(
          current.year,
          current.month,
          current.day,
        );

        return day.isAfter(currentDay) ? currentDay : day;
    }
  }

  bool _sameBucket(DateTime a, DateTime b, String mode) {
    return _bucketFor(a, mode) == _bucketFor(b, mode);
  }

  String _bucketFor(
    DateTime cursor,
    String mode,
  ) {
    switch (mode) {
      case 'month':
        return Formatters.apiMonth(cursor);
      case 'year':
        return Formatters.apiYear(cursor);
      case 'day':
      default:
        return Formatters.apiDay(cursor);
    }
  }

  String _cacheKeyFor(String type, String value) {
    return _cacheKeyForSnapshot(
      brand,
      siteCode,
      type,
      value,
    );
  }

  String _cacheKeyForSnapshot(
    String brandValue,
    String siteCodeValue,
    String type,
    String value,
  ) {
    return '${brandValue.toUpperCase()}::$siteCodeValue::$type::$value';
  }

  double _pointValue(HistoryPoint point) {
    final dynamic raw = point.value;

    if (raw is num) {
      return raw.toDouble();
    }

    return 0;
  }

  List<SiteSummary> _activeFirst(List<SiteSummary> sites) {
    final active = <SiteSummary>[];
    final others = <SiteSummary>[];

    for (final site in sites) {
      if (site.siteCode == siteCode &&
          site.brand.toUpperCase() == brand.toUpperCase()) {
        active.add(site);
      } else {
        others.add(site);
      }
    }

    others.sort((a, b) {
      final statusRank = _rank(
        StatusUtils.normalize(a.status),
      ).compareTo(
        _rank(StatusUtils.normalize(b.status)),
      );

      if (statusRank != 0) return statusRank;

      return a.siteName.compareTo(b.siteName);
    });

    return [...active, ...others];
  }

  int _rank(String status) {
    switch (status) {
      case 'healthy':
        return 0;
      case 'faulty':
        return 1;
      case 'disconnected':
        return 2;
      default:
        return 3;
    }
  }
}