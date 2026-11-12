# Suivi production : période limitée par la date de swap

- Début = max(début filtre, date de swap), indépendamment de la mise en service.
- Fin = min(fin filtre, hier en Africa/Tunis), bornes incluses.
- Jours filtre et jours production = nombre de jours de cette période effective.
- Prévu théorique = puissance installée × somme des rendements journaliers sur toute cette période.
- Jours monitorisés = jours ayant une valeur valide >= 0, y compris 0 kWh.
- Jours manquants = jours effectifs - jours monitorisés.
- Estimation manquante = somme monitorisée / jours monitorisés × jours manquants.
- Total = somme monitorisée + estimation manquante.
- Sans swap : statut MISSING_SWAP_DATE, énergies et écarts indisponibles ; les compteurs API restent à 0 par compatibilité, affichés Indisponible dans le tableau et l'export.
- Sans jour monitorisé : prévu théorique disponible si zone/puissance valides ; estimation et total indisponibles.
- Aucun changement des règles propres au module de comparaison journalière.

Exemples : 10/11 au 30/11 = 21 jours ; 01/01/2026 au 01/05/2026 = 121 jours.
Un swap en novembre 2025 est antérieur à un filtre novembre 2026 et ne réduit donc pas ce dernier.

## Fichiers modifiés
- backend/monitoring/src/main/java/com/fusionsolar/monitoring/service/PerformanceReportService.java
- backend/monitoring/src/main/java/com/fusionsolar/monitoring/service/ProductionEstimationService.java (commentaire)
- backend/monitoring/src/test/java/com/fusionsolar/monitoring/service/PerformanceReportSwapTests.java (nouveau)
- monitoring/lib/data/models/performance_report.dart
- monitoring/lib/modules/performence/pages/performance_page.dart

## Vérification
27 assertions ciblées sur le rapport et 23 sur le moteur d'estimation ont passé avec les sources actuelles.
Compilation Java ciblée avec constructeur explicite équivalent à Lombok pour le rapport.
Compilation Flutter et suite Maven complète non effectuées dans cet environnement.
Ce ZIP contient les sources ; reconstruire le backend et le frontend avant déploiement.
