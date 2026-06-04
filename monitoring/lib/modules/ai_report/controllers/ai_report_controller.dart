import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/utils/file_downloader.dart';
import '../../../core/utils/formatters.dart';
import '../../../data/models/ai_report.dart';
import '../../../data/models/site_summary.dart';
import '../../../data/repositories/monitoring_repository.dart';

class AiReportController extends GetxController {
  AiReportController({required MonitoringRepository repository}) : _repository = repository;

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isDownloading = false.obs;
  final isLoadingScopeValues = false.obs;
  final errorMessage = RxnString();
  final report = AiReportPreview.empty().obs;

  final startDate = DateTime(DateTime.now().year, DateTime.now().month, 1).obs;
  final endDate = DateTime(DateTime.now().year, DateTime.now().month + 1, 0).obs;
  final scope = 'ALL'.obs;
  final scopeValue = ''.obs;
  final reportType = 'STANDARD'.obs;

  final zoneOptions = <AiScopeValueOption>[].obs;
  final governorateOptions = <AiScopeValueOption>[].obs;
  final siteOptions = <AiScopeValueOption>[].obs;

  static const Map<String, List<String>> _zoneGovernorates = {
    'Zone A': ['ARI', 'BAR', 'MAN', 'JEN', 'ZAG', 'BIZ', 'TUN', 'NAB', 'BEJ', 'KEF', 'SIL'],
    'Zone B': ['KAI', 'SOU', 'MAH', 'KAS', 'MON', 'SBO', 'SFA'],
    'Zone C': ['MED', 'TAT', 'GAB', 'TOZ', 'GAF', 'KEB'],
  };

  static final List<String> _governorateCodes = _zoneGovernorates.values.expand((items) => items).toList();

  @override
  void onInit() {
    super.onInit();
    loadScopeValues();
    loadPreview();
  }

  String get selectedRangeLabel => '${Formatters.date(startDate.value)} → ${Formatters.date(endDate.value)}';

  bool get hasReport => report.value.period.startDate.isNotEmpty;

  List<AiScopeValueOption> get currentScopeValueOptions {
    return switch (scope.value) {
      'ZONE' => zoneOptions,
      'GOVERNORATE' => governorateOptions,
      'SITE' => siteOptions,
      _ => const <AiScopeValueOption>[],
    };
  }

  bool get needsScopeValue => scope.value != 'ALL';

  Future<void> loadScopeValues() async {
    try {
      isLoadingScopeValues.value = true;
      final sites = await _repository.getSites();
      _buildScopeValueOptions(sites);
      _ensureValidScopeValue();
    } catch (_) {
      if (zoneOptions.isEmpty) {
        zoneOptions.assignAll(_zoneGovernorates.keys.map((zone) => AiScopeValueOption(value: zone, label: zone)));
      }
    } finally {
      isLoadingScopeValues.value = false;
    }
  }

