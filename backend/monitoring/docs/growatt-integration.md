# Growatt integration

## Configuration

Set these environment variables on the backend server:

```env
GROWATT_ENABLED=true
GROWATT_BASE_URL=https://openapi.growatt.com
GROWATT_TOKEN_1=your_first_growatt_token
GROWATT_TOKEN_2=your_second_growatt_token
```

The configured site code format is:

```text
GROWATT_<ACCOUNT_GROUP>_<PLANT_ID>
```

Examples:

```text
GROWATT_ITS_238461
GROWATT_COMPTE2_238461
```

This avoids collisions between Huawei, Solis, and multiple Growatt accounts.

## Growatt endpoints used

- `GET /v1/plant/list` for site discovery and add/remove sync.
- `GET /v1/plant/data` for current real KPI values.
- `GET /v1/plant/details` for site details.
- `GET /v1/plant/energy` for day/month/year production history.
- `GET /v1/device/list` for device cache.

Frontend reads MongoDB only. Growatt calls are done by backend sync/backfill services to avoid `error_frequently_access`.
