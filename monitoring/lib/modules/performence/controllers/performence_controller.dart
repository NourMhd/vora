import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/utils/formatters.dart';
import '../../../data/models/performance_report.dart';
import '../../../data/repositories/monitoring_repository.dart';

class PerformanceController extends GetxController {
  PerformanceController({required MonitoringRepository repository}) : _repository = repository;

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final errorMessage = RxnString();
  final rows = <PerformanceReportRow>[].obs;

  final selectedPeriod = 'MONTH'.obs; // TEN_DAYS / MONTH / YEAR / CUSTOM
  final selectedYear = DateTime.now().year.obs;
  final selectedMonth = DateTime.now().month.obs;
  final selectedDecade = _decadeForDay(DateTime.now().day).obs;
  final selectedStartDate = DateTime(DateTime.now().year, DateTime.now().month, 1).obs;
  final selectedEndDate = DateTime(DateTime.now().year, DateTime.now().month + 1, 0).obs;

  final searchQuery = ''.obs;
  final selectedResult = 'ALL'.obs; // ALL / BONUS / MALUS / N/A
  final selectedStatus = 'HEALTHY'.obs; // HEALTHY / FAULTY / ALL
  final selectedZone = 'ALL'.obs; // ALL / Zone A / Zone B / Zone C / UNKNOWN
  final selectedGovernorate = 'ALL'.obs; // ALL / ARI / SFA / ... / UNKNOWN
  final selectedPlantType = 'ALL'.obs; // ALL / RT / GF / UNKNOWN

  @override
  void onInit() {
    super.onInit();
    _applyShortcutRange(refresh: false);
    load();
  }

  List<int> get availableYears {
    final current = DateTime.now().year;
    return List<int>.generate(8, (index) => current - 5 + index);
  }

  String get selectedRangeLabel =>
      '${Formatters.date(selectedStartDate.value)} → ${Formatters.date(selectedEndDate.value)}';

  Map<String, String> get statusFilterItems => const <String, String>{
        'HEALTHY': 'Healthy',
        'FAULTY': 'Faulty',
        'ALL': 'Tous',
      };

  Map<String, String> get zoneFilterItems {
    final values = rows.map((row) => _normalizeZone(row.zone)).toSet();

    final ordered = <String>['Zone A', 'Zone B', 'Zone C', 'UNKNOWN'];
    final result = <String, String>{
      'ALL': 'Toutes',
    };

    for (final zone in ordered) {
      if (values.contains(zone)) {
        result[zone] = zone == 'UNKNOWN' ? 'Unknown' : zone;
      }
    }

    final extraZones = values.where((zone) => !ordered.contains(zone)).toList()..sort();
    for (final zone in extraZones) {
      result[zone] = zone;
    }

    return result;
  }

  Map<String, String> get governorateFilterItems {
    final values = rows.map((row) => _normalizeGovernorate(row.governorate)).toSet();
    final ordered = values.toList()..sort();

    final result = <String, String>{
      'ALL': 'Tous',
    };

    for (final governorate in ordered) {
      result[governorate] = governorate == 'UNKNOWN' ? 'Unknown' : governorate;
    }

    final current = selectedGovernorate.value;
    if (current != 'ALL' && !result.containsKey(current)) {
      result[current] = current;
    }

    return result;
  }

  Map<String, String> get plantTypeFilterItems {
    final values = rows.map((row) => _normalizePlantType(row.plantType)).toSet();

    final ordered = <String>['RT', 'GF', 'UNKNOWN'];
    final result = <String, String>{
      'ALL': 'Tous',
    };

    for (final type in ordered) {
      if (values.contains(type)) {
        result[type] = type == 'UNKNOWN' ? 'Unknown' : type;
      }
    }

    final extraTypes = values.where((type) => !ordered.contains(type)).toList()..sort();
    for (final type in extraTypes) {
      result[type] = type;
    }

    return result;
  }

