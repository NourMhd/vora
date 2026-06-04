abstract class AppRoutes {
  static const login = '/login';
  static const shell = '/';
  static const dashboard = '/dashboard';
  static const swapDates = '/swap-dates';
  static const performance = '/performance';
  static const comparatif = '/comparatif';
  static const sites = '/sites';
  static const alarms = '/alarms';
  static const aiReport = '/ai-report';
  static const siteDetails = '/sites/:brand/:siteCode';

  static String siteDetailsPath({
    required String brand,
    required String siteCode,
  }) {
    return '/sites/${Uri.encodeComponent(brand)}/${Uri.encodeComponent(siteCode)}';
  }
}