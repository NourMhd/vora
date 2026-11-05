import 'dart:async';

import 'package:intl/intl.dart';

import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/sync/front_sync_policy.dart';
import '../../../core/utils/status_utils.dart';
import '../../../core/utils/file_downloader.dart';
import '../../../core/utils/sites_excel_export.dart';
import '../../../data/models/site_summary.dart';
import '../../../data/repositories/monitoring_repository.dart';

class SitesController extends GetxController {
  SitesController({required MonitoringRepository repository})
    : _repository = repository;

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final isExporting = false.obs;
  final errorMessage = RxnString();
  final lastRefresh = Rxn<DateTime>();
  final autoRefreshEnabled = true.obs;
  final sites = <SiteSummary>[].obs;
  final brands = <String>[].obs;

  final searchQuery = ''.obs;
  final selectedBrand = 'ALL'.obs;
  final selectedStatus = 'ALL'.obs;

  final selectedZone = 'ALL'.obs;
  final selectedPlantType = 'ALL'.obs; // ALL / RT / GF
  final selectedGovernor = 'ALL'.obs;

  final sortBy = 'commissioningDate'.obs;
  final sortDirection = 'desc'.obs;

  final faultsOnly = false.obs;

  Timer? _autoRefreshTimer;
  bool _fetchInFlight = false;

  static const zoneGovernments = <String, List<String>>{
    'Zone A': ['ARI', 'BAR', 'MAN', 'JEN', 'ZAG', 'BIZ', 'TUN'],
    'Zone B': ['SFA', 'KAI', 'SOU'],
    'Zone C': ['MED', 'TAT', 'GAB'],
  };

  static List<String> get allGovernments {
    final values = zoneGovernments.values
        .expand((items) => items)
        .toSet()
        .toList();
    values.sort();
    return values;
  }

  List<String> get availableGovernments {
    if (selectedZone.value == 'ALL') {
      return ['ALL', ...allGovernments];
    }

    return [
      'ALL',
      ...(zoneGovernments[selectedZone.value] ?? const <String>[]),
    ];
  }

  @override
  void onInit() {
    super.onInit();

    final args = Get.arguments;
    if (args is Map && args['status'] != null) {
      selectedStatus.value = args['status'].toString();
    }

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
      await _fetchSites();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger la liste des sites.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;
      await _fetchSites();
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

    try {
      await _fetchSites(silent: true);
    } catch (_) {
      // Keep current list visible when a live refresh is temporarily unavailable.
    }
  }

  Future<void> _fetchSites({bool silent = false}) async {
    if (_fetchInFlight) return;

    _fetchInFlight = true;
    try {
      if (!silent) errorMessage.value = null;

      final responses = await Future.wait<dynamic>([
        _repository.getSites(),
        _repository.getBrands().catchError((_) => <String>[]),
      ]);

      final allSites = responses[0] as List<SiteSummary>;
      final availableBrands = responses[1] as List<String>;

      sites.assignAll(allSites);

      final brandList = availableBrands.isEmpty
          ? allSites.map((site) => site.brand).toSet().toList()
          : availableBrands.toSet().toList();

      brandList.sort();
      brands.assignAll(brandList);

      lastRefresh.value = DateTime.now();
    } finally {
      _fetchInFlight = false;
    }
  }

  void _startAutoRefresh() {
    _autoRefreshTimer?.cancel();
    _autoRefreshTimer = Timer.periodic(
      FrontSyncPolicy.liveFast,
      (_) => refreshLiveSilently(),
    );
  }

