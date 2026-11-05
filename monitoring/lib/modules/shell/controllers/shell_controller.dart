import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../auth/controllers/auth_controller.dart';

class ShellController extends GetxController {
  final selectedIndex = 0.obs;

  @override
  void onInit() {
    super.onInit();
    syncFromRoute();
  }

  @override
  void onReady() {
    super.onReady();
    syncFromRoute();
  }

  void syncFromRoute([String? route]) {
    final requested = _indexForRoute(route ?? Get.currentRoute);
    final index = requested == 7 && !Get.find<AuthController>().canAccessPlanning ? 0 : requested;
    if (selectedIndex.value != index) {
      selectedIndex.value = index;
    }
  }

  void selectTab(int index) {
    final safeIndex = index.clamp(0, 7).toInt();
    if (safeIndex == 7 && !Get.find<AuthController>().canAccessPlanning) return;

    final targetRoute = switch (safeIndex) {
      1 => AppRoutes.sites,
      2 => AppRoutes.performance,
      3 => AppRoutes.alarms,
      4 => AppRoutes.aiReport,
      5 => AppRoutes.comparatif,
      6 => AppRoutes.swapDates,
      7 => AppRoutes.planning,
      _ => AppRoutes.dashboard,
    };

    final currentRoute = _cleanRoute(Get.currentRoute);

    if (selectedIndex.value != safeIndex) {
      selectedIndex.value = safeIndex;
    }

    if (currentRoute == targetRoute) return;
    if (currentRoute == AppRoutes.shell && targetRoute == AppRoutes.dashboard) return;

    Get.offNamed(targetRoute);
  }

  int _indexForRoute(String route) {
    final cleanRoute = _cleanRoute(route);

    if (cleanRoute == AppRoutes.sites || cleanRoute.startsWith('${AppRoutes.sites}/')) {
      return 1;
    }

    if (cleanRoute == AppRoutes.performance) {
      return 2;
    }

    if (cleanRoute == AppRoutes.alarms) {
      return 3;
    }

    if (cleanRoute == AppRoutes.aiReport) {
      return 4;
    }

    if (cleanRoute == AppRoutes.comparatif) {
      return 5;
    }

    if (cleanRoute == AppRoutes.swapDates) {
      return 6;
    }

    if (cleanRoute == AppRoutes.planning) return 7;
    return 0;
  }

  String _cleanRoute(String route) {
    if (route.isEmpty) return AppRoutes.dashboard;
    return route.split('?').first;
  }
}
