import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../../app/theme/app_colors.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/utils/site_photo_picker.dart';
import '../../../core/widgets/energy_chart.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/metric_card.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../core/widgets/status_badge.dart';
import '../../../data/models/device_item.dart';
import '../../../data/models/site_description.dart';
import '../../../data/models/site_summary.dart';
import '../../auth/controllers/auth_controller.dart';
import '../controllers/site_details_controller.dart';

class SiteDetailsPage extends GetView<SiteDetailsController> {
  const SiteDetailsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.detail.value == null) {
          return const LoadingState(label: 'Chargement du site...');
        }

        if (controller.errorMessage.value != null && controller.detail.value == null) {
          return ErrorState(
            message: controller.errorMessage.value!,
            onRetry: controller.load,
          );
        }

        return LayoutBuilder(
          builder: (context, constraints) {
            final showLeftPanel = constraints.maxWidth >= 1100;

            return Row(
              children: [
                if (showLeftPanel) _SitesNavigator(controller: controller),
                Expanded(
                  child: CustomScrollView(
                    slivers: [
                      _SiteTopBar(
                        controller: controller,
                        showBack: !showLeftPanel,
                      ),
                      SliverPadding(
                        padding: const EdgeInsets.fromLTRB(26, 16, 26, 30),
                        sliver: SliverList(
                          delegate: SliverChildListDelegate([
                            _SiteHeaderCard(controller: controller),
                            const SizedBox(height: 18),
                            _SiteKpiGrid(controller: controller),
                            const SizedBox(height: 22),
                            _ChartsCard(controller: controller),
                            const SizedBox(height: 22),
                            _DevicesCard(controller: controller),
                          ]),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            );
          },
        );
      }),
    );
  }
}

class _SitesNavigator extends StatelessWidget {
  const _SitesNavigator({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 330,
      padding: const EdgeInsets.fromLTRB(18, 20, 18, 18),
      decoration: const BoxDecoration(
        color: AppColors.white,
        border: Border(right: BorderSide(color: AppColors.borderGray)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              IconButton(
                onPressed: () => Get.offNamed(AppRoutes.sites),
                icon: const Icon(Icons.arrow_back_rounded),
              ),
              const SizedBox(width: 8),
              const Expanded(
                child: Text(
                  'Site navigation',
                  style: TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 19,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            decoration: const InputDecoration(
              hintText: 'Quick search',
              prefixIcon: Icon(Icons.search_rounded),
            ),
            onChanged: (value) => controller.navSearch.value = value,
          ),
          const SizedBox(height: 16),
          Expanded(
            child: Obx(() {
              final sites = controller.navigationSites;

              if (sites.isEmpty) {
                return const EmptyState(
                  title: 'Aucun site',
                  message: 'Navigation indisponible.',
                  icon: Icons.domain_disabled_rounded,
                );
              }

              return ListView.separated(
                itemCount: sites.length,
                separatorBuilder: (_, __) => const SizedBox(height: 8),
                itemBuilder: (context, index) => _NavSiteTile(
                  controller: controller,
                  site: sites[index],
                  active: sites[index].siteCode == controller.siteCode &&
                      sites[index].brand.toUpperCase() == controller.brand.toUpperCase(),
                ),
              );
            }),
          ),
        ],
      ),
    );
  }
}

class _NavSiteTile extends StatelessWidget {
  const _NavSiteTile({
    required this.controller,
    required this.site,
    required this.active,
  });

  final SiteDetailsController controller;
  final SiteSummary site;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: active
          ? null
          : () {
              controller.openSite(
                brand: site.brand,
                siteCode: site.siteCode,
              );

              Get.offNamed(
                AppRoutes.siteDetailsPath(
                  brand: site.brand,
                  siteCode: site.siteCode,
                ),
              );
            },
      borderRadius: BorderRadius.circular(18),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 180),
        padding: const EdgeInsets.all(13),
        decoration: BoxDecoration(
          color: active ? AppColors.tealSoft : AppColors.softWhite,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(
            color: active ? AppColors.deepTeal.withOpacity(0.26) : AppColors.borderGray,
          ),
        ),
        child: Row(
          children: [
            Container(
              width: 38,
              height: 38,
              decoration: BoxDecoration(
                color: active ? AppColors.deepTeal : AppColors.white,
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(
                Icons.solar_power_rounded,
                color: active ? AppColors.white : AppColors.deepTeal,
                size: 20,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    site.siteName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.midnightNavy,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '${site.brand} · ${site.siteCode}',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.mutedText,
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ),
            StatusBadge(status: site.status, compact: true),
          ],
        ),
      ),
    );
  }
}

class _SiteTopBar extends StatelessWidget {
  const _SiteTopBar({
    required this.controller,
    required this.showBack,
  });

