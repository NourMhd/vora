class FrontSyncPolicy {
  FrontSyncPolicy._();

  /// Mongo-only screens. Ne doit pas forcer les fournisseurs.
  static const Duration liveFast = Duration(seconds: 30);
  static const Duration liveStatus = Duration(seconds: 30);

  /// Solis est plus sensible au 429, donc détail site plus lent.
  static const Duration solisLive = Duration(seconds: 60);

  /// Historique détaillé: toujours plus lent que les KPIs.
  static const Duration siteHistory = Duration(minutes: 30);
}