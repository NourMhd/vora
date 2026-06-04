import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/utils/formatters.dart';
import '../../../data/models/daily_comparison_report.dart';
import '../../../data/repositories/monitoring_repository.dart';

class ComparatifController extends GetxController {
  ComparatifController({required MonitoringRepository repository}) : _repository = repository;

  static const List<String> _defaultSiteCodes = [
    'ARI_0222',
    'ARI_0112',
    'ARI_0061',
    'ARI_0088',
  ];

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final errorMessage = RxnString();
  final report = DailyComparisonReport.empty().obs;
  final availableSites = <DailyComparisonSelectableSite>[].obs;
  final selectedSiteCodes = <String>{}.obs;
  final selectedStartDate = Rxn<DateTime>();
  final selectedEndDate = Rxn<DateTime>();

  @override
  void onInit() {
    super.onInit();
    load();
  }

  Future<void> load() async {
    try {
      isLoading.value = true;
      errorMessage.value = null;

      availableSites.value = await _repository.getAriComparisonSites();
      _selectDefaultSitesIfNeeded();
      await _loadReport();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger le comparatif ARI.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;

      if (availableSites.isEmpty) {
        availableSites.value = await _repository.getAriComparisonSites();
        _selectDefaultSitesIfNeeded();
      }
      await _loadReport();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Refresh impossible.';
    } finally {
      isRefreshing.value = false;
    }
  }

  Future<void> applySelection() async {
    if (selectedSiteCodes.isEmpty) {
      errorMessage.value = 'Sélectionne au moins un site ARI.';
      return;
    }

    await refreshData();
  }

  void toggleSite(String siteCode, bool selected) {
    final next = Set<String>.from(selectedSiteCodes);
    if (selected) {
      next.add(siteCode);
    } else {
      next.remove(siteCode);
    }
    selectedSiteCodes.value = next;
  }

  bool isSelected(String siteCode) => selectedSiteCodes.contains(siteCode);

  void setStartDate(DateTime value) {
    final normalized = _dateOnly(value);
    selectedStartDate.value = normalized;

    final end = selectedEndDate.value;
    if (end != null && normalized.isAfter(end)) {
      selectedEndDate.value = normalized;
    }
  }

  void setEndDate(DateTime value) {
    final normalized = _dateOnly(value);
    selectedEndDate.value = normalized;

    final start = selectedStartDate.value;
    if (start != null && normalized.isBefore(start)) {
      selectedStartDate.value = normalized;
    }
  }

  void clearDateRange() {
    selectedStartDate.value = null;
    selectedEndDate.value = null;
  }

  DateTime get yesterday {
    final now = DateTime.now();
    return DateTime(now.year, now.month, now.day).subtract(const Duration(days: 1));
  }

  DateTime _dateOnly(DateTime value) => DateTime(value.year, value.month, value.day);

  Future<void> _loadReport() async {
    report.value = await _repository.getAriDailyComparison(
      siteCodes: selectedSiteCodes.toList()..sort(),
      startDate: selectedStartDate.value == null ? null : Formatters.apiDay(selectedStartDate.value!),
      endDate: selectedEndDate.value == null ? null : Formatters.apiDay(selectedEndDate.value!),
    );
  }

  void _selectDefaultSitesIfNeeded() {
    if (selectedSiteCodes.isNotEmpty) return;

    final availableCodes = availableSites.map((site) => site.siteCode).toSet();
    final defaultCodes = _defaultSiteCodes.where(availableCodes.contains).toSet();

    if (defaultCodes.isNotEmpty) {
      selectedSiteCodes.value = defaultCodes;
    } else if (availableSites.isNotEmpty) {
      selectedSiteCodes.value = {availableSites.first.siteCode};
    }
  }
}