  final SiteDetailsController controller;
  final bool showBack;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      toolbarHeight: 82,
      backgroundColor: AppColors.softWhite.withOpacity(0.96),
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(18, 18, 26, 12),
        child: Row(
          children: [
            if (showBack) ...[
              IconButton.filledTonal(
                onPressed: () {
                  final previousRoute = Get.previousRoute;

                  if (previousRoute.isEmpty || previousRoute == Get.currentRoute) {
                    Get.offNamed(AppRoutes.sites);
                  } else {
                    Get.back();
                  }
                },
                icon: const Icon(Icons.arrow_back_rounded),
              ),
              const SizedBox(width: 10),
            ],
            const Expanded(
              child: Text(
                'Site Details',
                style: TextStyle(
                  color: AppColors.midnightNavy,
                  fontSize: 30,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -0.8,
                ),
              ),
            ),
            Obx(
              () => IconButton.filledTonal(
                tooltip: 'Mettre à jour maintenant',
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

class _SiteHeaderCard extends StatelessWidget {
  const _SiteHeaderCard({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    final detail = controller.detail.value;

    if (detail == null) return const SizedBox.shrink();

    return PremiumCard(
      padding: const EdgeInsets.all(26),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final compact = constraints.maxWidth < 850;
          final showDescriptionButton = _isTechnicianProjectUser();

          final title = Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                spacing: 10,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  StatusBadge(status: detail.status),
                  _HeaderChip(label: detail.brand),
                  _HeaderChip(label: detail.siteCode),
                ],
              ),
              const SizedBox(height: 16),
              Text(
                detail.siteName,
                style: const TextStyle(
                  color: AppColors.midnightNavy,
                  fontSize: 34,
                  fontWeight: FontWeight.w900,
                  letterSpacing: -1,
                  height: 1.05,
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Date de swap · ${Formatters.providerDate(detail.swapDate, brand: detail.brand)}',
                style: const TextStyle(
                  color: AppColors.midnightNavy,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                'Date de mise en service · ${Formatters.providerDate(detail.gridConnectionDate, brand: detail.brand)}',
                style: const TextStyle(
                  color: AppColors.mutedText,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          );

          final power = Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(
              color: AppColors.midnightNavy,
              borderRadius: BorderRadius.circular(24),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(
                  Icons.bolt_rounded,
                  color: AppColors.white,
                  size: 28,
                ),
                const SizedBox(height: 20),
                Text(
                  'Installed power',
                  style: TextStyle(
                    color: AppColors.white.withOpacity(0.64),
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  Formatters.power(detail.installedPowerKw),
                  style: const TextStyle(
                    color: AppColors.white,
                    fontSize: 30,
                    fontWeight: FontWeight.w900,
                    letterSpacing: -0.8,
                  ),
                ),
              ],
            ),
          );

          if (compact) {
            return Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                title,
              if (showDescriptionButton) ...[
  const SizedBox(height: 18),
  SizedBox(
    width: double.infinity,
    child: _SiteDescriptionActions(controller: controller),
  ),
],
                const SizedBox(height: 18),
                power,
              ],
            );
          }

          return Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: title),
           if (showDescriptionButton) ...[
  const SizedBox(width: 24),
  SizedBox(
    width: constraints.maxWidth >= 1100 ? 300 : 230,
    child: _SiteDescriptionActions(controller: controller),
  ),
],
const SizedBox(width: 12),
SizedBox(width: 270, child: power),
            ],
          );
        },
      ),
    );
  }
}

class _SiteDescriptionActions extends StatelessWidget {
  const _SiteDescriptionActions({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      mainAxisSize: MainAxisSize.min,
      children: [
        _SiteDescriptionButton(controller: controller),
        const SizedBox(height: 10),
        _ComparablePerformanceCard(controller: controller),
      ],
    );
  }
}

class _ComparablePerformanceCard extends StatelessWidget {
  const _ComparablePerformanceCard({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final description = controller.siteDescription.value;
      final isLoading = controller.isDescriptionLoading.value;

      if (isLoading && description == null) {
        return _ComparableCardShell(
          icon: Icons.sync_rounded,
          title: 'Comparaison gouvernorat',
          value: 'Chargement...',
          subtitle: 'Analyse du meilleur rendement comparable',
          backgroundColor: AppColors.softWhite,
          borderColor: AppColors.borderGray,
          iconColor: AppColors.deepTeal,
          rightTitle: 'Indice',
          rightValue: '...',
          rightSubtitle: 'Hors aujourd’hui',
        );
      }

      final currentYield = description?.currentSpecificYieldKwhPerKwp;
      final bestYield = description?.bestSpecificYieldKwhPerKwp;
      final performanceIndex = description?.performanceIndexPercent;

      if (currentYield == null || bestYield == null || bestYield <= 0) {
        return _ComparableCardShell(
          icon: Icons.info_outline_rounded,
          title: 'Comparaison gouvernorat',
          value: 'Indisponible',
          subtitle: 'Aucun meilleur site comparable trouvé',
          backgroundColor: AppColors.softWhite,
          borderColor: AppColors.borderGray,
          iconColor: AppColors.mutedText,
          rightTitle: 'Indice',
          rightValue: performanceIndex == null ? '—' : _formatPercentPlain(performanceIndex),
          rightSubtitle: description?.performanceIndexDate ?? 'Dernier jour monitorisé hors aujourd’hui',
        );
      }

      final comparisonPercent = description?.comparisonPercent ?? ((currentYield / bestYield) * 100);
      final gapPercent = description?.gapPercent ?? (100 - comparisonPercent).clamp(0, 100).toDouble();
      final displayedGap = gapPercent <= 0.05 ? 0.0 : -gapPercent;
      final isNormal = comparisonPercent >= 78;

      final governorate = description?.governorate?.toString();
      final comparableCount = description?.comparableSitesCount;
      final bestSiteName = description?.bestSiteName?.toString();

      final subtitleParts = <String>[
        if (governorate != null && governorate.trim().isNotEmpty) 'Gouvernorat $governorate',
        if (bestSiteName != null && bestSiteName.trim().isNotEmpty) 'Meilleur: $bestSiteName',
        description?.comparisonPeriod ?? 'dernier jour monitorisé hors aujourd’hui',
        if (comparableCount != null) '$comparableCount site(s) Healthy',
      ];

      return _ComparableCardShell(
        icon: isNormal ? Icons.verified_rounded : Icons.warning_amber_rounded,
        title: 'Écart ${_formatPercent(displayedGap)}',
        value: governorate == null || governorate.trim().isEmpty
            ? 'Par rapport au meilleur site'
            : 'Par rapport au meilleur site $governorate',
        subtitle: subtitleParts.join(' · '),
        backgroundColor: isNormal
            ? const Color(0xFFEAF8F0)
            : const Color(0xFFFFF4E5),
        borderColor: isNormal
            ? const Color(0xFFB7E4C7)
            : const Color(0xFFFFD8A8),
        iconColor: isNormal
            ? const Color(0xFF16A34A)
            : const Color(0xFFF59E0B),
        rightTitle: 'Indice',
        rightValue: performanceIndex == null ? '—' : _formatPercentPlain(performanceIndex),
        rightSubtitle: _performanceSubtitle(description),
      );
    });
  }
}

