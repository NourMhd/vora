import 'package:get/get.dart';
import 'package:monitoring/modules/ai_report/controllers/ai_report_controller.dart';
import 'package:monitoring/modules/alarms/controllers/alarms_controller.dart';
import 'package:monitoring/modules/performence/controllers/performence_controller.dart';

import '../../data/repositories/auth_repository.dart';
import '../../data/repositories/monitoring_repository.dart';
import '../../modules/auth/controllers/auth_controller.dart';
import '../../modules/comparatif/controllers/comparatif_controller.dart';
import '../../modules/dashboard/controllers/dashboard_controller.dart';
import '../../modules/shell/controllers/shell_controller.dart';
import '../../modules/sites/controllers/sites_controller.dart';
import '../../modules/swap_dates/controllers/swap_dates_controller.dart';
import '../network/api_client.dart';

class AppBinding extends Bindings {
  @override
  void dependencies() {
    if (!Get.isRegistered<ApiClient>()) {
      Get.put(ApiClient(), permanent: true);
    }

    if (!Get.isRegistered<AuthRepository>()) {
      Get.put(AuthRepository(Get.find<ApiClient>()), permanent: true);
    }

    if (!Get.isRegistered<MonitoringRepository>()) {
      Get.put(MonitoringRepository(Get.find<ApiClient>()), permanent: true);
    }

    if (!Get.isRegistered<AuthController>()) {
      Get.put(
        AuthController(
          repository: Get.find<AuthRepository>(),
          storage: Get.find(),
          apiClient: Get.find<ApiClient>(),
        ),
        permanent: true,
      );
    }

    Get.lazyPut<ShellController>(() => ShellController(), fenix: true);

    Get.lazyPut<DashboardController>(
      () => DashboardController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );

    Get.lazyPut<SitesController>(
      () => SitesController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );


    Get.lazyPut<PerformanceController>(
      () => PerformanceController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );

    Get.lazyPut<ComparatifController>(
      () => ComparatifController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );


    Get.lazyPut<AlarmsController>(
      () => AlarmsController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );

    Get.lazyPut<AiReportController>(
      () => AiReportController(repository: Get.find<MonitoringRepository>()),
      fenix: true,
    );

  if (!Get.isRegistered<SwapDatesController>()) {
  Get.put(
    SwapDatesController(repository: Get.find<MonitoringRepository>()),
    permanent: true,
  );
}
  }
}