  List<PerformanceReportRow> get filteredRows {
    final query = searchQuery.value.trim().toLowerCase();
    final result = selectedResult.value;
    final status = selectedStatus.value;
    final zone = selectedZone.value;
    final governorate = selectedGovernorate.value;
    final plantType = selectedPlantType.value;

    final values = rows.where((row) {
      final normalizedZone = _normalizeZone(row.zone);
      final normalizedGovernorate = _normalizeGovernorate(row.governorate);
      final normalizedPlantType = _normalizePlantType(row.plantType);
      final normalizedStatus = _normalizeStatus(row.siteStatus);

      final matchesQuery = query.isEmpty ||
          row.siteName.toLowerCase().contains(query) ||
          row.siteCode.toLowerCase().contains(query) ||
          row.brand.toLowerCase().contains(query) ||
          row.plantType.toLowerCase().contains(query) ||
          normalizedZone.toLowerCase().contains(query) ||
          (row.governorate ?? '').toLowerCase().contains(query);

      final matchesResult = result == 'ALL' || row.bonusMalus.toUpperCase() == result;
      final matchesStatus = status == 'ALL'
          ? normalizedStatus == 'HEALTHY' || normalizedStatus == 'FAULTY'
          : normalizedStatus == status;
      final matchesZone = zone == 'ALL' || normalizedZone == zone;
      final matchesGovernorate = governorate == 'ALL' || normalizedGovernorate == governorate;
      final matchesPlantType = plantType == 'ALL' || normalizedPlantType == plantType;

      return matchesQuery &&
          matchesResult &&
          matchesStatus &&
          matchesZone &&
          matchesGovernorate &&
          matchesPlantType;
    }).toList();

    values.sort((a, b) {
      if (a.hasMissingData != b.hasMissingData) {
        return a.hasMissingData ? 1 : -1;
      }

      final left = a.differencePercent ?? -999999;
      final right = b.differencePercent ?? -999999;
      final comparison = right.compareTo(left);

      if (comparison != 0) return comparison;
      return a.siteName.compareTo(b.siteName);
    });

    return values;
  }

  Iterable<PerformanceReportRow> get comparableRows => rows.where((row) {
        final status = _normalizeStatus(row.siteStatus);
        return status == 'HEALTHY' || status == 'FAULTY';
      });

  int get bonusCount => comparableRows.where((row) => row.isBonus).length;
  int get malusCount => comparableRows.where((row) => row.isMalus).length;
  int get missingCount => comparableRows.where((row) => row.hasMissingData).length;

  int get filteredBonusCount => filteredRows.where((row) => row.isBonus).length;
  int get filteredMalusCount => filteredRows.where((row) => row.isMalus).length;
  int get filteredMissingCount => filteredRows.where((row) => row.hasMissingData).length;