String _performanceSubtitle(SiteDescription? description) {
  final actual = description?.performanceActualKwh;
  final theoretical = description?.performanceTheoreticalKwh;
  if (actual != null && theoretical != null && theoretical > 0) {
    return '${_formatNumber(actual)} / ${_formatNumber(theoretical)} kWh';
  }
  return description?.performanceIndexDate ?? 'Dernier jour monitorisé hors aujourd’hui';
}

String _formatPercentPlain(num value) {
  return '${value.toStringAsFixed(1).replaceAll('.', ',')}%';
}

String _formatPercent(num value) {
  final sign = value > 0 ? '+' : '';
  return '$sign${value.toStringAsFixed(1).replaceAll('.', ',')}%';
}
class _ComparableCardShell extends StatelessWidget {
  const _ComparableCardShell({
    required this.icon,
    required this.title,
    required this.value,
    required this.subtitle,
    required this.backgroundColor,
    required this.borderColor,
    required this.iconColor,
    this.rightTitle,
    this.rightValue,
    this.rightSubtitle,
  });

  final IconData icon;
  final String title;
  final String value;
  final String subtitle;
  final Color backgroundColor;
  final Color borderColor;
  final Color iconColor;
  final String? rightTitle;
  final String? rightValue;
  final String? rightSubtitle;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: backgroundColor,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: borderColor),
        boxShadow: const [
          BoxShadow(
            color: AppColors.cardShadow,
            blurRadius: 14,
            offset: Offset(0, 8),
          ),
        ],
      ),
      child: Row(
        children: [
          Expanded(
            child: _ComparableCardSection(
              icon: icon,
              title: title,
              value: value,
              subtitle: subtitle,
              iconColor: iconColor,
              alignEnd: false,
            ),
          ),
          if (rightTitle != null || rightValue != null || rightSubtitle != null) ...[
            Container(
              width: 1.5,
              height: 62,
              margin: const EdgeInsets.symmetric(horizontal: 12),
              decoration: BoxDecoration(
                color: iconColor.withOpacity(0.75),
                borderRadius: BorderRadius.circular(999),
              ),
            ),
            Expanded(
              child: _ComparableCardSection(
                icon: Icons.speed_rounded,
                title: rightTitle ?? 'Indice',
                value: rightValue ?? '—',
                subtitle: rightSubtitle ?? 'Hors aujourd’hui',
                iconColor: iconColor,
                alignEnd: true,
                showIcon: false,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _ComparableCardSection extends StatelessWidget {
  const _ComparableCardSection({
    required this.icon,
    required this.title,
    required this.value,
    required this.subtitle,
    required this.iconColor,
    required this.alignEnd,
    this.showIcon = true,
  });

  final IconData icon;
  final String title;
  final String value;
  final String subtitle;
  final Color iconColor;
  final bool alignEnd;
  final bool showIcon;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: alignEnd ? MainAxisAlignment.end : MainAxisAlignment.start,
      children: [
        if (showIcon) ...[
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: AppColors.white,
              borderRadius: BorderRadius.circular(14),
            ),
            child: Icon(
              icon,
              color: iconColor,
              size: 21,
            ),
          ),
          const SizedBox(width: 10),
        ],
        Flexible(
          child: Column(
            crossAxisAlignment: alignEnd ? CrossAxisAlignment.end : CrossAxisAlignment.start,
            children: [
              Text(
                title,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: alignEnd ? TextAlign.right : TextAlign.left,
                style: TextStyle(
                  color: iconColor,
                  fontSize: 12,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: alignEnd ? TextAlign.right : TextAlign.left,
                style: const TextStyle(
                  color: AppColors.midnightNavy,
                  fontSize: 14,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                subtitle,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                textAlign: alignEnd ? TextAlign.right : TextAlign.left,
                style: const TextStyle(
                  color: AppColors.mutedText,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                  height: 1.25,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

bool _isTechnicianProjectUser() {
  if (!Get.isRegistered<AuthController>()) return false;
  return Get.find<AuthController>().user.value?.scope == 'PROJECT';
}

class _SiteDescriptionButton extends StatelessWidget {
  const _SiteDescriptionButton({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(
      () => ElevatedButton.icon(
        onPressed: controller.isDescriptionLoading.value
            ? null
            : () async {
                await controller.loadSiteDescription(force: true);
                if (controller.siteDescription.value == null) return;
                await Get.dialog<void>(
                  _SiteDescriptionDialog(controller: controller),
                  barrierDismissible: true,
                );
              },
        icon: controller.isDescriptionLoading.value
            ? const SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(strokeWidth: 2),
              )
            : const Icon(Icons.description_outlined),
        label: const Text('Description site'),
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.deepTeal,
          foregroundColor: AppColors.white,
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
          textStyle: const TextStyle(fontWeight: FontWeight.w900),
        ),
      ),
    );
  }
}

class _SiteDescriptionDialog extends StatefulWidget {
  const _SiteDescriptionDialog({required this.controller});

  final SiteDetailsController controller;

  @override
  State<_SiteDescriptionDialog> createState() => _SiteDescriptionDialogState();
}

class _SiteDescriptionDialogState extends State<_SiteDescriptionDialog> {
  late final TextEditingController _noteController;
  late final TextEditingController _orientationController;
  late List<_EditableSitePhoto> _photos;
  int _photoIndex = 0;

  @override
  void initState() {
    super.initState();
    final description = widget.controller.siteDescription.value;
    _noteController = TextEditingController(text: description?.note ?? '');
    _orientationController = TextEditingController(
      text: description?.orientationDeg == null
          ? ''
          : _formatNumber(description!.orientationDeg!),
    );
    _photos = description?.photos
            .map(_EditableSitePhoto.fromExisting)
            .toList() ??
        <_EditableSitePhoto>[];
  }

  @override
  void dispose() {
    _noteController.dispose();
    _orientationController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 780, maxHeight: 860),
        child: Obx(() {
          final description = widget.controller.siteDescription.value;
          if (description == null) {
            return const Padding(
              padding: EdgeInsets.all(32),
              child: LoadingState(label: 'Chargement de la description...'),
            );
          }

          return SingleChildScrollView(
            padding: const EdgeInsets.all(26),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            description.siteName,
                            style: const TextStyle(
                              color: AppColors.midnightNavy,
                              fontSize: 28,
                              fontWeight: FontWeight.w900,
                              letterSpacing: -0.6,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            description.brand,
                            style: const TextStyle(
                              color: AppColors.mutedText,
                              fontWeight: FontWeight.w800,
                            ),
                          ),
                        ],
                      ),
                    ),
                    IconButton.filledTonal(
                      onPressed: () => Get.back(),
                      icon: const Icon(Icons.close_rounded),
                    ),
                  ],
                ),
                const SizedBox(height: 22),
                _DescriptionNotice(description: description),
                const SizedBox(height: 22),
                _PhotoEditor(
                  photos: _photos,
                  selectedIndex: _photoIndex,
                  onPick: () async {
                    final picked = await pickSitePhoto();
                    if (picked == null) return;
                    setState(() {
                      _photos.add(_EditableSitePhoto.fromPicked(picked));
                      _photoIndex = _photos.length - 1;
                    });
                  },
                  onRemove: (index) {
                    if (index < 0 || index >= _photos.length) return;
                    setState(() {
                      _photos.removeAt(index);
                      if (_photos.isEmpty) {
                        _photoIndex = 0;
                      } else if (_photoIndex >= _photos.length) {
                        _photoIndex = _photos.length - 1;
                      }
                    });
                  },
                  onSelect: (index) {
                    if (index < 0 || index >= _photos.length) return;
                    setState(() => _photoIndex = index);
                  },
                  onPrevious: () {
                    if (_photos.isEmpty) return;
                    setState(() {
                      _photoIndex = (_photoIndex - 1).clamp(0, _photos.length - 1).toInt();
                    });
                  },
                  onNext: () {
                    if (_photos.isEmpty) return;
                    setState(() {
                      _photoIndex = (_photoIndex + 1).clamp(0, _photos.length - 1).toInt();
                    });
                  },
                ),
                const SizedBox(height: 22),
                TextField(
                  controller: _noteController,
                  minLines: 4,
                  maxLines: 8,
                  decoration: InputDecoration(
                    labelText: 'Note du site',
                    hintText: 'Écrire une note technique pour ce site...',
                    filled: true,
                    fillColor: AppColors.softWhite,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(18),
                      borderSide: const BorderSide(color: AppColors.borderGray),
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(18),
                      borderSide: const BorderSide(color: AppColors.borderGray),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                TextField(
                  controller: _orientationController,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(
                    labelText: 'Orientation du site (degré)',
                    hintText: 'Exemple: 180',
                    suffixText: '°',
                    filled: true,
                    fillColor: AppColors.softWhite,
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(18),
                      borderSide: const BorderSide(color: AppColors.borderGray),
                    ),
                    enabledBorder: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(18),
                      borderSide: const BorderSide(color: AppColors.borderGray),
                    ),
                  ),
                ),
                const SizedBox(height: 22),
                Row(
                  children: [
                    Expanded(
                      child: OutlinedButton(
                        onPressed: () => Get.back(),
                        style: OutlinedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                        ),
                        child: const Text('Annuler'),
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Obx(
                        () => ElevatedButton.icon(
                          onPressed: widget.controller.isDescriptionSaving.value
                              ? null
                              : () async {
                                  await widget.controller.saveSiteDescription(
                                    note: _noteController.text,
                                    orientationDeg: _orientationController.text,
                                    retainedPhotoIds: _photos
                                        .where((photo) => photo.isExisting && photo.id != 'legacy-photo')
                                        .map((photo) => photo.id!)
                                        .toList(),
                                    photosToAdd: _photos
                                        .where((photo) => photo.isNew)
                                        .map((photo) => MultipartFilePayload(
                                              fieldName: 'photos',
                                              bytes: photo.bytes!,
                                              fileName: photo.filename,
                                            ))
                                        .toList(),
                                  );
                                },
                          icon: widget.controller.isDescriptionSaving.value
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.save_outlined),
                          label: const Text('Enregistrer'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppColors.deepTeal,
                            foregroundColor: AppColors.white,
                            padding: const EdgeInsets.symmetric(vertical: 16),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
                            textStyle: const TextStyle(fontWeight: FontWeight.w900),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          );
        }),
      ),
    );
  }
}

class _DescriptionNotice extends StatelessWidget {
  const _DescriptionNotice({required this.description});

  final SiteDescription description;

  @override
  Widget build(BuildContext context) {
    final currentYield = description.currentSpecificYieldKwhPerKwp;
    final bestYield = description.bestSpecificYieldKwhPerKwp;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: AppColors.tealSoft,
        borderRadius: BorderRadius.circular(22),
        border: Border.all(color: AppColors.deepTeal.withOpacity(0.18)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Icon(Icons.insights_rounded, color: AppColors.deepTeal),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  description.notice?.toString() ?? 'Comparaison indisponible pour le moment.',
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                    height: 1.35,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: [
              _NoticePill(
                label: 'Date de swap',
                value: Formatters.providerDate(description.swapDate, brand: description.brand),
              ),
              _NoticePill(
                label: 'Mise en service',
                value: Formatters.providerDate(description.gridConnectionDate, brand: description.brand),
              ),
              if (description.totalEnergyKwh != null)
                _NoticePill(
                  label: 'Production totale',
                  value: Formatters.energy(description.totalEnergyKwh, decimals: 2),
                ),
              if (currentYield != null)
                _NoticePill(label: 'Rendement site', value: '${_formatNumber(currentYield)} kWh/kWc'),
              if (bestYield != null)
                _NoticePill(label: 'Rendement meilleur', value: '${_formatNumber(bestYield)} kWh/kWc'),
              if (description.bestSiteName != null)
                _NoticePill(
                  label: 'Meilleur site',
                  value: description.bestSiteCode == null
                      ? description.bestSiteName.toString()
                      : '${description.bestSiteName}',
                ),
              if (description.governorate != null)
                _NoticePill(label: 'Gouvernorat', value: description.governorate.toString()),
              if (description.comparableSitesCount != null)
                _NoticePill(label: 'Comparables', value: description.comparableSitesCount.toString()),
              if (description.comparisonPercent != null)
                _NoticePill(
                  label: 'Comparaison',
                  value: _formatPercentPlain(description.comparisonPercent!),
                ),
              if (description.gapPercent != null)
                _NoticePill(
                  label: 'Écart meilleur',
                  value: _formatPercent(-description.gapPercent!),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _NoticePill extends StatelessWidget {
  const _NoticePill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: AppColors.white,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: RichText(
        text: TextSpan(
          style: const TextStyle(color: AppColors.slateGraphite, fontWeight: FontWeight.w700),
          children: [
            TextSpan(text: '$label: '),
            TextSpan(
              text: value,
              style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900),
            ),
          ],
        ),
      ),
    );
  }
}

class _EditableSitePhoto {
  const _EditableSitePhoto({
    this.id,
    this.dataUrl,
    this.bytes,
    required this.filename,
  });

  final String? id;
  final String? dataUrl;
  final Uint8List? bytes;
  final String filename;

  bool get isExisting => id != null && id!.isNotEmpty && bytes == null;
  bool get isNew => bytes != null && bytes!.isNotEmpty;

  factory _EditableSitePhoto.fromExisting(SiteDescriptionPhoto photo) {
    return _EditableSitePhoto(
      id: photo.id,
      dataUrl: photo.dataUrl,
      filename: photo.filename,
    );
  }

  factory _EditableSitePhoto.fromPicked(PickedSitePhoto photo) {
    return _EditableSitePhoto(
      bytes: photo.bytes,
      filename: photo.filename,
    );
  }
}

class _PhotoEditor extends StatelessWidget {
  const _PhotoEditor({
    required this.photos,
    required this.selectedIndex,
    required this.onPick,
    required this.onRemove,
    required this.onSelect,
    required this.onPrevious,
    required this.onNext,
  });

  final List<_EditableSitePhoto> photos;
  final int selectedIndex;
  final VoidCallback onPick;
  final ValueChanged<int> onRemove;
  final ValueChanged<int> onSelect;
  final VoidCallback onPrevious;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    final hasPhotos = photos.isNotEmpty;
    final safeIndex = hasPhotos ? selectedIndex.clamp(0, photos.length - 1).toInt() : 0;
    final selected = hasPhotos ? photos[safeIndex] : null;

    Widget image;
    if (selected?.bytes != null && selected!.bytes!.isNotEmpty) {
      image = Image.memory(
        selected.bytes!,
        fit: BoxFit.contain,
        width: double.infinity,
        height: double.infinity,
      );
    } else if (selected?.dataUrl != null && selected!.dataUrl!.isNotEmpty) {
      image = Image.network(
        selected.dataUrl!,
        fit: BoxFit.contain,
        width: double.infinity,
        height: double.infinity,
      );
    } else {
      image = Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: const [
          Icon(Icons.add_photo_alternate_outlined, size: 52, color: AppColors.deepTeal),
          SizedBox(height: 12),
          Text(
            'Aucune photo pour ce site',
            style: TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900),
          ),
          SizedBox(height: 4),
          Text(
            'Clique sur ajouter pour importer une ou plusieurs photos.',
            style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700),
          ),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            const Expanded(
              child: Text(
                'Photos du site',
                style: TextStyle(
                  color: AppColors.midnightNavy,
                  fontSize: 18,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
            OutlinedButton.icon(
              onPressed: onPick,
              icon: const Icon(Icons.add_photo_alternate_outlined),
              label: const Text('Ajouter une photo'),
            ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          hasPhotos
              ? '${safeIndex + 1}/${photos.length} · ${selected!.filename}'
              : 'Aucune photo sélectionnée',
          style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700),
        ),
        const SizedBox(height: 12),
        ClipRRect(
          borderRadius: BorderRadius.circular(22),
          child: Container(
            height: 320,
            width: double.infinity,
            decoration: BoxDecoration(
              color: AppColors.softWhite,
              border: Border.all(color: AppColors.borderGray),
              borderRadius: BorderRadius.circular(22),
            ),
            child: Stack(
              children: [
                Positioned.fill(child: Center(child: image)),
                if (hasPhotos) ...[
                  Positioned(
                    left: 10,
                    top: 0,
                    bottom: 0,
                    child: Center(
                      child: IconButton.filledTonal(
                        onPressed: safeIndex == 0 ? null : onPrevious,
                        icon: const Icon(Icons.chevron_left_rounded),
                      ),
                    ),
                  ),
                  Positioned(
                    right: 10,
                    top: 0,
                    bottom: 0,
                    child: Center(
                      child: IconButton.filledTonal(
                        onPressed: safeIndex >= photos.length - 1 ? null : onNext,
                        icon: const Icon(Icons.chevron_right_rounded),
                      ),
                    ),
                  ),
                  Positioned(
                    right: 12,
                    top: 12,
                    child: IconButton.filled(
                      style: IconButton.styleFrom(
                        backgroundColor: AppColors.faulty,
                        foregroundColor: AppColors.white,
                      ),
                      onPressed: () => onRemove(safeIndex),
                      icon: const Icon(Icons.delete_outline_rounded),
                      tooltip: 'Supprimer cette photo',
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
        if (photos.length > 1) ...[
          const SizedBox(height: 10),
          SizedBox(
            height: 58,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: photos.length,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                final photo = photos[index];
                final selectedThumb = index == safeIndex;
                final thumb = photo.bytes != null
                    ? Image.memory(photo.bytes!, fit: BoxFit.cover)
                    : Image.network(photo.dataUrl ?? '', fit: BoxFit.cover);

                return InkWell(
                  onTap: () => onSelect(index),
                  borderRadius: BorderRadius.circular(14),
                  child: Container(
                    width: 58,
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: selectedThumb ? AppColors.deepTeal : AppColors.borderGray,
                        width: selectedThumb ? 2 : 1,
                      ),
                    ),
                    clipBehavior: Clip.antiAlias,
                    child: thumb,
                  ),
                );
              },
            ),
          ),
        ],
      ],
    );
  }
}

String _formatNumber(num value) {
  if ((value - value.round()).abs() < 0.001) {
    return value.round().toString();
  }
  return value.toStringAsFixed(2).replaceAll('.', ',');
}

class _HeaderChip extends StatelessWidget {
  const _HeaderChip({required this.label});

  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.softWhite,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Text(
        label,
        style: const TextStyle(
          color: AppColors.slateGraphite,
          fontWeight: FontWeight.w900,
          fontSize: 12,
        ),
      ),
    );
  }
}

class _SiteKpiGrid extends StatelessWidget {
  const _SiteKpiGrid({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    final detail = controller.detail.value;

    if (detail == null) return const SizedBox.shrink();

    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 1060
            ? 4
            : constraints.maxWidth >= 680
                ? 2
                : 1;

        final spacing = 16.0;
        final width = (constraints.maxWidth - spacing * (columns - 1)) / columns;

        final cards = [
          MetricCard(
            title: 'Today',
            value: Formatters.energy(detail.dayEnergyKwh),
            icon: Icons.wb_sunny_rounded,
          ),
          MetricCard(
            title: 'Month',
            value: Formatters.energy(detail.monthEnergyKwh),
            icon: Icons.calendar_view_month_rounded,
          ),
          MetricCard(
            title: 'Year',
            value: Formatters.energy(detail.yearEnergyKwh),
            icon: Icons.stacked_line_chart_rounded,
          ),
          MetricCard(
            title: 'Total',
            value: Formatters.energy(detail.totalEnergyKwh),
            icon: Icons.all_inclusive_rounded,
          ),
        ];

        return Wrap(
          spacing: spacing,
          runSpacing: spacing,
          children: cards.map((card) => SizedBox(width: width, child: card)).toList(),
        );
      },
    );
  }
}

class _ChartsCard extends StatelessWidget {
  const _ChartsCard({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 1180;

              return Obx(() {
                final showKpi = controller.shouldShowSelectedPeriodKpi;
                final isHistoryLoading = controller.isHistoryLoading.value;
                final mode = controller.chartMode.value;
                final titleText = controller.activeHistoryTitle;
                final periodLabel = controller.chartPeriodLabel;
                final canMoveNext = controller.canMoveToNextPeriod;

                final title = Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Energy history',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w900,
                        color: AppColors.midnightNavy,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      titleText,
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                );

                final controls = Wrap(
                  spacing: 10,
                  runSpacing: 10,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  alignment: WrapAlignment.end,
                  children: [
                    SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(value: 'day', label: Text('Day')),
                        ButtonSegment(value: 'month', label: Text('Month')),
                        ButtonSegment(value: 'year', label: Text('Year')),
                      ],
                      selected: {mode},
                      onSelectionChanged: isHistoryLoading
                          ? null
                          : (value) => controller.loadChartMode(value.first),
                      showSelectedIcon: false,
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: AppColors.softWhite,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(color: AppColors.borderGray),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton.filledTonal(
                            tooltip: 'Période précédente',
                            onPressed: isHistoryLoading
                                ? null
                                : () => controller.shiftChartPeriod(-1),
                            icon: const Icon(Icons.chevron_left_rounded),
                          ),
                          Material(
                            color: Colors.transparent,
                            child: InkWell(
                              borderRadius: BorderRadius.circular(999),
                              onTap: isHistoryLoading
                                  ? null
                                  : () => _openChartPeriodPicker(context, controller),
                              child: Padding(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: 10,
                                  vertical: 8,
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Text(
                                      periodLabel,
                                      style: const TextStyle(
                                        color: AppColors.midnightNavy,
                                        fontWeight: FontWeight.w900,
                                      ),
                                    ),
                                    const SizedBox(width: 6),
                                    const Icon(
                                      Icons.calendar_month_rounded,
                                      size: 18,
                                      color: AppColors.mutedText,
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          ),
                          IconButton.filledTonal(
                            tooltip: 'Période suivante',
                            onPressed: isHistoryLoading || !canMoveNext
                                ? null
                                : () => controller.shiftChartPeriod(1),
                            icon: const Icon(Icons.chevron_right_rounded),
                          ),
                        ],
                      ),
                    ),
                  ],
                );

                if (compact) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      title,
                      if (showKpi) ...[
                        const SizedBox(height: 14),
                        _SelectedPeriodKpi(controller: controller),
                      ],
                      const SizedBox(height: 16),
                      controls,
                    ],
                  );
                }

                return Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(flex: 3, child: title),
                    if (showKpi) ...[
                      const SizedBox(width: 18),
                      SizedBox(
                        width: 360,
                        child: _SelectedPeriodKpi(controller: controller),
                      ),
                    ],
                    const SizedBox(width: 18),
                    Flexible(
                      flex: 4,
                      child: Align(
                        alignment: Alignment.topRight,
                        child: controls,
                      ),
                    ),
                  ],
                );
              });
            },
          ),
          const SizedBox(height: 20),
          Obx(() {
            if (controller.isHistoryLoading.value) {
              return const SizedBox(
                height: 320,
                child: LoadingState(label: 'Chargement historique...'),
              );
            }

            return EnergyLineChart(
              key: ValueKey(controller.chartIdentity),
              points: controller.activeHistory,
              mode: controller.chartMode.value,
              height: 320,
            );
          }),
        ],
      ),
    );
  }
}

