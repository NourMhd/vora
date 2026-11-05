import 'package:get/get.dart';

import '../../data/repositories/monitoring_repository.dart';
import '../../modules/auth/pages/login_page.dart';
import '../../modules/shell/pages/shell_page.dart';
import '../../modules/site_details/controllers/site_details_controller.dart';
import '../../modules/site_details/pages/site_details_page.dart';
import 'app_routes.dart';

class AppPages {
  static final pages = <GetPage<dynamic>>[
    GetPage(
      name: AppRoutes.login,
      page: () => const LoginPage(),
    ),
    GetPage(
      name: AppRoutes.shell,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.dashboard,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.sites,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.performance,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.alarms,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.aiReport,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.comparatif,
      page: () => const ShellPage(),
    ),
    GetPage(
      name: AppRoutes.swapDates,
      page: () => const ShellPage(),
    ),
    GetPage(name: AppRoutes.planning, page: () => const ShellPage()),
    GetPage(
      name: AppRoutes.siteDetails,
      page: () => const SiteDetailsPage(),
      binding: BindingsBuilder(() {
        final args = Get.arguments;

        final rawBrand = Get.parameters['brand'] ??
            (args is Map ? args['brand']?.toString() : null) ??
            'HUAWEI';

        final rawSiteCode = Get.parameters['siteCode'] ??
            (args is Map ? args['siteCode']?.toString() : null) ??
            '';

        final brand = Uri.decodeComponent(rawBrand);
        final siteCode = Uri.decodeComponent(rawSiteCode);

        if (Get.isRegistered<SiteDetailsController>()) {
          Get.find<SiteDetailsController>().openSite(
            brand: brand,
            siteCode: siteCode,
          );
        } else {
          Get.put<SiteDetailsController>(
            SiteDetailsController(
              repository: Get.find<MonitoringRepository>(),
              brand: brand,
              siteCode: siteCode,
            ),
            permanent: true,
          );
        }
      }),
    ),
  ];
}