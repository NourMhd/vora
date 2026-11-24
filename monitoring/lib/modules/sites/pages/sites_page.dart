import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../app/routes/app_routes.dart';
import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../core/widgets/status_badge.dart';
import '../../../data/models/site_summary.dart';
import '../controllers/sites_controller.dart';

class SitesPage extends GetView<SitesController> {
  const SitesPage({super.key});
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.sites.isEmpty) {
          return const LoadingState(label: 'Chargement des sites...');
        }
        if (controller.errorMessage.value != null && controller.sites.isEmpty) {
          return ErrorState(
            message: controller.errorMessage.value!,
            onRetry: controller.load,
          );
        }
        return CustomScrollView(
          slivers: [
            _SitesHeader(controller: controller),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(26, 16, 26, 26),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  _FiltersCard(controller: controller),
                  const SizedBox(height: 20),
                  _SitesContent(controller: controller),
                ]),
              ),
            ),
          ],
        );
      }),
    );
  }
}

class _SitesHeader extends StatelessWidget {
  const _SitesHeader({required this.controller});
  final SitesController controller;
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
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Sites',
                    style: TextStyle(
                      color: AppColors.midnightNavy,
                      fontSize: 34,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1.1,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Recherche, filtres et accès opérationnel aux sites.',
                    style: TextStyle(
                      color: AppColors.mutedText,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Obx(
                    () => Text(
                      'Dernière mise à jour · auto 10s · ${Formatters.dateTime(controller.lastRefresh.value)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Obx(
              () => IconButton.filledTonal(
                tooltip: 'Mettre à jour maintenant',
                onPressed: controller.isRefreshing.value
                    ? null
                    : controller.refreshData,
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

class _FiltersCard extends StatelessWidget {
  const _FiltersCard({required this.controller});
  final SitesController controller;
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
                  'Filtres sites',
                  style: TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -0.3,
                  ),
                ),
              ),
              TextButton.icon(
                onPressed: controller.clearFilters,
                icon: const Icon(Icons.filter_alt_off_rounded),
                label: const Text('Reset'),
              ),
            ],
          ),
          const SizedBox(height: 14),
          TextField(
            onChanged: (value) => controller.searchQuery.value = value,
            decoration: const InputDecoration(
              prefixIcon: Icon(Icons.search_rounded),
              hintText:
                  'Rechercher un site, code, marque, zone ou gouvernorat...',
            ),
          ),
          const SizedBox(height: 16),
          LayoutBuilder(
            builder: (context, constraints) {
              final fullWidth = constraints.maxWidth;
              final wide = fullWidth >= 1180;
              final medium = fullWidth >= 760;
              final groupWidth = wide
                  ? (fullWidth - 24) / 3
                  : medium
                  ? (fullWidth - 12) / 2
                  : fullWidth;
              return Wrap(
                spacing: 12,
                runSpacing: 12,
                children: [
                  SizedBox(
                    width: groupWidth,
                    child: _FilterGroupCard(
                      icon: Icons.account_tree_rounded,
                      title: 'Zone, type et gouvernorat',
                      child: Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        children: [
                          Obx(
                            () => _DropdownFilter(
                              width: 150,
                              label: 'Zone',
                              value: controller.selectedZone.value,
                              values: const [
                                'ALL',
                                'Zone A',
                                'Zone B',
                                'Zone C',
                              ],
                              labels: const {
                                'ALL': 'Toutes',
                                'Zone A': 'Zone A',
                                'Zone B': 'Zone B',
                                'Zone C': 'Zone C',
                              },
                              onChanged: (value) =>
                                  controller.setZoneFilter(value ?? 'ALL'),
                            ),
                          ),
                          Obx(
                            () => _DropdownFilter(
                              width: 140,
                              label: 'Type',
                              value: controller.selectedPlantType.value,
                              values: const ['ALL', 'RT', 'GF'],
                              labels: const {
                                'ALL': 'Tous',
                                'RT': 'RT',
                                'GF': 'GF',
                              },
                              onChanged: (value) =>
                                  controller.selectedPlantType.value =
                                      value ?? 'ALL',
                            ),
                          ),
                          Obx(
                            () => _DropdownFilter(
                              width: 160,
                              label: 'Gouvernorat',
                              value: controller.selectedGovernor.value,
                              values: controller.availableGovernments,
                              labels: const {'ALL': 'Tous'},
                              onChanged: (value) =>
                                  controller.selectedGovernor.value =
                                      value ?? 'ALL',
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  SizedBox(
                    width: groupWidth,
                    child: _FilterGroupCard(
                      icon: Icons.sort_rounded,
                      title: 'Tri',
                      child: Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        children: [
                          Obx(
                            () => _DropdownFilter(
                              width: 190,
                              label: 'Trier par',
                              value: controller.sortBy.value,
                              values: const [
                                'commissioningDate',
                                'dayEnergy',
                                'monthEnergy',
                                'totalEnergy',
                                'installedPower',
                              ],
                              labels: const {
                                'commissioningDate': 'Date mise en service',
                                'dayEnergy': 'kWh day',
                                'monthEnergy': 'kWh month',
                                'totalEnergy': 'kWh total',
                                'installedPower': 'kWp',
                              },
                              onChanged: (value) => controller.setSortBy(
                                value ?? 'commissioningDate',
                              ),
                            ),
                          ),
                          Obx(
                            () => _DropdownFilter(
                              width: 150,
                              label: 'Ordre',
                              value: controller.sortDirection.value,
                              values: const ['desc', 'asc'],
                              labels: const {
                                'desc': 'Descendant',
                                'asc': 'Ascendant',
                              },
                              onChanged: (value) =>
                                  controller.setSortDirection(value ?? 'desc'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  SizedBox(
                    width: groupWidth,
                    child: _FilterGroupCard(
                      icon: Icons.tune_rounded,
                      title: 'Filtres opérationnels',
                      child: Wrap(
                        spacing: 12,
                        runSpacing: 12,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Obx(
                            () => _DropdownFilter(
                              width: 150,
                              label: 'Brand',
                              value: controller.selectedBrand.value,
                              values: ['ALL', ...controller.brands],
                              labels: const {'ALL': 'Toutes'},
                              onChanged: (value) =>
                                  controller.selectedBrand.value =
                                      value ?? 'ALL',
                            ),
                          ),
                          Obx(
                            () => _DropdownFilter(
                              width: 160,
                              label: 'Status',
                              value: controller.selectedStatus.value,
                              values: const [
                                'ALL',
                                'healthy',
                                'faulty',
                                'disconnected',
                              ],
                              labels: const {
                                'ALL': 'Tous',
                                'healthy': 'Healthy',
                                'faulty': 'Faulty',
                                'disconnected': 'Offline',
                              },
                              onChanged: (value) =>
                                  controller.selectedStatus.value =
                                      value ?? 'ALL',
                            ),
                          ),
                          Obx(
                            () => FilterChip(
                              selected: controller.faultsOnly.value,
                              onSelected: (value) =>
                                  controller.faultsOnly.value = value,
                              avatar: const Icon(
                                Icons.error_outline_rounded,
                                size: 18,
                              ),
                              label: const Text('Défauts uniquement'),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              );
            },
          ),
        ],
      ),
    );
  }
}

class _FilterGroupCard extends StatelessWidget {
  const _FilterGroupCard({
    required this.icon,
    required this.title,
    required this.child,
  });
  final IconData icon;
  final String title;
  final Widget child;
  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(minHeight: 132),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.softWhite,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, size: 18, color: AppColors.deepTeal),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 13,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _DropdownFilter extends StatelessWidget {
  const _DropdownFilter({
    required this.width,
    required this.label,
    required this.value,
    required this.values,
    required this.onChanged,
    this.labels = const {},
  });
  final double width;
  final String label;
  final String value;
  final List<String> values;
  final Map<String, String> labels;
  final ValueChanged<String?> onChanged;
  @override
  Widget build(BuildContext context) {
    final normalizedValues = values.toSet().toList();
    final safeValue = normalizedValues.contains(value)
        ? value
        : normalizedValues.first;
    return SizedBox(
      width: width,
      child: DropdownButtonFormField<String>(
        value: safeValue,
        isExpanded: true,
        items: normalizedValues
            .map(
              (item) => DropdownMenuItem<String>(
                value: item,
                child: Text(
                  labels[item] ?? item,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            )
            .toList(),
        onChanged: onChanged,
        decoration: InputDecoration(labelText: label),
      ),
    );
  }
}

class _SitesContent extends StatefulWidget {
  const _SitesContent({required this.controller});
  final SitesController controller;

  @override
  State<_SitesContent> createState() => _SitesContentState();
}

class _SitesContentState extends State<_SitesContent> {
  final _horizontal = ScrollController();
  final _vertical = ScrollController();

  @override
  void dispose() {
    _horizontal.dispose();
    _vertical.dispose();
    super.dispose();
  }

  void _changePage(int page) {
    widget.controller.goToPage(page);
    if (_vertical.hasClients) _vertical.jumpTo(0);
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    return Obx(() {
      final sites = controller.filteredSites;
      final size = controller.rowsPerPage.value;
      // All reactive pagination reads stay inside this Obx.
      final page = controller.visiblePage;
      final pages = sites.isEmpty ? 1 : (sites.length / size).ceil();
      final start = page * size;
      final end = (start + size).clamp(0, sites.length).toInt();
      final visible = sites.sublist(start, end);
      final exporting = controller.isExporting.value;
      final queryKey = [
        controller.searchQuery.value,
        controller.selectedBrand.value,
        controller.selectedStatus.value,
        controller.selectedZone.value,
        controller.selectedPlantType.value,
        controller.selectedGovernor.value,
        controller.sortBy.value,
        controller.sortDirection.value,
        controller.faultsOnly.value,
        size,
        page,
      ].join('|');

      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Wrap(
            spacing: 18,
            runSpacing: 12,
            alignment: WrapAlignment.spaceBetween,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(
                '${sites.length} sites · ${sites.isEmpty ? 0 : start + 1}–$end affichés',
                style: const TextStyle(
                  color: AppColors.mutedText,
                  fontWeight: FontWeight.w800,
                ),
              ),
              Tooltip(
                message:
                    'Exporter tous les sites filtrés, sur toutes les pages',
                child: FilledButton.icon(
                  onPressed: exporting || sites.isEmpty
                      ? null
                      : controller.exportFilteredSites,
                  icon: exporting
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.download_rounded, size: 20),
                  label: Text(
                    exporting ? 'Export en cours...' : 'Exporter Excel',
                  ),
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.deepTeal,
                    foregroundColor: Colors.white,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Container(
            clipBehavior: Clip.antiAlias,
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: AppColors.borderGray),
            ),
            child: Column(
              children: [
                if (sites.isEmpty)
                  const Padding(
                    padding: EdgeInsets.all(28),
                    child: EmptyState(
                      title: 'Aucun site trouvé',
                      message:
                          'Ajuste la recherche ou les filtres pour afficher les sites.',
                      icon: Icons.search_off_rounded,
                    ),
                  )
                else
                  LayoutBuilder(
                    builder: (context, constraints) {
                      final width = constraints.maxWidth < 1340
                          ? 1340.0
                          : constraints.maxWidth;
                      final height = (visible.length * 58.0)
                          .clamp(58.0, 580.0)
                          .toDouble();
                      return Scrollbar(
                        controller: _horizontal,
                        thumbVisibility: true,
                        notificationPredicate: (notification) =>
                            notification.metrics.axis == Axis.horizontal,
                        child: SingleChildScrollView(
                          controller: _horizontal,
                          scrollDirection: Axis.horizontal,
                          child: SizedBox(
                            width: width,
                            child: Column(
                              children: [
                                const _SitesTableHeader(),
                                SizedBox(
                                  height: height,
                                  child: Scrollbar(
                                    controller: _vertical,
                                    thumbVisibility: true,
                                    child: ListView.builder(
                                      // Reset position when filters, sorting or pagination change.
                                      // Silent refresh preserves the scroll position.
                                      key: ValueKey(queryKey),
                                      controller: _vertical,
                                      primary: false,
                                      padding: const EdgeInsets.only(
                                        bottom: 12,
                                      ),
                                      itemExtent: 58,
                                      itemCount: visible.length,
                                      itemBuilder: (context, index) =>
                                          _SiteTableRow(
                                            site: visible[index],
                                            governor: controller
                                                .governorForSite(
                                                  visible[index],
                                                ),
                                            alternate: index.isOdd,
                                          ),
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
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 18,
                    vertical: 12,
                  ),
                  child: Wrap(
                    spacing: 20,
                    runSpacing: 8,
                    alignment: WrapAlignment.spaceBetween,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Text('Lignes par page :'),
                          const SizedBox(width: 12),
                          DropdownButton<int>(
                            value: size,
                            underline: const SizedBox.shrink(),
                            items: const [50, 100, 200]
                                .map(
                                  (count) => DropdownMenuItem(
                                    value: count,
                                    child: Text('$count'),
                                  ),
                                )
                                .toList(),
                            onChanged: (value) {
                              if (value != null) {
                                controller.setRowsPerPage(value);
                                if (_vertical.hasClients) _vertical.jumpTo(0);
                              }
                            },
                          ),
                        ],
                      ),
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text('Page ${page + 1} / $pages'),
                          const SizedBox(width: 8),
                          IconButton(
                            tooltip: 'Première page',
                            onPressed: page == 0 ? null : () => _changePage(0),
                            icon: const Icon(Icons.first_page_rounded),
                          ),
                          IconButton(
                            tooltip: 'Page précédente',
                            onPressed: page == 0
                                ? null
                                : () => _changePage(page - 1),
                            icon: const Icon(Icons.chevron_left_rounded),
                          ),
                          IconButton(
                            tooltip: 'Page suivante',
                            onPressed: page + 1 >= pages
                                ? null
                                : () => _changePage(page + 1),
                            icon: const Icon(Icons.chevron_right_rounded),
                          ),
                          IconButton(
                            tooltip: 'Dernière page',
                            onPressed: page + 1 >= pages
                                ? null
                                : () => _changePage(pages - 1),
                            icon: const Icon(Icons.last_page_rounded),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      );
    });
  }
}

class _SitesTableHeader extends StatelessWidget {
  const _SitesTableHeader();

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      decoration: const BoxDecoration(
        color: AppColors.tealSoft,
        border: Border(bottom: BorderSide(color: AppColors.borderGray)),
      ),
      child: const Row(
        children: [
          _TableCell(flex: 15, label: 'Nom du site', header: true),
          _TableCell(flex: 16, label: 'Code du site', header: true),
          _TableCell(flex: 11, label: 'Gouvernorat', header: true),
          _TableCell(flex: 9, label: 'Marque', header: true),
          _TableCell(flex: 10, label: 'État', header: true),
          _TableCell(
            flex: 14,
            label: 'Puissance installée\n(kWc)',
            header: true,
            numeric: true,
          ),
          _TableCell(
            flex: 15,
            label: 'Production du jour\n(kWh)',
            header: true,
            numeric: true,
          ),
          _TableCell(
            flex: 15,
            label: 'Production totale\n(kWh)',
            header: true,
            numeric: true,
          ),
          _TableCell(flex: 12, label: 'Action', header: true),
        ],
      ),
    );
  }
}

class _SiteTableRow extends StatelessWidget {
  const _SiteTableRow({
    required this.site,
    required this.governor,
    required this.alternate,
  });
  final SiteSummary site;
  final String governor;
  final bool alternate;

  void _open() => Get.toNamed(
    AppRoutes.siteDetailsPath(brand: site.brand, siteCode: site.siteCode),
  );

  @override
  Widget build(BuildContext context) {
    return Material(
      color: alternate ? AppColors.softWhite : Colors.white,
      child: InkWell(
        onTap: _open,
        hoverColor: AppColors.tealSoft,
        child: Container(
          decoration: const BoxDecoration(
            border: Border(bottom: BorderSide(color: AppColors.borderGray)),
          ),
          child: Row(
            children: [
              _TableCell(flex: 15, label: site.siteName, emphasized: true),
              _TableCell(flex: 16, label: site.siteCode),
              _TableCell(flex: 11, label: governor),
              _TableCell(flex: 9, label: site.brand),
              _TableCell(
                flex: 10,
                child: StatusBadge(status: site.status, compact: true),
              ),
              _TableCell(
                flex: 14,
                label: Formatters.power(site.installedPowerKw),
                numeric: true,
              ),
              _TableCell(
                flex: 15,
                label: Formatters.energy(site.dayEnergyKwh),
                numeric: true,
              ),
              _TableCell(
                flex: 15,
                label: Formatters.energy(site.totalEnergyKwh),
                numeric: true,
              ),
              _TableCell(
                flex: 12,
                child: TextButton(
                  onPressed: _open,
                  child: const Text('Voir détails', maxLines: 1),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _TableCell extends StatelessWidget {
  const _TableCell({
    required this.flex,
    this.label,
    this.child,
    this.header = false,
    this.numeric = false,
    this.emphasized = false,
  });
  final int flex;
  final String? label;
  final Widget? child;
  final bool header;
  final bool numeric;
  final bool emphasized;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      flex: flex,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Align(
          alignment: numeric ? Alignment.centerRight : Alignment.centerLeft,
          child:
              child ??
              Tooltip(
                message: label ?? '',
                child: Text(
                  label ?? '',
                  maxLines: header ? 2 : 1,
                  overflow: TextOverflow.ellipsis,
                  textAlign: numeric ? TextAlign.right : TextAlign.left,
                  style: TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: header ? 12 : 13,
                    fontWeight: header || emphasized
                        ? FontWeight.w800
                        : FontWeight.w600,
                  ),
                ),
              ),
        ),
      ),
    );
  }
}