Future<void> _openChartPeriodPicker(
  BuildContext context,
  SiteDetailsController controller,
) async {
  final mode = controller.chartMode.value;

  DateTime? selected;

  if (mode == 'day') {
    selected = await showDatePicker(
      context: context,
      initialDate: controller.chartPickerInitialDate,
      firstDate: controller.chartPickerFirstDate,
      lastDate: controller.chartPickerLastDate,
      helpText: 'Choisir un jour',
      cancelText: 'Annuler',
      confirmText: 'Choisir',
    );
  } else if (mode == 'month') {
    selected = await _showMonthPicker(
      context: context,
      initialDate: controller.chartPickerInitialDate,
      firstDate: controller.chartPickerFirstDate,
      lastDate: controller.chartPickerLastDate,
    );
  } else {
    selected = await _showYearPicker(
      context: context,
      initialDate: controller.chartPickerInitialDate,
      firstDate: controller.chartPickerFirstDate,
      lastDate: controller.chartPickerLastDate,
    );
  }

  if (selected != null) {
    await controller.selectChartPeriod(selected);
  }
}

Future<DateTime?> _showMonthPicker({
  required BuildContext context,
  required DateTime initialDate,
  required DateTime firstDate,
  required DateTime lastDate,
}) {
  const months = [
    'Janv.',
    'Févr.',
    'Mars',
    'Avr.',
    'Mai',
    'Juin',
    'Juil.',
    'Août',
    'Sept.',
    'Oct.',
    'Nov.',
    'Déc.',
  ];

  var displayedYear = initialDate.year;

  return showDialog<DateTime>(
    context: context,
    builder: (dialogContext) {
      return StatefulBuilder(
        builder: (context, setState) {
          final canGoPrevious = displayedYear > firstDate.year;
          final canGoNext = displayedYear < lastDate.year;

          return AlertDialog(
            title: const Text(
              'Choisir un mois',
              style: TextStyle(
                fontWeight: FontWeight.w900,
                color: AppColors.midnightNavy,
              ),
            ),
            content: SizedBox(
              width: 360,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Row(
                    children: [
                      IconButton(
                        onPressed: canGoPrevious
                            ? () => setState(() => displayedYear--)
                            : null,
                        icon: const Icon(Icons.chevron_left_rounded),
                      ),
                      Expanded(
                        child: Center(
                          child: Text(
                            displayedYear.toString(),
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w900,
                              color: AppColors.midnightNavy,
                            ),
                          ),
                        ),
                      ),
                      IconButton(
                        onPressed: canGoNext
                            ? () => setState(() => displayedYear++)
                            : null,
                        icon: const Icon(Icons.chevron_right_rounded),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),
                  GridView.builder(
                    shrinkWrap: true,
                    itemCount: 12,
                    physics: const NeverScrollableScrollPhysics(),
                    gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 3,
                      crossAxisSpacing: 10,
                      mainAxisSpacing: 10,
                      childAspectRatio: 2.4,
                    ),
                    itemBuilder: (context, index) {
                      final month = index + 1;
                      final candidate = DateTime(displayedYear, month, 1);

                      final minMonth = DateTime(firstDate.year, firstDate.month, 1);
                      final maxMonth = DateTime(lastDate.year, lastDate.month, 1);

                      final disabled =
                          candidate.isBefore(minMonth) || candidate.isAfter(maxMonth);

                      final selected = initialDate.year == displayedYear &&
                          initialDate.month == month;

                      return OutlinedButton(
                        onPressed: disabled
                            ? null
                            : () => Navigator.of(dialogContext).pop(candidate),
                        style: OutlinedButton.styleFrom(
                          backgroundColor:
                              selected ? AppColors.midnightNavy : AppColors.white,
                          foregroundColor:
                              selected ? AppColors.white : AppColors.midnightNavy,
                          side: BorderSide(
                            color: selected
                                ? AppColors.midnightNavy
                                : AppColors.borderGray,
                          ),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(14),
                          ),
                        ),
                        child: Text(
                          months[index],
                          style: const TextStyle(
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                      );
                    },
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(),
                child: const Text('Annuler'),
              ),
            ],
          );
        },
      );
    },
  );
}

Future<DateTime?> _showYearPicker({
  required BuildContext context,
  required DateTime initialDate,
  required DateTime firstDate,
  required DateTime lastDate,
}) {
  final years = List<int>.generate(
    lastDate.year - firstDate.year + 1,
    (index) => lastDate.year - index,
  );

  return showDialog<DateTime>(
    context: context,
    builder: (dialogContext) {
      return AlertDialog(
        title: const Text(
          'Choisir une année',
          style: TextStyle(
            fontWeight: FontWeight.w900,
            color: AppColors.midnightNavy,
          ),
        ),
        content: SizedBox(
          width: 360,
          height: 360,
          child: GridView.builder(
            itemCount: years.length,
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              crossAxisSpacing: 10,
              mainAxisSpacing: 10,
              childAspectRatio: 2.2,
            ),
            itemBuilder: (context, index) {
              final year = years[index];
              final selected = year == initialDate.year;

              return OutlinedButton(
                onPressed: () {
                  Navigator.of(dialogContext).pop(DateTime(year, 1, 1));
                },
                style: OutlinedButton.styleFrom(
                  backgroundColor:
                      selected ? AppColors.midnightNavy : AppColors.white,
                  foregroundColor:
                      selected ? AppColors.white : AppColors.midnightNavy,
                  side: BorderSide(
                    color: selected
                        ? AppColors.midnightNavy
                        : AppColors.borderGray,
                  ),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(14),
                  ),
                ),
                child: Text(
                  year.toString(),
                  style: const TextStyle(
                    fontWeight: FontWeight.w900,
                  ),
                ),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Annuler'),
          ),
        ],
      );
    },
  );
}

class _SelectedPeriodKpi extends StatelessWidget {
  const _SelectedPeriodKpi({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final isLoading = controller.isHistoryLoading.value;
      final value = controller.selectedPeriodProductionLabel;
      final subtitle = controller.selectedPeriodKpiTitle;

      return AnimatedSwitcher(
        duration: const Duration(milliseconds: 180),
        child: Container(
          key: ValueKey('${controller.chartIdentity}-$value-$isLoading'),
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 16),
          decoration: BoxDecoration(
            color: AppColors.midnightNavy,
            borderRadius: BorderRadius.circular(22),
            boxShadow: const [
              BoxShadow(
                color: AppColors.cardShadow,
                blurRadius: 18,
                offset: Offset(0, 10),
              ),
            ],
          ),
          child: Row(
            children: [
              Container(
                width: 46,
                height: 46,
                decoration: BoxDecoration(
                  color: AppColors.deepTeal,
                  borderRadius: BorderRadius.circular(16),
                ),
                child: isLoading
                    ? const Padding(
                        padding: EdgeInsets.all(12),
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: AppColors.white,
                        ),
                      )
                    : const Icon(
                        Icons.bolt_rounded,
                        color: AppColors.white,
                      ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: AppColors.white.withOpacity(0.68),
                        fontWeight: FontWeight.w800,
                        fontSize: 12,
                      ),
                    ),
                    const SizedBox(height: 5),
                    Text(
                      value,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.white,
                        fontSize: 25,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -0.7,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      );
    });
  }
}

class _DevicesCard extends StatelessWidget {
  const _DevicesCard({required this.controller});

  final SiteDetailsController controller;

  @override
  Widget build(BuildContext context) {
    final devices = controller.devices;

    return PremiumCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Devices',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.w900,
                        color: AppColors.midnightNavy,
                      ),
                    ),
                    SizedBox(height: 6),
                    Text(
                      'Vue équipement du site.',
                      style: TextStyle(
                        color: AppColors.mutedText,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
              Obx(() => _HeaderChip(label: '${controller.devices.length} devices')),
            ],
          ),
          const SizedBox(height: 18),
          Obx(() {
            if (devices.isEmpty) {
              return const EmptyState(
                title: 'Aucun device',
                message: 'Aucun équipement n’est disponible pour ce site.',
                icon: Icons.developer_board_off_rounded,
              );
            }

            return LayoutBuilder(
              builder: (context, constraints) {
                final compact = constraints.maxWidth < 720;

                if (compact) {
                  return Column(
                    children: devices.map((device) => _DeviceTile(device: device)).toList(),
                  );
                }

                return ClipRRect(
                  borderRadius: BorderRadius.circular(18),
                  child: Table(
                    columnWidths: const {
                      0: FlexColumnWidth(2),
                      1: FlexColumnWidth(1.2),
                      2: FlexColumnWidth(1.3),
                      3: FlexColumnWidth(1.3),
                    },
                    children: [
                      const TableRow(
                        decoration: BoxDecoration(color: AppColors.softWhite),
                        children: [
                          _DeviceCell('Device', header: true),
                          _DeviceCell('Type', header: true),
                          _DeviceCell('Model', header: true),
                          _DeviceCell('ESN', header: true),
                        ],
                      ),
                      ...devices.map(
                        (device) => TableRow(
                          decoration: const BoxDecoration(color: AppColors.white),
                          children: [
                            _DeviceCell(device.devName),
                            _DeviceCell(device.devTypeId?.toString() ?? '—'),
                            _DeviceCell(device.model.isEmpty ? '—' : device.model),
                            _DeviceCell(device.esnCode.isEmpty ? '—' : device.esnCode),
                          ],
                        ),
                      ),
                    ],
                  ),
                );
              },
            );
          }),
        ],
      ),
    );
  }
}

class _DeviceTile extends StatelessWidget {
  const _DeviceTile({required this.device});

  final DeviceItem device;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border.all(color: AppColors.borderGray),
        borderRadius: BorderRadius.circular(18),
      ),
      child: Row(
        children: [
          Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(
              color: AppColors.tealSoft,
              borderRadius: BorderRadius.circular(14),
            ),
            child: const Icon(
              Icons.developer_board_rounded,
              color: AppColors.deepTeal,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  device.devName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  device.esnCode.isEmpty ? device.devDn : device.esnCode,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DeviceCell extends StatelessWidget {
  const _DeviceCell(this.value, {this.header = false});

  final String value;
  final bool header;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: AppColors.borderGray)),
      ),
      child: Text(
        value,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: TextStyle(
          color: header ? AppColors.mutedText : AppColors.midnightNavy,
          fontWeight: header ? FontWeight.w900 : FontWeight.w700,
          fontSize: header ? 12 : 13,
        ),
      ),
    );
  }
}