  double get totalExpectedKwh => comparableRows
      .map((row) => row.expectedKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get totalActualKwh => comparableRows
      .map((row) => row.actualKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get totalEstimatedMissingKwh => comparableRows
      .map((row) => row.estimatedMissingKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get totalDifferenceKwh => totalActualKwh - totalExpectedKwh;

  double get filteredTotalExpectedKwh => filteredRows
      .map((row) => row.expectedKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get filteredTotalActualKwh => filteredRows
      .map((row) => row.actualKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get filteredTotalEstimatedMissingKwh => filteredRows
      .map((row) => row.estimatedMissingKwh)
      .whereType<double>()
      .fold<double>(0, (sum, value) => sum + value);

  double get filteredTotalDifferenceKwh => filteredTotalActualKwh - filteredTotalExpectedKwh;

  Future<void> load() async {
    try {
      isLoading.value = true;
      errorMessage.value = null;
      await _fetchReport();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger le suivi production.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;
      await _fetchReport();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Refresh impossible.';
    } finally {
      isRefreshing.value = false;
    }
  }

  Future<void> _fetchReport() async {
    rows.assignAll(
      await _repository.getPerformanceReport(
        period: selectedPeriod.value,
        year: selectedYear.value,
        month: selectedMonth.value,
        decade: selectedDecade.value,
        startDate: Formatters.apiDay(selectedStartDate.value),
        endDate: Formatters.apiDay(selectedEndDate.value),
      ),
    );
  }

  void setPeriod(String value) {
    selectedPeriod.value = value;
    _applyShortcutRange(refresh: false);
    refreshData();
  }

  void setYear(int value) {
    selectedYear.value = value;
    _applyShortcutRange(refresh: false);
    refreshData();
  }

  void setMonth(int value) {
    selectedMonth.value = value;
    _applyShortcutRange(refresh: false);
    refreshData();
  }

  void setDecade(int value) {
    selectedDecade.value = value;
    _applyShortcutRange(refresh: false);
    refreshData();
  }

  void setStartDate(DateTime value) {
    final cleanValue = _dateOnly(value);
    selectedPeriod.value = 'CUSTOM';
    selectedStartDate.value = cleanValue;

    if (selectedEndDate.value.isBefore(cleanValue)) {
      selectedEndDate.value = cleanValue;
    }

    refreshData();
  }

  void setEndDate(DateTime value) {
    final cleanValue = _dateOnly(value);
    selectedPeriod.value = 'CUSTOM';
    selectedEndDate.value = cleanValue;

    if (selectedStartDate.value.isAfter(cleanValue)) {
      selectedStartDate.value = cleanValue;
    }

    refreshData();
  }

  void setZone(String value) {
    selectedZone.value = value;
  }

  void setGovernorate(String value) {
    selectedGovernorate.value = value;
  }

  void setPlantType(String value) {
    selectedPlantType.value = value;
  }

  void setStatus(String value) {
    selectedStatus.value = value;
  }

  void clearFilters() {
    searchQuery.value = '';
    selectedResult.value = 'ALL';
    selectedStatus.value = 'HEALTHY';
    selectedZone.value = 'ALL';
    selectedGovernorate.value = 'ALL';
    selectedPlantType.value = 'ALL';
  }

  void _applyShortcutRange({required bool refresh}) {
    final period = selectedPeriod.value;

    if (period == 'CUSTOM') {
      if (refresh) refreshData();
      return;
    }

    if (period == 'YEAR') {
      selectedStartDate.value = DateTime(selectedYear.value, 1, 1);
      selectedEndDate.value = DateTime(selectedYear.value, 12, 31);
    } else if (period == 'TEN_DAYS') {
      final monthEnd = DateTime(selectedYear.value, selectedMonth.value + 1, 0).day;
      final startDay = selectedDecade.value == 1
          ? 1
          : selectedDecade.value == 2
              ? 11
              : 21;
      final endDay = selectedDecade.value == 1
          ? 10
          : selectedDecade.value == 2
              ? 20
              : monthEnd;
      selectedStartDate.value = DateTime(selectedYear.value, selectedMonth.value, startDay);
      selectedEndDate.value = DateTime(selectedYear.value, selectedMonth.value, endDay);
    } else {
      selectedStartDate.value = DateTime(selectedYear.value, selectedMonth.value, 1);
      selectedEndDate.value = DateTime(selectedYear.value, selectedMonth.value + 1, 0);
    }

    if (refresh) refreshData();
  }

  static DateTime _dateOnly(DateTime value) => DateTime(value.year, value.month, value.day);

  static String _normalizeZone(String? value) {
    final cleaned = value?.trim();
    if (cleaned == null || cleaned.isEmpty) {
      return 'UNKNOWN';
    }
    return cleaned;
  }

  static String _normalizeGovernorate(String? value) {
    final cleaned = value?.trim().toUpperCase();
    if (cleaned == null || cleaned.isEmpty) {
      return 'UNKNOWN';
    }
    return cleaned;
  }

  static String _normalizeStatus(String value) {
    final cleaned = value.trim().toUpperCase();
    if (cleaned == 'ONLINE') return 'HEALTHY';
    if (cleaned == 'OFFLINE' || cleaned == 'DISCONNECTED') return 'DISCONNECTED';
    if (cleaned == 'ALARM' || cleaned == 'FAULT' || cleaned == 'FAULTY') return 'FAULTY';
    if (cleaned == 'HEALTHY') return 'HEALTHY';
    return 'UNKNOWN';
  }

  static String _normalizePlantType(String value) {
    final cleaned = value.trim().toUpperCase();
    if (cleaned.isEmpty) {
      return 'UNKNOWN';
    }
    return cleaned;
  }

  static int _decadeForDay(int day) {
    if (day <= 10) return 1;
    if (day <= 20) return 2;
    return 3;
  }
}