  List<SiteSummary> get filteredSites {
    final query = searchQuery.value.trim().toLowerCase();
    final status = selectedStatus.value;
    final brand = selectedBrand.value;
    final zone = selectedZone.value;
    final plantType = selectedPlantType.value;
    final governor = selectedGovernor.value;
    final onlyFaults = faultsOnly.value;

    final filtered = sites.where((site) {
      final normalizedStatus = StatusUtils.normalize(site.status);
      final siteGovernor = _governorForSite(site);
      final siteZone = _zoneForGovernor(siteGovernor);
      final sitePlantType = _plantTypeForSite(site);

      final matchesQuery =
          query.isEmpty ||
          site.siteName.toLowerCase().contains(query) ||
          site.siteCode.toLowerCase().contains(query) ||
          site.brand.toLowerCase().contains(query) ||
          (siteGovernor ?? '').toLowerCase().contains(query) ||
          (siteZone ?? '').toLowerCase().contains(query) ||
          sitePlantType.toLowerCase().contains(query);

      final matchesBrand =
          brand == 'ALL' || site.brand.toUpperCase() == brand.toUpperCase();
      final matchesStatus =
          status == 'ALL' || normalizedStatus == status.toLowerCase();
      final matchesFaults = !onlyFaults || normalizedStatus == 'faulty';

      final matchesZone = zone == 'ALL' || siteZone == zone;
      final matchesPlantType = plantType == 'ALL' || sitePlantType == plantType;
      final matchesGovernor = governor == 'ALL' || siteGovernor == governor;

      return matchesQuery &&
          matchesBrand &&
          matchesStatus &&
          matchesFaults &&
          matchesZone &&
          matchesPlantType &&
          matchesGovernor;
    }).toList();

    filtered.sort(_compareSites);
    return filtered;
  }

