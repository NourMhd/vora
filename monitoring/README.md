# VORA Frontend

Frontend Flutter / GetX pour la plateforme VORA.

## Objectif

Interface premium de monitoring multi-marque pour actifs énergétiques :

- Login technicien unique
- Dashboard global
- Liste de sites filtrable
- Détail expert par site
- Historique Day / Month / Year / Custom
- Liste des devices

## Backend consommé

Le front consomme directement les endpoints du backend Spring Boot fourni :

- `POST /api/auth/login`
- `GET /api/auth/me`
- `POST /api/auth/logout`
- `GET /api/brands`
- `GET /api/dashboard/summary`
- `GET /api/sites`
- `GET /api/brands/{brand}/sites/{siteCode}`
- `GET /api/brands/{brand}/sites/{siteCode}/full`
- `GET /api/brands/{brand}/sites/{siteCode}/history?type=day|month|year&value=...`
- `GET /api/brands/{brand}/sites/{siteCode}/devices`
- `GET /api/health`
- `GET /api/sync/status`

## Lancer le front

Par défaut, l'API est appelée sur :

```bash
http://localhost:9090
```

Pour changer l'URL :

```bash
flutter run --dart-define=VORA_API_BASE_URL=http://localhost:9090
```

Exemple web recommandé avec le CORS backend existant :

```bash
flutter run -d chrome --web-port=5173 --dart-define=VORA_API_BASE_URL=http://localhost:9090
```

## Branding

Les fichiers de marque sont dans :

```text
assets/branding/
```

Le code référence déjà les emplacements suivants :

- `assets/branding/vora_mark.png`
- `assets/branding/vora_lockup_dark.png`
- `assets/branding/vora_lockup_light.png`

