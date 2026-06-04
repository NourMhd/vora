import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:excel/excel.dart' as xlsx;
import 'package:monitoring/modules/performence/controllers/performence_controller.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../core/widgets/status_badge.dart';
import '../../../data/models/performance_report.dart';

class PerformancePage extends GetView<PerformanceController> {
  const PerformancePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.rows.isEmpty) {
          return const LoadingState(label: 'Calcul suivi production...');
        }

        if (controller.errorMessage.value != null && controller.rows.isEmpty) {
          return ErrorState(message: controller.errorMessage.value!, onRetry: controller.load);
        }

        return CustomScrollView(
          slivers: [
            _PerformanceHeader(controller: controller),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(26, 16, 26, 26),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  _SummaryCards(controller: controller),
                  const SizedBox(height: 18),
                  _FiltersCard(controller: controller),
                  const SizedBox(height: 18),
                  _PerformanceTable(controller: controller),
                ]),
              ),
            ),
          ],
        );
      }),
    );
  }
}

class _PerformanceHeader extends StatelessWidget {
  const _PerformanceHeader({required this.controller});

  final PerformanceController controller;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      expandedHeight: 142,
      toolbarHeight: 142,
      backgroundColor: AppColors.softWhite,
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(26, 24, 26, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            const Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Suivi production',
                    style: TextStyle(
                      color: AppColors.midnightNavy,
                      fontSize: 34,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1.1,
                    ),
                  ),
                  SizedBox(height: 6),
                  Text(
                    'Comparaison du kWh théorique avec la production réelle et estimée.',
                    style: TextStyle(
                      color: AppColors.mutedText,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ),
            Obx(
              () => IconButton.filledTonal(
                tooltip: 'Recalculer',
                onPressed: controller.isRefreshing.value ? null : controller.refreshData,
                icon: controller.isRefreshing.value
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh_rounded),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _SummaryCards extends StatelessWidget {
  const _SummaryCards({required this.controller});

  final PerformanceController controller;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth;
        final cardWidth = width >= 980
            ? (width - 36) / 4
            : width >= 640
                ? (width - 12) / 2
                : width;

        return Obx(() {
          return Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _MetricCard(
                width: cardWidth,
                icon: Icons.flag_rounded,
                label: 'Prévu filtré',
                value: Formatters.energy(controller.filteredTotalExpectedKwh),
              ),
              _MetricCard(
                width: cardWidth,
                icon: Icons.bolt_rounded,
                label: 'Réel filtré',
                value: Formatters.energy(controller.filteredTotalActualKwh),
              ),
              _MetricCard(
                width: cardWidth,
                icon: Icons.trending_up_rounded,
                label: 'Écart filtré',
                value: Formatters.energy(controller.filteredTotalDifferenceKwh),
              ),
              _MetricCard(
                width: cardWidth,
                icon: Icons.fact_check_rounded,
                label: 'Bonus / Malus',
                value: '${controller.filteredBonusCount} / ${controller.filteredMalusCount}',
              ),
            ],
          );
        });
      },
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.width,
    required this.icon,
    required this.label,
    required this.value,
  });

  final double width;
  final IconData icon;
  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      child: PremiumCard(
        padding: const EdgeInsets.all(18),
        child: Row(
          children: [
            CircleAvatar(
              backgroundColor: AppColors.deepTeal.withOpacity(0.10),
              foregroundColor: AppColors.deepTeal,
              child: Icon(icon),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    label,
                    style: const TextStyle(
                      color: AppColors.mutedText,
                      fontWeight: FontWeight.w800,
                      fontSize: 12,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    value,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.midnightNavy,
                      fontWeight: FontWeight.w900,
                      fontSize: 18,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FiltersCard extends StatelessWidget {
  const _FiltersCard({required this.controller});

  final PerformanceController controller;

  static const _monthLabels = {
    1: 'Janvier',
    2: 'Février',
    3: 'Mars',
    4: 'Avril',
    5: 'Mai',
    6: 'Juin',
    7: 'Juillet',
    8: 'Août',
    9: 'Septembre',
    10: 'Octobre',
    11: 'Novembre',
    12: 'Décembre',
  };

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Période de calcul',
                  style: TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
              TextButton.icon(
                onPressed: controller.clearFilters,
                icon: const Icon(Icons.filter_alt_off_rounded),
                label: const Text('Reset filtres'),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Obx(
                () => _Dropdown<String>(
                  width: 180,
                  label: 'Vue',
                  value: controller.selectedPeriod.value,
                  items: const {
                    'TEN_DAYS': '10 jours',
                    'MONTH': 'Mois',
                    'YEAR': 'Année',
                    'CUSTOM': 'Période libre',
                  },
                  onChanged: (value) => controller.setPeriod(value ?? 'MONTH'),
                ),
              ),
              Obx(
                () => _Dropdown<int>(
                  width: 130,
                  label: 'Année',
                  value: controller.selectedYear.value,
                  items: {
                    for (final year in controller.availableYears) year: year.toString(),
                  },
                  onChanged: (value) {
                    if (value != null) controller.setYear(value);
                  },
                ),
              ),
              Obx(
                () => controller.selectedPeriod.value == 'YEAR' ||
                        controller.selectedPeriod.value == 'CUSTOM'
                    ? const SizedBox.shrink()
                    : _Dropdown<int>(
                        width: 155,
                        label: 'Mois',
                        value: controller.selectedMonth.value,
                        items: _monthLabels,
                        onChanged: (value) {
                          if (value != null) controller.setMonth(value);
                        },
                      ),
              ),
              Obx(
                () => controller.selectedPeriod.value == 'TEN_DAYS'
                    ? _Dropdown<int>(
                        width: 155,
                        label: 'Tranche',
                        value: controller.selectedDecade.value,
                        items: const {
                          1: '1 à 10',
                          2: '11 à 20',
                          3: '21 à fin',
                        },
                        onChanged: (value) {
                          if (value != null) controller.setDecade(value);
                        },
                      )
                    : const SizedBox.shrink(),
              ),
              Obx(
                () => _DatePickerButton(
                  width: 185,
                  label: 'Date début',
                  value: controller.selectedStartDate.value,
                  firstDate: DateTime(2000, 1, 1),
                  lastDate: DateTime(2100, 12, 31),
                  onSelected: controller.setStartDate,
                ),
              ),
              Obx(
                () => _DatePickerButton(
                  width: 185,
                  label: 'Date fin',
                  value: controller.selectedEndDate.value,
                  firstDate: DateTime(2000, 1, 1),
                  lastDate: DateTime(2100, 12, 31),
                  onSelected: controller.setEndDate,
                ),
              ),
              Obx(
                () => _Dropdown<String>(
                  width: 145,
                  label: 'État',
                  value: controller.selectedStatus.value,
                  items: controller.statusFilterItems,
                  onChanged: (value) => controller.setStatus(value ?? 'HEALTHY'),
                ),
              ),
              Obx(
                () => _Dropdown<String>(
                  width: 145,
                  label: 'Zone',
                  value: controller.selectedZone.value,
                  items: controller.zoneFilterItems,
                  onChanged: (value) => controller.setZone(value ?? 'ALL'),
                ),
              ),
              Obx(
                () => _Dropdown<String>(
                  width: 160,
                  label: 'Gouvernorat',
                  value: controller.selectedGovernorate.value,
                  items: controller.governorateFilterItems,
                  onChanged: (value) => controller.setGovernorate(value ?? 'ALL'),
                ),
              ),
              Obx(
                () => _Dropdown<String>(
                  width: 140,
                  label: 'RT/GF',
                  value: controller.selectedPlantType.value,
                  items: controller.plantTypeFilterItems,
                  onChanged: (value) => controller.setPlantType(value ?? 'ALL'),
                ),
              ),
              Obx(
                () => _Dropdown<String>(
                  width: 150,
                  label: 'Résultat',
                  value: controller.selectedResult.value,
                  items: const {
                    'ALL': 'Tous',
                    'BONUS': 'Bonus',
                    'MALUS': 'Malus',
                    'N/A': 'N/A',
                  },
                  onChanged: (value) => controller.selectedResult.value = value ?? 'ALL',
                ),
              ),
              SizedBox(
                width: 300,
                child: TextField(
                  onChanged: (value) => controller.searchQuery.value = value,
                  decoration: const InputDecoration(
                    prefixIcon: Icon(Icons.search_rounded),
                    hintText: 'Site, zone, gouvernorat...',
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Obx(
            () => Text(
              '${controller.filteredRows.length} sites affichés · ${controller.selectedRangeLabel}'
              '${controller.filteredMissingCount > 0 ? ' · ${controller.filteredMissingCount} sites avec historique manquant' : ''}',
              style: const TextStyle(
                color: AppColors.mutedText,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _Dropdown<T> extends StatelessWidget {
  const _Dropdown({
    required this.width,
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
  });

  final double width;
  final String label;
  final T value;
  final Map<T, String> items;
  final ValueChanged<T?> onChanged;

  @override
  Widget build(BuildContext context) {
    final safeValue = items.containsKey(value) ? value : items.keys.first;

    return SizedBox(
      width: width,
      child: DropdownButtonFormField<T>(
        value: safeValue,
        isExpanded: true,
        decoration: InputDecoration(labelText: label),
        items: items.entries
            .map(
              (entry) => DropdownMenuItem<T>(
                value: entry.key,
                child: Text(
                  entry.value,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            )
            .toList(),
        onChanged: onChanged,
      ),
    );
  }
}

class _DatePickerButton extends StatelessWidget {
  const _DatePickerButton({
    required this.width,
    required this.label,
    required this.value,
    required this.firstDate,
    required this.lastDate,
    required this.onSelected,
  });

  final double width;
  final String label;
  final DateTime value;
  final DateTime firstDate;
  final DateTime lastDate;
  final ValueChanged<DateTime> onSelected;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () async {
          final selected = await showDatePicker(
            context: context,
            initialDate: value,
            firstDate: firstDate,
            lastDate: lastDate,
          );

          if (selected != null) {
            onSelected(selected);
          }
        },
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: label,
            suffixIcon: const Icon(Icons.calendar_today_rounded, size: 18),
          ),
          child: Text(
            _formatDateNumeric(value),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              color: AppColors.midnightNavy,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ),
    );
  }
}

class _PerformanceTable extends StatefulWidget {
  const _PerformanceTable({required this.controller});

  final PerformanceController controller;

  @override
  State<_PerformanceTable> createState() => _PerformanceTableState();
}

class _PerformanceTableState extends State<_PerformanceTable> {
  final ScrollController _horizontalController = ScrollController();
  final ScrollController _verticalController = ScrollController();

  @override
  void dispose() {
    _horizontalController.dispose();
    _verticalController.dispose();
    super.dispose();
  }

  Future<void> _exportExcel(List<PerformanceReportRow> rows) async {
    if (rows.isEmpty) {
      Get.snackbar(
        'Export impossible',
        'Aucune donnée à exporter.',
        snackPosition: SnackPosition.BOTTOM,
      );
      return;
    }

    try {
      final book = xlsx.Excel.createExcel();
      const sheetName = 'Suivi production';
      final sheet = book[sheetName];
      book.delete('Sheet1');

      sheet.appendRow([
        xlsx.TextCellValue('Nom site'),
        xlsx.TextCellValue('Puissance crête (kWp)'),
        xlsx.TextCellValue('Statut'),
        xlsx.TextCellValue('Marque'),
        xlsx.TextCellValue('Date filtre début'),
        xlsx.TextCellValue('Date filtre fin'),
        xlsx.TextCellValue('Date swap'),
        xlsx.TextCellValue('Date mise en service'),
        xlsx.TextCellValue('Jours filtre'),
        xlsx.TextCellValue('Jours production'),
        xlsx.TextCellValue('Jours monitorisés'),
        xlsx.TextCellValue('Jours non monitorisés'),
        xlsx.TextCellValue('RT/GF'),
        xlsx.TextCellValue('Zone'),
        xlsx.TextCellValue('kWh prévu'),
        xlsx.TextCellValue('kWh monitorisé'),
        xlsx.TextCellValue('kWh estimé'),
        xlsx.TextCellValue('kWh réel'),
        xlsx.TextCellValue('Écart kWh'),
        xlsx.TextCellValue('Écart %'),
        xlsx.TextCellValue('État'),
        xlsx.TextCellValue('Bonus/Malus'),
      ]);

      for (final row in rows) {
        final status = _gapStatus(row);
        sheet.appendRow([
          xlsx.TextCellValue(_cleanSiteName(row.siteName)),
          _excelCell(row.installedPowerKw),
          xlsx.TextCellValue(_siteStatusText(row.siteStatus)),
          xlsx.TextCellValue(row.brand),
          xlsx.TextCellValue(_displayIsoDate(row.filterStartDate)),
          xlsx.TextCellValue(_displayIsoDate(row.filterEndDate)),
          xlsx.TextCellValue(_displayIsoDate(row.swapDate)),
          xlsx.TextCellValue(_displayIsoDate(row.gridConnectionDate)),
          xlsx.IntCellValue(row.filterDaysCount),
          xlsx.IntCellValue(row.productionDaysCount),
          xlsx.IntCellValue(row.capturedDaysCount),
          xlsx.IntCellValue(row.estimatedDaysCount),
          xlsx.TextCellValue(row.plantType),
          xlsx.TextCellValue(row.zone ?? '-'),
          _excelCell(row.expectedKwh),
          _excelCell(row.capturedKwh),
          _excelCell(row.estimatedMissingKwh),
          _excelCell(row.actualKwh),
          _excelCell(row.differenceKwh),
          _excelCell(row.differencePercent),
          xlsx.TextCellValue(status.label),
          xlsx.TextCellValue(_bonusMalusText(row)),
        ]);
      }

      final now = DateTime.now();
      final stamp = '${now.year}'
          '${now.month.toString().padLeft(2, '0')}'
          '${now.day.toString().padLeft(2, '0')}_'
          '${now.hour.toString().padLeft(2, '0')}'
          '${now.minute.toString().padLeft(2, '0')}';

      book.save(fileName: 'suivi_production_$stamp.xlsx');

      Get.snackbar(
        'Export terminé',
        'Le fichier Excel a été téléchargé.',
        snackPosition: SnackPosition.BOTTOM,
      );
    } catch (error) {
      Get.snackbar(
        'Erreur export',
        error.toString(),
        snackPosition: SnackPosition.BOTTOM,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final rows = widget.controller.filteredRows;

      return PremiumCard(
        padding: EdgeInsets.zero,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 18, 20, 14),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Tableau suivi production',
                      style: TextStyle(
                        color: AppColors.midnightNavy,
                        fontSize: 20,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  ElevatedButton.icon(
                    onPressed: rows.isEmpty ? null : () => _exportExcel(rows),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.midnightNavy,
                      foregroundColor: Colors.white,
                      disabledBackgroundColor: const Color(0xFFE5E7EB),
                      disabledForegroundColor: const Color(0xFF94A3B8),
                      elevation: 0,
                      padding: const EdgeInsets.symmetric(
                        horizontal: 18,
                        vertical: 14,
                      ),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(14),
                      ),
                    ),
                    icon: const Icon(Icons.file_download_outlined, size: 18),
                    label: const Text(
                      'Exporter Excel',
                      style: TextStyle(fontWeight: FontWeight.w800),
                    ),
                  ),
                ],
              ),
            ),
            const Divider(height: 1, color: Color(0xFFE5E7EB)),
            if (rows.isEmpty)
              const Padding(
                padding: EdgeInsets.all(32),
                child: Center(
                  child: Text(
                    'Aucune donnée trouvée pour ces filtres.',
                    style: TextStyle(
                      color: Color(0xFF64748B),
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
              )
            else
              LayoutBuilder(
                builder: (context, constraints) {
                  final minTableWidth = math.max(constraints.maxWidth, 2040.0);
                  final availableHeight = MediaQuery.sizeOf(context).height - 300;
                  final tableHeight = math.max(430.0, math.min(650.0, availableHeight));

                  return Scrollbar(
                    controller: _horizontalController,
                    thumbVisibility: true,
                    notificationPredicate: (notification) => notification.depth == 0,
                    child: SingleChildScrollView(
                      controller: _horizontalController,
                      scrollDirection: Axis.horizontal,
                      child: SizedBox(
                        width: minTableWidth,
                        height: tableHeight,
                        child: Column(
                          children: [
                            const _PerformanceTableHeader(),
                            Expanded(
                              child: Scrollbar(
                                controller: _verticalController,
                                thumbVisibility: true,
                                child: ListView.separated(
                                  controller: _verticalController,
                                  itemCount: rows.length,
                                  separatorBuilder: (_, __) => const Divider(
                                    height: 1,
                                    color: Color(0xFFE5E7EB),
                                  ),
                                  itemBuilder: (context, index) {
                                    return _PerformanceTableRow(row: rows[index]);
                                  },
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
          ],
        ),
      );
    });
  }
}

class _PerformanceTableHeader extends StatelessWidget {
  const _PerformanceTableHeader();

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 65,
      decoration: const BoxDecoration(color: Color(0xFFF8FAFC)),
      child: const Row(
        children: [
          _HeaderCell('Nom site', flex: 22, alignment: Alignment.centerLeft),
          _HeaderCell('Puissance\ncrête', flex: 12, alignment: Alignment.centerRight),
          _HeaderCell('Statut', flex: 11, alignment: Alignment.center),
          _HeaderCell('Swap', flex: 11, alignment: Alignment.center),
          _HeaderCell('MES', flex: 11, alignment: Alignment.center),
          _HeaderCell('Jours filtre', flex: 9, alignment: Alignment.centerRight),
_HeaderCell('Jours prod.\nMonit. / Non monit.', flex: 11, alignment: Alignment.centerRight),
          _HeaderCell('RT/GF', flex: 8, alignment: Alignment.center),
          _HeaderCell('Zone', flex: 10, alignment: Alignment.centerLeft),
          _HeaderCell('kWh prévu', flex: 15, alignment: Alignment.centerRight),
          _HeaderCell('kWh monit.', flex: 15, alignment: Alignment.centerRight),
          _HeaderCell('kWh estimé', flex: 15, alignment: Alignment.centerRight),
          _HeaderCell('kWh réel', flex: 15, alignment: Alignment.centerRight),
          _HeaderCell('Écart', flex: 25, alignment: Alignment.centerRight),
          _HeaderCell('Bonus/Malus', flex: 12, alignment: Alignment.center),
        ],
      ),
    );
  }
}

class _PerformanceTableRow extends StatelessWidget {
  const _PerformanceTableRow({required this.row});

  final PerformanceReportRow row;

  @override
  Widget build(BuildContext context) {
    final status = _gapStatus(row);
    final siteName = _cleanSiteName(row.siteName);

    return Container(
      constraints: const BoxConstraints(minHeight: 74),
      color: Colors.white,
      child: Row(
        children: [
          _ValueCell(
            flex: 22,
            alignment: Alignment.centerLeft,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  siteName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  row.brand,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: Color(0xFF64748B),
                    fontSize: 12,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
          _ValueCell(
            flex: 12,
            alignment: Alignment.centerRight,
            child: Text(
              row.installedPowerKw == null ? '—' : Formatters.power(row.installedPowerKw),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w900),
            ),
          ),
          _ValueCell(
            flex: 11,
            alignment: Alignment.center,
            child: StatusBadge(status: row.siteStatus, compact: true),
          ),
          _ValueCell(
            flex: 11,
            alignment: Alignment.center,
            child: Text(
              _displayIsoDate(row.swapDate),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 11,
            alignment: Alignment.center,
            child: Text(
              _displayIsoDate(row.gridConnectionDate),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 9,
            alignment: Alignment.centerRight,
            child: Text(
              row.filterDaysCount.toString(),
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
  flex: 11,
  alignment: Alignment.centerRight,
  child: Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.end,
    children: [
      Text(
        row.productionDaysCount.toString(),
        style: _cellTextStyle(fontWeight: FontWeight.w900),
      ),
      const SizedBox(height: 3),
      Text(
        '${row.capturedDaysCount} / ${row.estimatedDaysCount}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          color: AppColors.mutedText,
          fontSize: 11,
          fontWeight: FontWeight.w800,
        ),
      ),
    ],
  ),
),
          _ValueCell(
            flex: 8,
            alignment: Alignment.center,
            child: _PlantTypeBadge(label: row.plantType),
          ),
          _ValueCell(
            flex: 10,
            alignment: Alignment.centerLeft,
            child: Text(
              row.zone ?? '-',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 15,
            alignment: Alignment.centerRight,
            child: Text(
              _kwhText(row.expectedKwh),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 15,
            alignment: Alignment.centerRight,
            child: Text(
              _kwhText(row.capturedKwh),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 15,
            alignment: Alignment.centerRight,
            child: Text(
              row.estimatedDaysCount <= 0 ? '—' : _kwhText(row.estimatedMissingKwh),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w800),
            ),
          ),
          _ValueCell(
            flex: 15,
            alignment: Alignment.centerRight,
            child: Text(
              _kwhText(row.actualKwh),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: _cellTextStyle(fontWeight: FontWeight.w900),
            ),
          ),
          _ValueCell(
            flex: 25,
            alignment: Alignment.centerRight,
            child: _GapCell(row: row, status: status),
          ),
          _ValueCell(
            flex: 12,
            alignment: Alignment.center,
            child: _BonusMalusBadge(row: row, status: status),
          ),
        ],
      ),
    );
  }
}

class _HeaderCell extends StatelessWidget {
  const _HeaderCell(
    this.label, {
    required this.flex,
    required this.alignment,
  });

  final String label;
  final int flex;
  final Alignment alignment;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      flex: flex,
      child: Container(
        alignment: alignment,
        padding: const EdgeInsets.symmetric(horizontal: 14),
        child:Text(
  label,
  maxLines: 2,
  overflow: TextOverflow.ellipsis,
  textAlign: alignment == Alignment.centerRight
      ? TextAlign.right
      : alignment == Alignment.center
          ? TextAlign.center
          : TextAlign.left,
  style: const TextStyle(
    color: AppColors.midnightNavy,
    fontSize: 12,
    height: 1.15,
    fontWeight: FontWeight.w900,
  ),
),
      ),
    );
  }
}

class _ValueCell extends StatelessWidget {
  const _ValueCell({
    required this.flex,
    required this.alignment,
    required this.child,
  });

  final int flex;
  final Alignment alignment;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      flex: flex,
      child: Container(
        alignment: alignment,
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: child,
      ),
    );
  }
}

class _GapCell extends StatelessWidget {
  const _GapCell({required this.row, required this.status});

  final PerformanceReportRow row;
  final _GapStatus status;

  @override
  Widget build(BuildContext context) {
    final gap = row.differenceKwh == null || row.differencePercent == null
        ? '—'
        : '${_signedDecimal(row.differenceKwh)} kWh (${_signedDecimal(row.differencePercent)}%)';

    return Wrap(
      alignment: WrapAlignment.end,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 8,
      runSpacing: 6,
      children: [
        Text(
          gap,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(
            color: status.foreground,
            fontWeight: FontWeight.w900,
          ),
        ),
        _StatusBadge(status: status),
      ],
    );
  }
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});

  final _GapStatus status;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: status.background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: status.border),
      ),
      child: Text(
        status.label,
        style: TextStyle(
          color: status.foreground,
          fontSize: 11,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _BonusMalusBadge extends StatelessWidget {
  const _BonusMalusBadge({required this.row, required this.status});

  final PerformanceReportRow row;
  final _GapStatus status;

  @override
  Widget build(BuildContext context) {
    final label = _bonusMalusText(row);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: status.background,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: status.border),
      ),
      child: Text(
        label,
        style: TextStyle(
          color: status.foreground,
          fontSize: 11,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _PlantTypeBadge extends StatelessWidget {
  const _PlantTypeBadge({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: const Color(0xFFE6F7F4),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: Color(0xFF0F766E),
          fontSize: 12,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _GapStatus {
  const _GapStatus({
    required this.label,
    required this.foreground,
    required this.background,
    required this.border,
  });

  final String label;
  final Color foreground;
  final Color background;
  final Color border;
}

_GapStatus _gapStatus(PerformanceReportRow row) {
  final percent = row.differencePercent;

  if (percent == null || row.hasMissingData) {
    return const _GapStatus(
      label: 'données manquantes',
      foreground: Color(0xFF64748B),
      background: Color(0xFFF1F5F9),
      border: Color(0xFFE2E8F0),
    );
  }

  if (percent >= 0) {
    return const _GapStatus(
      label: 'bon état',
      foreground: Color(0xFF15803D),
      background: Color(0xFFEAF7EE),
      border: Color(0xFFCBEBD3),
    );
  }

  if (percent >= -15) {
    return const _GapStatus(
      label: 'tolérable',
      foreground: Color.fromARGB(255, 10, 212, 84),
      background: Color.fromARGB(255, 244, 255, 230),
      border: Color(0xFFFFD8A8),
    );
  }

  if (percent >= -30) {
    return const _GapStatus(
      label: 'problème',
      foreground: Color(0xFFEF4444),
      background: Color(0xFFFFEEEE),
      border: Color(0xFFFFCACA),
    );
  }

  return const _GapStatus(
    label: 'à visiter',
    foreground: Color(0xFFB91C1C),
    background: Color(0xFFFFE4E6),
    border: Color(0xFFFCA5A5),
  );
}

String _kwhText(num? value) {
  if (value == null) return '—';
  return '${Formatters.decimal(value, decimals: 3)} kWh';
}

String _twoDigits(int value) {
  return value.toString().padLeft(2, '0');
}

String _formatDateNumeric(DateTime value) {
  return '${_twoDigits(value.day)}/${_twoDigits(value.month)}/${value.year}';
}

String _displayIsoDate(String? value) {
  if (value == null || value.trim().isEmpty) return '—';

  final parsed = DateTime.tryParse(value.trim());
  if (parsed == null) return value;

  return _formatDateNumeric(parsed);
}

String _cleanSiteName(String value) {
  return value
      .replaceAll(RegExp(r'\s*\(?NE\s*=\s*[^\s\)]+\)?', caseSensitive: false), '')
      .replaceAll(RegExp(r'\s{2,}'), ' ')
      .trim();
}

String _siteStatusText(String value) {
  final cleaned = value.trim().toUpperCase();
  if (cleaned == 'HEALTHY' || cleaned == 'ONLINE') return 'Healthy';
  if (cleaned == 'FAULTY' || cleaned == 'FAULT' || cleaned == 'ALARM') {
    return 'Faulty';
  }
  if (cleaned == 'DISCONNECTED' || cleaned == 'OFFLINE') return 'Offline';
  return cleaned.isEmpty ? '—' : cleaned;
}

String _bonusMalusText(PerformanceReportRow row) {
  final raw = row.bonusMalus.trim().toUpperCase();
  if (raw == 'BONUS' || row.isBonus) return 'BONUS';
  if (raw == 'MALUS' || row.isMalus) return 'MALUS';

  final percent = row.differencePercent;
  if (percent == null || row.hasMissingData) return '—';
  return percent >= 0 ? 'BONUS' : 'MALUS';
}

xlsx.CellValue _excelCell(num? value) {
  if (value == null) return xlsx.TextCellValue('-');
  return xlsx.DoubleCellValue(value.toDouble());
}

String _signedDecimal(num? value) {
  if (value == null) return '—';
  final formatted = Formatters.decimal(value.abs(), decimals: 3);
  if (value > 0) return '+$formatted';
  if (value < 0) return '-$formatted';
  return formatted;
}

TextStyle _cellTextStyle({FontWeight fontWeight = FontWeight.w700}) {
  return TextStyle(
    color: AppColors.midnightNavy,
    fontSize: 14,
    fontWeight: fontWeight,
  );
}
