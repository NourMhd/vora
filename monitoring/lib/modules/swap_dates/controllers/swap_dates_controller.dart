import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/network/api_exception.dart';
import '../../../data/models/site_swap_date.dart';
import '../../../data/repositories/monitoring_repository.dart';

class SwapDatesController extends GetxController {
  SwapDatesController({required MonitoringRepository repository}) : _repository = repository;

  static const int pageSize = 50;

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isSaving = false.obs;
  final errorMessage = RxnString();
  final lastRefresh = Rxn<DateTime>();

  final items = <SiteSwapDate>[].obs;
  final currentPage = 0.obs;
  final totalPages = 0.obs;
  final totalElements = 0.obs;

  final hasChanges = false.obs;
  final changedCount = 0.obs;
  final fieldErrors = <String, String>{}.obs;

  final Map<String, String?> _originalDates = {};
  final Map<String, TextEditingController> _textControllers = {};
  final searchQuery = ''.obs;
  final swapFilter = 'all'.obs;

@override
void onInit() {
  super.onInit();

  debounce<String>(
    searchQuery,
    (_) => loadPage(0),
    time: const Duration(milliseconds: 450),
  );

  debounce<String>(
    swapFilter,
    (_) => loadPage(0),
    time: const Duration(milliseconds: 150),
  );

  loadPage(0);
}
  @override
  void onClose() {
    for (final controller in _textControllers.values) {
      controller.dispose();
    }
    _textControllers.clear();
    super.onClose();
  }

  TextEditingController textControllerFor(SiteSwapDate item) {
    return _textControllers[item.key] ??= _createTextController(item.key, item.swapDate);
  }

  String? fieldErrorFor(SiteSwapDate item) => fieldErrors[item.key];

  bool isModified(SiteSwapDate item) {
    final current = _normalizeDateText(textControllerFor(item).text);
    final original = _normalizeDateText(_originalDates[item.key]);
    return current != original;
  }

Future<void> loadPage(int page) async {
  if (hasChanges.value) {
    Get.snackbar(
      'Modifications non enregistrées',
      'Applique les modifications ou annule-les avant de changer de page.',
      snackPosition: SnackPosition.BOTTOM,
    );
    return;
  }

  try {
    isLoading.value = true;
    errorMessage.value = null;
final response = await _repository.getSiteSwapDates(
  page: page,
  size: pageSize,
  search: searchQuery.value,
  swapFilter: swapFilter.value,
);

    // Important :
    // On vide d'abord la liste affichée pour que les anciens TextField
    // soient retirés de l'UI avant de changer les contrôleurs.
    items.clear();

    currentPage.value = response.page;
    totalPages.value = response.totalPages;
    totalElements.value = response.totalElements;
    lastRefresh.value = DateTime.now();

    _originalDates
      ..clear()
      ..addEntries(
        response.items.map(
          (item) => MapEntry(
            item.key,
            _normalizeDateText(item.swapDate),
          ),
        ),
      );

    for (final item in response.items) {
      final textController = textControllerFor(item);
      textController.text = item.swapDate ?? '';
    }

    fieldErrors.clear();

    // On ajoute les nouveaux items seulement après avoir préparé
    // les TextEditingController.
    items.assignAll(response.items);

    _recomputeChanges();
  } on ApiException catch (e) {
    errorMessage.value = e.message;
  } catch (_) {
    errorMessage.value = 'Impossible de charger les dates de swap.';
  } finally {
    isLoading.value = false;
  }
}
void updateSwapFilter(String value) {
  if (swapFilter.value == value) return;
  swapFilter.value = value;
}
void updateSearch(String value) {
  searchQuery.value = value.trim();
}

void clearSearch() {
  if (searchQuery.value.isEmpty) return;
  searchQuery.value = '';
}
  Future<void> refreshCurrentPage() async {
    if (hasChanges.value) {
      Get.snackbar(
        'Modifications non enregistrées',
        'Applique ou annule tes modifications avant de rafraîchir.',
        snackPosition: SnackPosition.BOTTOM,
      );
      return;
    }

    await loadPage(currentPage.value);
  }

  Future<void> nextPage() async {
    if (currentPage.value + 1 >= totalPages.value) return;
    await loadPage(currentPage.value + 1);
  }

  Future<void> previousPage() async {
    if (currentPage.value <= 0) return;
    await loadPage(currentPage.value - 1);
  }

  void cancelChanges() {
    for (final item in items) {
      final original = _originalDates[item.key] ?? '';
      textControllerFor(item).text = original;
    }

    fieldErrors.clear();
    errorMessage.value = null;
    _recomputeChanges();
  }

