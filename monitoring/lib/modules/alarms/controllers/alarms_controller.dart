import 'dart:async';

import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../core/network/api_exception.dart';
import '../../../core/sync/front_sync_policy.dart';
import '../../../data/models/alarm_report.dart';
import '../../../data/repositories/monitoring_repository.dart';

class AlarmsController extends GetxController {
  AlarmsController({required MonitoringRepository repository}) : _repository = repository;

  static const int pageSize = 10;

  final MonitoringRepository _repository;

  final isLoading = false.obs;
  final isRefreshing = false.obs;
  final errorMessage = RxnString();
  final lastRefresh = Rxn<DateTime>();
  final report = Rxn<AlarmReport>();

  final ScrollController scrollController = ScrollController();
  final RxMap<String, int> _pages = <String, int>{}.obs;

  final Map<String, GlobalKey> _sectionAnchorKeys = <String, GlobalKey>{};
  final GlobalKey historyAnchorKey = GlobalKey(debugLabel: 'alarm-history-anchor');

  Timer? _timer;
  bool _fetchInFlight = false;

  int get activeCount => report.value?.activeCount ?? 0;
  int get historyCount => report.value?.historyCount ?? 0;
  List<AlarmSection> get sections => report.value?.activeSections ?? const [];
  List<AlarmItem> get history => report.value?.history ?? const [];

  String sectionPageKey(AlarmSection section) => sectionPageKeyByType(section.type);
  String sectionPageKeyByType(String type) => 'section_$type';
  String get historyPageKey => 'history';

  GlobalKey sectionAnchorKey(String type) {
    return _sectionAnchorKeys.putIfAbsent(
      type,
      () => GlobalKey(debugLabel: 'alarm-section-$type-anchor'),
    );
  }

  int pageFor(String key) => _pages[key] ?? 0;

  int maxPageForCount(int itemCount) {
    if (itemCount <= 0) return 0;
    return (itemCount - 1) ~/ pageSize;
  }

  int startIndexFor(String key) => pageFor(key) * pageSize;

  int endIndexFor(String key, int itemCount) {
    final end = startIndexFor(key) + pageSize;
    return end > itemCount ? itemCount : end;
  }

  List<AlarmItem> pagedSectionAlarms(AlarmSection section) {
    return _slice(section.alarms, pageFor(sectionPageKey(section)));
  }

  List<AlarmItem> pagedHistory() {
    return _slice(history, pageFor(historyPageKey));
  }

  void nextPage(String key, int itemCount) {
    final current = pageFor(key);
    final maxPage = maxPageForCount(itemCount);
    if (current < maxPage) {
      _pages[key] = current + 1;
    }
  }

  void previousPage(String key) {
    final current = pageFor(key);
    if (current > 0) {
      _pages[key] = current - 1;
    }
  }

  void goToFirstPage(String key) {
    _pages[key] = 0;
  }

  void jumpToSection(AlarmSection section) {
    jumpToSectionType(section.type);
  }

  void jumpToSectionType(String type) {
    goToFirstPage(sectionPageKeyByType(type));
    _scrollToKey(sectionAnchorKey(type));
  }

  void jumpToHistory() {
    goToFirstPage(historyPageKey);
    _scrollToKey(historyAnchorKey);
  }

  void _scrollToKey(GlobalKey key, {int attempt = 0}) {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (attempt == 0) {
        await Future<void>.delayed(const Duration(milliseconds: 40));
      }

      final context = key.currentContext;
      if (context == null) {
        if (attempt < 6) {
          Future<void>.delayed(
            const Duration(milliseconds: 80),
            () => _scrollToKey(key, attempt: attempt + 1),
          );
        }
        return;
      }

      if (!scrollController.hasClients) {
        Scrollable.ensureVisible(
          context,
          duration: const Duration(milliseconds: 450),
          curve: Curves.easeOutCubic,
          alignment: 0.06,
        );
        return;
      }

      final targetBox = context.findRenderObject();
      final viewportContext = scrollController.position.context.storageContext;
      final viewportBox = viewportContext.findRenderObject();

      if (targetBox is! RenderBox || viewportBox is! RenderBox) {
        Scrollable.ensureVisible(
          context,
          duration: const Duration(milliseconds: 450),
          curve: Curves.easeOutCubic,
          alignment: 0.06,
        );
        return;
      }

      final targetDy = targetBox.localToGlobal(Offset.zero).dy;
      final viewportDy = viewportBox.localToGlobal(Offset.zero).dy;

      // Petit offset pour garder le titre du bloc visible sous le SliverAppBar pinned.
      final rawOffset = scrollController.offset + (targetDy - viewportDy) - 12;
      final targetOffset = rawOffset
          .clamp(
            scrollController.position.minScrollExtent,
            scrollController.position.maxScrollExtent,
          )
          .toDouble();

      await scrollController.animateTo(
        targetOffset,
        duration: const Duration(milliseconds: 520),
        curve: Curves.easeOutCubic,
      );
    });
  }

  @override
  void onInit() {
    super.onInit();
    load();
    _startAutoRefresh();
  }

  @override
  void onClose() {
    _timer?.cancel();
    scrollController.dispose();
    super.onClose();
  }

  Future<void> load() async {
    try {
      isLoading.value = true;
      errorMessage.value = null;
      await _fetch();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Impossible de charger les alarmes.';
    } finally {
      isLoading.value = false;
    }
  }

  Future<void> refreshData() async {
    try {
      isRefreshing.value = true;
      errorMessage.value = null;
      await _fetch();
    } on ApiException catch (e) {
      errorMessage.value = e.message;
    } catch (_) {
      errorMessage.value = 'Refresh alarmes impossible.';
    } finally {
      isRefreshing.value = false;
    }
  }

  Future<void> refreshSilently() async {
    try {
      await _fetch();
    } catch (_) {
      // Keep current alarm state visible if the API is temporarily unreachable.
    }
  }

  Future<void> _fetch() async {
    if (_fetchInFlight) return;

    _fetchInFlight = true;
    try {
      report.value = await _repository.getAlarmReport();
      lastRefresh.value = DateTime.now();
      _normalizePages();
    } finally {
      _fetchInFlight = false;
    }
  }

  List<AlarmItem> _slice(List<AlarmItem> items, int page) {
    if (items.isEmpty) return const [];

    final start = page * pageSize;
    if (start >= items.length) return const [];

    final end = start + pageSize > items.length ? items.length : start + pageSize;
    return items.sublist(start, end);
  }

  void _normalizePages() {
    for (final section in sections) {
      _normalizePage(sectionPageKey(section), section.alarms.length);
    }

    _normalizePage(historyPageKey, history.length);
  }

  void _normalizePage(String key, int itemCount) {
    final current = pageFor(key);
    final maxPage = maxPageForCount(itemCount);

    if (current > maxPage) {
      _pages[key] = maxPage;
    }
  }

  void _startAutoRefresh() {
    _timer?.cancel();
    _timer = Timer.periodic(
      FrontSyncPolicy.liveFast,
      (_) => refreshSilently(),
    );
  }
}