  Future<void> exportFilteredSites() async {
    if (isExporting.value) return;
    // Capture the displayed list, its order and filters before any async work.
    final snapshot = List<SiteSummary>.of(filteredSites);
    if (snapshot.isEmpty) {
      Get.snackbar(
        'Export impossible',
        'Aucun site ne correspond aux filtres.',
        snackPosition: SnackPosition.BOTTOM,
      );
      return;
    }
    final exportedAt = DateTime.now();
    final filters = <String, String>{
      'Recherche': searchQuery.value.trim().isEmpty
          ? 'Aucune'
          : searchQuery.value.trim(),
      'Marque': selectedBrand.value == 'ALL' ? 'Toutes' : selectedBrand.value,
      'État': selectedStatus.value == 'ALL'
          ? 'Tous'
          : SitesExcelExport.statusLabel(
              StatusUtils.normalize(selectedStatus.value),
            ),
      'Zone': selectedZone.value == 'ALL' ? 'Toutes' : selectedZone.value,
      'Gouvernorat': selectedGovernor.value == 'ALL'
          ? 'Tous'
          : selectedGovernor.value,
      'Type': selectedPlantType.value == 'ALL'
          ? 'Tous'
          : selectedPlantType.value,
      'Défauts uniquement': faultsOnly.value ? 'Oui' : 'Non',
      'Tri':
          {
            'commissioningDate': 'Date de mise en service',
            'dayEnergy': 'Énergie du jour',
            'monthEnergy': 'Énergie du mois',
            'totalEnergy': 'Énergie totale',
            'installedPower': 'Puissance installée',
          }[sortBy.value] ??
          sortBy.value,
      'Ordre': sortDirection.value == 'asc' ? 'Ascendant' : 'Descendant',
    };
    final rows = snapshot
        .map((site) {
          final governor = _governorForSite(site);
          return SitesExcelRow(
            name: site.siteName,
            code: site.siteCode,
            brand: site.brand,
            status: StatusUtils.normalize(site.status),
            zone: _zoneForGovernor(governor) ?? 'Non renseignée',
            governor: governor ?? 'Non renseigné',
          );
        })
        .toList(growable: false);
    final refreshedAt = lastRefresh.value;
    isExporting.value = true;
    try {
      // Let Flutter paint the progress indicator before creating the workbook.
      await Future<void>.delayed(Duration.zero);
      final bytes = SitesExcelExport.build(
        rows: rows,
        filters: filters,
        exportedAt: exportedAt,
        refreshedAt: refreshedAt,
      );
      await downloadBytes(
        bytes: bytes,
        filename:
            'vora_sites_${DateFormat('yyyyMMdd_HHmmss').format(exportedAt)}.xlsx',
        mimeType:
            'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      );
      Get.snackbar(
        'Export terminé',
        '${rows.length} sites exportés selon les filtres appliqués.',
        snackPosition: SnackPosition.BOTTOM,
      );
    } catch (_) {
      Get.snackbar(
        'Erreur export',
        'Impossible de télécharger le fichier Excel. Réessaie.',
        snackPosition: SnackPosition.BOTTOM,
      );
    } finally {
      isExporting.value = false;
    }
  }

  void setZoneFilter(String zone) {
    selectedZone.value = zone;

    if (!availableGovernments.contains(selectedGovernor.value)) {
      selectedGovernor.value = 'ALL';
    }
  }

  void setStatusFilter(String status) {
    selectedStatus.value = status;
  }

  void setSortBy(String value) {
    sortBy.value = value;
  }

  void setSortDirection(String value) {
    sortDirection.value = value;
  }

  void clearFilters() {
    searchQuery.value = '';
    selectedBrand.value = 'ALL';
    selectedStatus.value = 'ALL';

    selectedZone.value = 'ALL';
    selectedPlantType.value = 'ALL';
    selectedGovernor.value = 'ALL';

    sortBy.value = 'commissioningDate';
    sortDirection.value = 'desc';

    faultsOnly.value = false;
  }

  String _plantTypeForSite(SiteSummary site) {
    final kwp = site.installedPowerKw;

    if (kwp <= 0) {
      return 'UNKNOWN';
    }

    if (kwp >= 1 && kwp <= 5) {
      return 'RT';
    }

    return 'GF';
  }

  String? _governorForSite(SiteSummary site) {
    final text = '${site.siteCode} ${site.siteName}'.toUpperCase();

    for (final governor in allGovernments) {
      if (_containsGovernorCode(text, governor)) {
        return governor;
      }
    }

    return null;
  }

  bool _containsGovernorCode(String text, String governor) {
    final normalizedGovernor = governor.toUpperCase();

    final tokens = text
        .split(RegExp(r'[^A-Z0-9]+'))
        .where((token) => token.trim().isNotEmpty)
        .toList();

    return tokens.any(
          (token) =>
              token == normalizedGovernor ||
              token.startsWith(normalizedGovernor),
        ) ||
        text.contains(normalizedGovernor);
  }

  String? _zoneForGovernor(String? governor) {
    if (governor == null) return null;

    for (final entry in zoneGovernments.entries) {
      if (entry.value.contains(governor)) {
        return entry.key;
      }
    }

    return null;
  }

  int _compareSites(SiteSummary a, SiteSummary b) {
    final direction = sortDirection.value == 'asc' ? 1 : -1;

    if (sortBy.value == 'commissioningDate') {
      final left = _commissioningDate(a);
      final right = _commissioningDate(b);

      if (left == null && right == null) {
        return a.siteName.compareTo(b.siteName);
      }

      if (left == null) return 1;
      if (right == null) return -1;

      final comparison = left.compareTo(right);
      if (comparison != 0) return direction * comparison;

      return a.siteName.compareTo(b.siteName);
    }

    final left = _sortValue(a);
    final right = _sortValue(b);

    final comparison = left.compareTo(right);
    if (comparison != 0) return direction * comparison;

    return a.siteName.compareTo(b.siteName);
  }

  double _sortValue(SiteSummary site) {
    switch (sortBy.value) {
      case 'monthEnergy':
        return site.monthEnergyKwh;
      case 'totalEnergy':
        return site.totalEnergyKwh;
      case 'installedPower':
        return site.installedPowerKw;
      case 'dayEnergy':
      default:
        return site.dayEnergyKwh;
    }
  }

  DateTime? _commissioningDate(SiteSummary site) {
    final raw = site.gridConnectionDate?.trim();

    if (raw == null || raw.isEmpty || raw == '—') {
      return null;
    }

    final epoch = int.tryParse(raw);
    if (epoch != null && RegExp(r'^\d{10,13}$').hasMatch(raw)) {
      final millis = epoch < 1000000000000 ? epoch * 1000 : epoch;
      return DateTime.fromMillisecondsSinceEpoch(millis);
    }

    final iso = DateTime.tryParse(raw);
    if (iso != null) return iso;

    final normalized = raw.replaceAll('/', '-');
    final parts = normalized
        .split('-')
        .map((part) => int.tryParse(part))
        .toList();

    if (parts.length != 3 || parts.any((part) => part == null)) {
      return null;
    }

    final first = parts[0]!;
    final second = parts[1]!;
    final third = parts[2]!;

    if (first > 31) {
      return DateTime(first, second, third);
    }

    return DateTime(third, second, first);
  }
}