  Future<void> saveChanges() async {
    if (isSaving.value) return;

    if (!_validateAllFields()) {
      Get.snackbar(
        'Format invalide',
        'Corrige les dates au format dd/mm/aaaa avant d’enregistrer.',
        snackPosition: SnackPosition.BOTTOM,
      );
      return;
    }

    final updates = _changedUpdates();
    if (updates.isEmpty) {
      _recomputeChanges();
      return;
    }

    try {
      isSaving.value = true;
      errorMessage.value = null;

      final updatedCount = await _repository.updateSiteSwapDates(updates: updates);

      for (final update in updates) {
        _originalDates['${update.brand}::${update.siteCode}'] = _normalizeDateText(update.swapDate);
      }

      _recomputeChanges();

      Get.snackbar(
        'Dates enregistrées',
        '$updatedCount modification(s) appliquée(s) avec succès.',
        snackPosition: SnackPosition.BOTTOM,
      );
    } on ApiException catch (e) {
      errorMessage.value = e.message;
      Get.snackbar('Erreur', e.message, snackPosition: SnackPosition.BOTTOM);
    } catch (_) {
      errorMessage.value = 'Impossible d’enregistrer les dates de swap.';
      Get.snackbar('Erreur', errorMessage.value!, snackPosition: SnackPosition.BOTTOM);
    } finally {
      isSaving.value = false;
    }
  }

  Future<void> pickDate(BuildContext context, SiteSwapDate item) async {
    final raw = textControllerFor(item).text;
    final initialDate = _parseSwapDate(raw) ?? DateTime.now();

    final picked = await showDatePicker(
      context: context,
      initialDate: initialDate,
      firstDate: DateTime(2000, 1, 1),
      lastDate: DateTime(2100, 12, 31),
      helpText: 'Choisir une date de swap',
      cancelText: 'Annuler',
      confirmText: 'Valider',
      builder: (context, child) {
        return Theme(
          data: Theme.of(context).copyWith(
            colorScheme: Theme.of(context).colorScheme.copyWith(
                  primary: AppColors.deepTeal,
                  secondary: AppColors.deepTeal,
                ),
          ),
          child: child ?? const SizedBox.shrink(),
        );
      },
    );

    if (picked == null) return;
    textControllerFor(item).text = _formatSwapDate(picked);
  }

  void clearDate(SiteSwapDate item) {
    textControllerFor(item).clear();
  }

  TextEditingController _createTextController(String key, String? value) {
    final controller = TextEditingController(text: value ?? '');
    controller.addListener(() {
      _validateField(key, controller.text);
      _recomputeChanges();
    });
    return controller;
  }

  

  bool _validateAllFields() {
    var valid = true;

    for (final item in items) {
      final fieldValid = _validateField(item.key, textControllerFor(item).text);
      if (!fieldValid) valid = false;
    }

    return valid;
  }

  bool _validateField(String key, String value) {
    final normalized = value.trim();

    if (normalized.isEmpty || _parseSwapDate(normalized) != null) {
      fieldErrors.remove(key);
      fieldErrors.refresh();
      return true;
    }

    fieldErrors[key] = 'Format dd/mm/aaaa';
    fieldErrors.refresh();
    return false;
  }

  List<SiteSwapDateUpdate> _changedUpdates() {
    return items
        .where(isModified)
        .map(
          (item) => SiteSwapDateUpdate(
            brand: item.brand,
            siteCode: item.siteCode,
            swapDate: _normalizeDateText(textControllerFor(item).text),
          ),
        )
        .toList();
  }

  void _recomputeChanges() {
    final count = items.where(isModified).length;
    changedCount.value = count;
    hasChanges.value = count > 0;
  }

  String? _normalizeDateText(String? value) {
    final text = value?.trim();
    return text == null || text.isEmpty ? null : text;
  }

  DateTime? _parseSwapDate(String? raw) {
    final value = raw?.trim();
    if (value == null || value.isEmpty) return null;

    final match = RegExp(r'^(\d{2})/(\d{2})/(\d{4})$').firstMatch(value);
    if (match == null) return null;

    final day = int.tryParse(match.group(1)!);
    final month = int.tryParse(match.group(2)!);
    final year = int.tryParse(match.group(3)!);

    if (day == null || month == null || year == null) return null;

    final parsed = DateTime(year, month, day);
    if (parsed.year != year || parsed.month != month || parsed.day != day) {
      return null;
    }

    return parsed;
  }

  String _formatSwapDate(DateTime date) {
    final day = date.day.toString().padLeft(2, '0');
    final month = date.month.toString().padLeft(2, '0');
    final year = date.year.toString().padLeft(4, '0');
    return '$day/$month/$year';
  }
}