  Future<void> loadPreview() async {
    try {
      isLoading.value = true;
      errorMessage.value = null;
      final start = Formatters.apiDay(startDate.value);
      final end = Formatters.apiDay(endDate.value);
      report.value = await _repository.getAiReportPreview(
        startDate: start,
        endDate: end,
        scope: scope.value,
        scopeValue: needsScopeValue ? scopeValue.value : '',
        type: reportType.value,
      );
    } on ApiException catch (e) {
      report.value = AiReportPreview.empty();
      errorMessage.value = e.message;
    } catch (_) {
      report.value = AiReportPreview.empty();
      errorMessage.value = 'Impossible de générer l’aperçu du rapport. Vérifiez la clé Gemini API et la connexion Internet du backend.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> downloadPdf() async {
    try {
      isDownloading.value = true;
      final start = Formatters.apiDay(startDate.value);
      final end = Formatters.apiDay(endDate.value);
      final bytes = await _repository.downloadAiReportPdf(
        startDate: start,
        endDate: end,
        scope: scope.value,
        scopeValue: needsScopeValue ? scopeValue.value : '',
        type: reportType.value,
      );
      final stamp = '${start}_$end'.replaceAll('-', '');
      await downloadBytes(
        bytes: bytes,
        filename: 'rapport_ai_$stamp.pdf',
        mimeType: 'application/pdf',
      );
      Get.snackbar('Rapport PDF prêt', 'Le téléchargement du rapport AI a été lancé.');
    } on ApiException catch (e) {
      errorMessage.value = e.message;
      Get.snackbar('Rapport non généré', e.message);
    } catch (_) {
      errorMessage.value = 'Impossible de télécharger le rapport PDF. Vérifiez la clé Gemini API et la connexion Internet du backend.';
      Get.snackbar('Rapport non généré', 'Impossible de télécharger le rapport PDF. Vérifiez la clé Gemini API et la connexion Internet du backend.');
    } finally {
      isDownloading.value = false;
    }
  }

  void setStartDate(DateTime value) {
    final clean = DateTime(value.year, value.month, value.day);
    startDate.value = clean;
    if (endDate.value.isBefore(clean)) {
      endDate.value = clean;
    }
  }

  void setEndDate(DateTime value) {
    final clean = DateTime(value.year, value.month, value.day);
    endDate.value = clean;
    if (startDate.value.isAfter(clean)) {
      startDate.value = clean;
    }
  }

  void setScope(String value) {
    scope.value = value;
    if (value == 'ALL') {
      scopeValue.value = '';
      return;
    }
    _selectFirstScopeValue();
  }

  void setScopeValue(String value) {
    scopeValue.value = value;
  }

  void setReportType(String value) {
    reportType.value = value == 'SUMMARY' ? 'SUMMARY' : 'STANDARD';
  }

  void _buildScopeValueOptions(List<SiteSummary> sites) {
    final zoneSet = <String>{};
    final governorateSet = <String>{};
    final siteItems = <AiScopeValueOption>[];

    final sortedSites = [...sites]
      ..sort((a, b) => a.siteName.toUpperCase().compareTo(b.siteName.toUpperCase()));

    for (final site in sortedSites) {
      final governorate = _governorateForSite(site);
      if (governorate != null) {
        governorateSet.add(governorate);
        final zone = _zoneForGovernorate(governorate);
        if (zone != null) zoneSet.add(zone);
      }

      final label = site.siteName.trim().isEmpty ? site.siteCode : site.siteName.trim();
      final value = site.siteCode.trim().isEmpty ? label : site.siteCode.trim();
      siteItems.add(AiScopeValueOption(value: value, label: label));
    }

    final zones = zoneSet.toList()..sort();
    final governorates = governorateSet.toList()..sort();

    zoneOptions.assignAll(zones.map((zone) => AiScopeValueOption(value: zone, label: zone)));
    governorateOptions.assignAll(governorates.map((gov) => AiScopeValueOption(value: gov, label: gov)));
    siteOptions.assignAll(siteItems);
  }

  String? _governorateForSite(SiteSummary site) {
    return _governorateFromText(site.siteName) ?? _governorateFromText(site.siteCode);
  }

  String? _governorateFromText(String raw) {
    final normalized = raw.trim().toUpperCase();
    if (normalized.isEmpty) return null;

    for (final code in _governorateCodes) {
      if (normalized == code || normalized.startsWith('${code}_') || normalized.startsWith('${code}-')) {
        return code;
      }
      if (normalized.startsWith(code) && normalized.length > code.length) {
        final next = normalized.codeUnitAt(code.length);
        if (next >= 48 && next <= 57) return code;
      }
    }

    final tokens = normalized.split(RegExp(r'[^A-Z0-9]+'));
    for (final token in tokens) {
      for (final code in _governorateCodes) {
        if (token == code) return code;
        if (token.startsWith(code) && token.length > code.length) {
          final next = token.codeUnitAt(code.length);
          if (next >= 48 && next <= 57) return code;
        }
      }
    }

    return null;
  }

  String? _zoneForGovernorate(String governorate) {
    for (final entry in _zoneGovernorates.entries) {
      if (entry.value.contains(governorate)) return entry.key;
    }
    return null;
  }

  void _ensureValidScopeValue() {
    if (scope.value == 'ALL') return;
    final values = currentScopeValueOptions.map((item) => item.value).toSet();
    if (!values.contains(scopeValue.value)) {
      _selectFirstScopeValue();
    }
  }

  void _selectFirstScopeValue() {
    final options = currentScopeValueOptions;
    scopeValue.value = options.isEmpty ? '' : options.first.value;
  }
}

class AiScopeValueOption {
  const AiScopeValueOption({required this.value, required this.label});

  final String value;
  final String label;
}
