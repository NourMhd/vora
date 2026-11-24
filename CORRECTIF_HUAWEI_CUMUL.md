# Correctif Huawei — répartition du cumul par période

Le rapport « Suivi production » utilise, quand toutes les valeurs sont disponibles,
la somme des compteurs de production des onduleurs Huawei du site.
Deux onduleurs = somme des deux `total_cap`, sans compter deux fois un même ID.
Les types Huawei 1 et 38 sont pris en charge. Les compteurs réseau d'import/export
ne sont pas additionnés aux compteurs de production des onduleurs.

## Calcul

kWh mesuré du filtre = cumul total × rendement théorique du filtre / rendement
théorique depuis la mise en service jusqu'à aujourd'hui inclus (Africa/Tunis).
Les deux plages sont bornées entre la mise en service et aujourd'hui.
Les rendements journaliers par zone et tranche du mois restent ceux du projet.
La puissance est commune au numérateur et au dénominateur : elle s'annule.
Le calcul utilise les valeurs non arrondies ; l'affichage est arrondi à 2 décimales.
De petits écarts d'arrondi peuvent apparaître en additionnant des lignes exportées.

Exemple Zone A, mise en service 01/01/2025, observation 28/02/2025, cumul 1625 kWh :
rendement janvier 73,5 ; février 89 ; total 162,5 kWh/kWc.
Janvier : 1625 × 73,5 / 162,5 = 735 kWh.
Février : 1625 × 89 / 162,5 = 890 kWh.
La période complète restitue les 1625 kWh, sans soustraire la production du jour.
Il s'agit d'une répartition théorique du cumul, pas d'un historique journalier relevé.

Cette valeur alimente « kWh mesuré » et le total corrigé. Aucun complément estimé
ni historique n'est ajouté une seconde fois. Les écarts et bonus/malus sont recalculés.
Le tableau et l'export gardent uniquement la date de mise en service.
Aucun badge ou libellé de provenance des kWh n'est ajouté dans l'interface.

## Repli et collecte

Si un onduleur manque, si une valeur est invalide ou date de plus de 20 minutes,
ou si la date MES ou la zone nécessaire au calcul est absente, le rapport utilise
sa logique précédente (historiques et estimation, bornés par le swap et hier).
Les autres marques conservent cette logique. Un cumul nul est une valeur valide.
Les exceptions de sites forcés à zéro restent prioritaires.
Les dates de swap restent stockées pour le repli et les autres modules.

Les rapports lisent uniquement le cache MongoDB et ne déclenchent pas d'appel Huawei.
Collecte planifiée : premier passage après 120 s, vérification toutes les 60 s,
au moins 5 minutes entre appels getDevRealKpi par périmètre de quota existant,
100 appareils maximum par requête, un type d'appareil par requête.
Le délai réel dépend des comptes, des lots et des limitations Huawei.
L'inventaire des appareils est désormais renouvelé lorsqu'il expire (12 h).
Les lots les plus anciens sont traités d'abord pour éviter leur blocage permanent.
La première collecte peut donc nécessiter plusieurs passages avant substitution.

Référence vérifiée : SmartPVMS 25.4.0 NBI Reference, Issue 01, 14/01/2026,
section 5.1.2.2 ; pages imprimées 78, 87 et 92.

## Validation effectuée

- 17 contrôles exécutés sur la répartition : mois isolés, périodes partielles,
  bornes, période future, cumul nul, données invalides, conservation du cumul
  pour les trois zones, années multiples et année bissextile.
- 18 contrôles exécutés sur le rapport et la somme des compteurs : types mixtes,
  doublons, exclusion du compteur réseau, absence de swap avec cumul disponible,
  période complète incluant aujourd'hui, repli sur historique et donnée périmée.
- 9 tests existants exécutés : rapport avec swap et exceptions des sites.
- Compilation Java ciblée des classes modifiées réussie avec les classes et
  bibliothèques disponibles ; membres Lombok explicités dans des copies temporaires.
  Les sources livrées conservent Lombok et n'incluent pas ces copies.
- Syntaxe Dart du fichier modifié vérifiée.
- Le build Maven/JUnit complet et le build Flutter n'ont pas été validés ici :
  dépendances Maven indisponibles et cache Flutter du ZIP lié à Windows.
  Aucun JAR ni build web généré n'est inclus.

## Mise à jour

Ce ZIP contient les sources complètes corrigées, sans caches de compilation ni .git.
Conserver la configuration privée de votre serveur et les données MongoDB existantes.
Il n'y a pas de script de migration à exécuter : les champs du cache sont ajoutés
par les nouvelles collectes.

Backend, depuis backend/monitoring :

```powershell
.\mvnw.cmd clean verify
```

Frontend, depuis monitoring (commande reprise du workflow CI fourni) :

```powershell
flutter pub get
flutter build web --release --base-href /
```

Puis mettre à jour le JAR et le contenu du répertoire frontend selon le déploiement
existant. Les paramètres réseau et comptes du ZIP ne sont pas modifiés.
