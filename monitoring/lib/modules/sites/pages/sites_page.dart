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

class _SitesContent extends StatelessWidget {
  const _SitesContent({required this.controller});
  final SitesController controller;
  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final sites = controller.filteredSites;
      if (sites.isEmpty) {
        return const PremiumCard(
          child: EmptyState(
            title: 'Aucun site trouvé',
            message:
                'Ajuste la recherche ou les filtres pour afficher les sites.',
            icon: Icons.search_off_rounded,
          ),
        );
      }
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4),
            child: LayoutBuilder(
              builder: (context, constraints) {
                final counter = Text(
                  '${sites.length} sites affichés',
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontWeight: FontWeight.w900,
                  ),
                );
                final exportButton = Obx(
                  () => Tooltip(
                    message:
                        'Télécharger les sites affichés, avec les filtres et le tri actuels',
                    child: FilledButton.icon(
                      onPressed: controller.isExporting.value
                          ? null
                          : controller.exportFilteredSites,
                      style: FilledButton.styleFrom(
                        backgroundColor: AppColors.deepTeal,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(
                          horizontal: 18,
                          vertical: 16,
                        ),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(14),
                        ),
                      ),
                      icon: controller.isExporting.value
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.download_rounded, size: 20),
                      label: Text(
                        controller.isExporting.value
                            ? 'Export en cours...'
                            : 'Exporter Excel',
                      ),
                    ),
                  ),
                );
                if (constraints.maxWidth < 480) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      counter,
                      const SizedBox(height: 12),
                      exportButton,
                    ],
                  );
                }
                return Row(
                  children: [
                    Expanded(child: counter),
                    exportButton,
                  ],
                );
              },
            ),
          ),
          const SizedBox(height: 12),
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 1280
                  ? 3
                  : constraints.maxWidth >= 820
                  ? 2
                  : 1;
              final spacing = 16.0;
              final width =
                  (constraints.maxWidth - spacing * (columns - 1)) / columns;
              return Wrap(
                spacing: spacing,
                runSpacing: spacing,
                children: sites.map((site) {
                  return SizedBox(
                    width: width,
                    child: _SiteCard(site: site),
                  );
                }).toList(),
              );
            },
          ),
        ],
      );
    });
  }
}

class _SiteCard extends StatelessWidget {
  const _SiteCard({required this.site});
  final SiteSummary site;
  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      onTap: () => Get.toNamed(
        AppRoutes.siteDetailsPath(brand: site.brand, siteCode: site.siteCode),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: AppColors.tealSoft,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: const Icon(
                  Icons.solar_power_rounded,
                  color: AppColors.deepTeal,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      site.siteName,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.midnightNavy,
                        fontSize: 17,
                        fontWeight: FontWeight.w900,
                        height: 1.1,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      site.siteCode,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
              StatusBadge(status: site.status, compact: true),
            ],
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              _InfoChip(label: 'Brand', value: site.brand),
              const SizedBox(width: 8),
              _InfoChip(
                label: 'Power',
                value: Formatters.power(site.installedPowerKw),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: AppColors.softWhite,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: AppColors.borderGray),
            ),
            child: Row(
              children: [
                Expanded(
                  child: _EnergyBlock(
                    label: 'Energy day',
                    value: Formatters.energy(site.dayEnergyKwh),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _EnergyBlock(
                    label: 'Energy total',
                    value: Formatters.energy(site.totalEnergyKwh),
                  ),
                ),
                const SizedBox(width: 8),
                const Icon(
                  Icons.arrow_forward_rounded,
                  color: AppColors.deepTeal,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _EnergyBlock extends StatelessWidget {
  const _EnergyBlock({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          label,
          style: const TextStyle(
            color: AppColors.mutedText,
            fontSize: 12,
            fontWeight: FontWeight.w800,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: AppColors.midnightNavy,
            fontSize: 18,
            fontWeight: FontWeight.w900,
            letterSpacing: -0.5,
          ),
        ),
      ],
    );
  }
}

class _InfoChip extends StatelessWidget {
  const _InfoChip({required this.label, required this.value});
  final String label;
  final String value;
  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: AppColors.borderGray),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              label,
              style: const TextStyle(
                color: AppColors.mutedText,
                fontSize: 10,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 3),
            Text(
              value,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.midnightNavy,
                fontWeight: FontWeight.w900,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
