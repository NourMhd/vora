import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:monitoring/modules/ai_report/pages/ai_report_page.dart';
import 'package:monitoring/modules/alarms/pages/alarms_page.dart';
import 'package:monitoring/modules/comparatif/pages/comparatif_page.dart';
import 'package:monitoring/modules/performence/pages/performance_page.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/widgets/vora_logo.dart';
import '../../auth/controllers/auth_controller.dart';
import '../../dashboard/pages/dashboard_page.dart';
import '../../sites/pages/sites_page.dart';
import '../../swap_dates/pages/swap_dates_page.dart';
import '../controllers/shell_controller.dart';

class ShellPage extends GetView<ShellController> {
  const ShellPage({super.key});

  @override
  Widget build(BuildContext context) {
    controller.syncFromRoute();
    final auth = Get.find<AuthController>();

    final pages = const [
      DashboardPage(),
      SitesPage(),
      PerformancePage(),
      AlarmsPage(),
      AiReportPage(),
      ComparatifPage(),
      SwapDatesPage(),
    ];


    return Scaffold(
      body: LayoutBuilder(
        builder: (context, constraints) {
          final desktop = constraints.maxWidth >= 980;

          if (desktop) {
            return Row(
              children: [
                _SideNavigation(auth: auth),
                Expanded(
                  child: Obx(
                    () => IndexedStack(
                      index: controller.selectedIndex.value,
                      children: pages,
                    ),
                  ),
                ),
              ],
            );
          }

          return Obx(
            () => Scaffold(
              body: IndexedStack(
                index: controller.selectedIndex.value,
                children: pages,
              ),
              bottomNavigationBar: NavigationBar(
                selectedIndex: controller.selectedIndex.value,
                onDestinationSelected: controller.selectTab,
                destinations: const [
                  NavigationDestination(
                    icon: Icon(Icons.space_dashboard_outlined),
                    selectedIcon: Icon(Icons.space_dashboard_rounded),
                    label: 'Overview',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.domain_outlined),
                    selectedIcon: Icon(Icons.domain_rounded),
                    label: 'Sites',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.insights_outlined),
                    selectedIcon: Icon(Icons.insights_rounded),
                    label: 'Suivi production',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.notification_important_outlined),
                    selectedIcon: Icon(Icons.notification_important_rounded),
                    label: 'Alarme',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.auto_awesome_outlined),
                    selectedIcon: Icon(Icons.auto_awesome_rounded),
                    label: 'Rapport AI',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.compare_arrows_outlined),
                    selectedIcon: Icon(Icons.compare_arrows_rounded),
                    label: 'Comparatif',
                  ),
                  NavigationDestination(
                    icon: Icon(Icons.event_note_outlined),
                    selectedIcon: Icon(Icons.event_available_rounded),
                    label: 'Date swap',
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

class _SideNavigation extends StatelessWidget {
  const _SideNavigation({required this.auth});

  final AuthController auth;

  @override
  Widget build(BuildContext context) {
    final shell = Get.find<ShellController>();

    return Container(
      width: 280,
      padding: const EdgeInsets.fromLTRB(22, 24, 22, 20),
      decoration: const BoxDecoration(
        color: AppColors.midnightNavy,
        border: Border(right: BorderSide(color: Color(0x1AFFFFFF))),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const VoraLogo(dark: true, lockup: true, height: 65),
          const SizedBox(height: 34),

          Obx(
            () => _NavItem(
              icon: Icons.space_dashboard_rounded,
              label: 'Overview',
              selected: shell.selectedIndex.value == 0,
              onTap: () => shell.selectTab(0),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.domain_rounded,
              label: 'Sites',
              selected: shell.selectedIndex.value == 1,
              onTap: () => shell.selectTab(1),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.insights_rounded,
              label: 'Suivi production',
              selected: shell.selectedIndex.value == 2,
              onTap: () => shell.selectTab(2),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.notification_important_rounded,
              label: 'Alarme',
              selected: shell.selectedIndex.value == 3,
              onTap: () => shell.selectTab(3),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.auto_awesome_rounded,
              label: 'Rapport AI',
              selected: shell.selectedIndex.value == 4,
              onTap: () => shell.selectTab(4),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.compare_arrows_rounded,
              label: 'Comparatif',
              selected: shell.selectedIndex.value == 5,
              onTap: () => shell.selectTab(5),
            ),
          ),
          const SizedBox(height: 10),
          Obx(
            () => _NavItem(
              icon: Icons.event_available_rounded,
              label: 'Date swap',
              selected: shell.selectedIndex.value == 6,
              onTap: () => shell.selectTab(6),
            ),
          ),

          const Spacer(),

          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppColors.white.withOpacity(0.06),
              borderRadius: BorderRadius.circular(22),
              border: Border.all(color: AppColors.white.withOpacity(0.09)),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Obx(
                  () => Text(
                    auth.accessLabel,
                    style: const TextStyle(
                      color: Color(0x99FFFFFF),
                      fontSize: 10,
                      fontWeight: FontWeight.w900,
                      letterSpacing: 1.6,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Obx(
                  () => Text(
                    auth.username,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      color: AppColors.white,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                ),
                const SizedBox(height: 14),
                SizedBox(
                  width: double.infinity,
                  child: OutlinedButton.icon(
                    onPressed: auth.logout,
                    icon: const Icon(Icons.logout_rounded, size: 18),
                    label: const Text('Logout'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: AppColors.white,
                      side: BorderSide(color: AppColors.white.withOpacity(0.16)),
                    ),
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

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.label,
    required this.selected,
    this.onTap,
    this.disabled = false,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback? onTap;
  final bool disabled;

  @override
  Widget build(BuildContext context) {
    final color = selected ? AppColors.white : AppColors.white.withOpacity(disabled ? 0.32 : 0.68);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: disabled ? null : onTap,
        borderRadius: BorderRadius.circular(18),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
          decoration: BoxDecoration(
            color: selected ? AppColors.deepTeal : Colors.transparent,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(
              color: selected ? AppColors.deepTeal : AppColors.white.withOpacity(0.06),
            ),
          ),
          child: Row(
            children: [
              Icon(icon, color: color, size: 21),
              const SizedBox(width: 12),
              Text(
                label,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.w900,
                ),
              ),
              if (disabled) ...[
                const Spacer(),
                Text(
                  'soon',
                  style: TextStyle(
                    color: color,
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}