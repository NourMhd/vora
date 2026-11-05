package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.HistoryPointDto;
import com.fusionsolar.monitoring.dto.SiteDescriptionDto;
import com.fusionsolar.monitoring.dto.SiteDescriptionPhotoDto;
import com.fusionsolar.monitoring.exception.ResourceNotFoundException;
import com.fusionsolar.monitoring.persistence.document.SiteDescriptionDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDescriptionPhotoDocument;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.document.SiteHistoryDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.enums.HistoryGranularity;
import com.fusionsolar.monitoring.persistence.enums.SiteStatus;
import com.fusionsolar.monitoring.persistence.repositories.SiteDescriptionRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteHistoryRepository;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SiteDescriptionService {

    private static final long MAX_PHOTO_BYTES = 8L * 1024L * 1024L;
    private static final ZoneId TUNIS_ZONE = ZoneId.of("Africa/Tunis");
    private static final int LAST_MONITORED_LOOKBACK_DAYS = 90;

    private final SiteRepository siteRepository;
    private final SiteDescriptionRepository siteDescriptionRepository;
    private final SiteHistoryRepository siteHistoryRepository;
    private final CurrentSiteAccessService currentSiteAccessService;
    private final ProductionEstimationService productionEstimationService;

    public SiteDescriptionDto getDescription(Brand brand, String siteCode) {
        SiteDocument site = getAccessibleProjectSiteOrThrow(brand, siteCode);
        SiteDescriptionDocument description = siteDescriptionRepository
                .findByBrandAndSiteCode(brand, siteCode)
                .orElse(null);

        return toDto(site, description, buildComparison(site), buildPerformanceIndex(site));
    }

    public SiteDescriptionDto saveDescription(
            Brand brand,
            String siteCode,
            String note,
            String orientationDeg,
            MultipartFile photo,
            List<MultipartFile> photos,
            List<String> retainedPhotoIds,
            Boolean removePhoto
    ) {
        SiteDocument site = getAccessibleProjectSiteOrThrow(brand, siteCode);
        Instant now = Instant.now();

        SiteDescriptionDocument document = siteDescriptionRepository
                .findByBrandAndSiteCode(brand, siteCode)
                .orElseGet(() -> SiteDescriptionDocument.builder()
                        .brand(brand)
                        .siteCode(siteCode)
                        .createdAt(now)
                        .build());

        document.setSiteName(site.getSiteName());
        document.setNote(normalizeNote(note));
        document.setOrientationDeg(parseOrientation(orientationDeg));

        migrateLegacyPhoto(document);

        if (Boolean.TRUE.equals(removePhoto)) {
            document.setPhotos(new ArrayList<>());
            document.setPhotoFilename(null);
            document.setPhotoContentType(null);
            document.setPhotoBase64(null);
            document.setPhotoUpdatedAt(null);
        } else {
            keepRetainedPhotos(document, retainedPhotoIds);
            appendPhoto(document, photo, now);
            appendPhotos(document, photos, now);
        }
        syncLegacyPhotoFields(document);

        document.setUpdatedAt(now);
        SiteDescriptionDocument saved = siteDescriptionRepository.save(document);
        return toDto(site, saved, buildComparison(site), buildPerformanceIndex(site));
    }

    private SiteDocument getAccessibleProjectSiteOrThrow(Brand brand, String siteCode) {
        SiteDocument site = siteRepository.findByBrandAndSiteCode(brand, siteCode).orElse(null);

        if (site == null || !currentSiteAccessService.canAccess(site)) {
            throw new ResourceNotFoundException("Site not found or inactive: " + siteCode + " for brand: " + brand);
        }

        if (currentSiteAccessService.currentScope() == SiteAccessScope.INDEPENDENT || !SiteAccessPolicy.isProjectSite(site)) {
            throw new ResourceNotFoundException("Description site is available only for technician project sites.");
        }

        return site;
    }

    private void appendPhotos(SiteDescriptionDocument document, List<MultipartFile> photos, Instant now) {
        if (photos == null || photos.isEmpty()) {
            return;
        }

        for (MultipartFile photo : photos) {
            appendPhoto(document, photo, now);
        }
    }

    private void appendPhoto(SiteDescriptionDocument document, MultipartFile photo, Instant now) {
        if (photo == null || photo.isEmpty()) {
            return;
        }

        SiteDescriptionPhotoDocument photoDocument = toPhotoDocument(document, photo, now);
        List<SiteDescriptionPhotoDocument> current = mutablePhotos(document);
        current.add(photoDocument);
        document.setPhotos(current);

        log.info(
                "Site photo saved in Mongo description brand={} siteCode={} photoId={} filename={} contentType={} bytes={}",
                document.getBrand(),
                document.getSiteCode(),
                photoDocument.getId(),
                photoDocument.getFilename(),
                photoDocument.getContentType(),
                photo.getSize()
        );
    }

    private SiteDescriptionPhotoDocument toPhotoDocument(
            SiteDescriptionDocument document,
            MultipartFile photo,
            Instant now
    ) {
        if (photo.getSize() > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("La photo dépasse 8 Mo.");
        }

        String filename = firstNonBlank(photo.getOriginalFilename(), "site-photo.jpg");
        String contentType = normalizeImageContentType(photo.getContentType(), filename);
        if (contentType == null) {
            throw new IllegalArgumentException("Le fichier doit être une image JPG, PNG, WEBP ou GIF.");
        }

        String base64;
        try {
            byte[] bytes = photo.getBytes();
            if (bytes.length == 0) {
                throw new IllegalArgumentException("La photo est vide.");
            }
            base64 = Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Impossible de lire la photo du site.", e);
        }

        return SiteDescriptionPhotoDocument.builder()
                .id(UUID.randomUUID().toString())
                .filename(filename)
                .contentType(contentType)
                .base64(base64)
                .updatedAt(now)
                .build();
    }

    private void keepRetainedPhotos(SiteDescriptionDocument document, List<String> retainedPhotoIds) {
        if (retainedPhotoIds == null) {
            return;
        }

        Set<String> retained = retainedPhotoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toSet());

        List<SiteDescriptionPhotoDocument> kept = mutablePhotos(document).stream()
                .filter(photo -> photo != null && photo.getId() != null && retained.contains(photo.getId()))
                .toList();
        document.setPhotos(new ArrayList<>(kept));
    }

    private void migrateLegacyPhoto(SiteDescriptionDocument document) {
        if (document == null || document.getPhotoBase64() == null || document.getPhotoBase64().isBlank()) {
            return;
        }

        if (document.getPhotos() != null && !document.getPhotos().isEmpty()) {
            return;
        }

        document.setPhotos(new ArrayList<>(List.of(SiteDescriptionPhotoDocument.builder()
                .id(UUID.randomUUID().toString())
                .filename(firstNonBlank(document.getPhotoFilename(), "site-photo.jpg"))
                .contentType(firstNonBlank(document.getPhotoContentType(), "image/jpeg"))
                .base64(document.getPhotoBase64())
                .updatedAt(document.getPhotoUpdatedAt())
                .build())));
    }

    private void syncLegacyPhotoFields(SiteDescriptionDocument document) {
        SiteDescriptionPhotoDocument first = mutablePhotos(document).stream().findFirst().orElse(null);

        if (first == null) {
            document.setPhotoFilename(null);
            document.setPhotoContentType(null);
            document.setPhotoBase64(null);
            document.setPhotoUpdatedAt(null);
            return;
        }

        document.setPhotoFilename(first.getFilename());
        document.setPhotoContentType(first.getContentType());
        document.setPhotoBase64(first.getBase64());
        document.setPhotoUpdatedAt(first.getUpdatedAt());
    }

    private List<SiteDescriptionPhotoDocument> mutablePhotos(SiteDescriptionDocument document) {
        if (document.getPhotos() == null) {
            return new ArrayList<>();
        }

        return new ArrayList<>(document.getPhotos().stream()
                .filter(photo -> photo != null && photo.getBase64() != null && !photo.getBase64().isBlank())
                .toList());
    }

    private SiteDescriptionPhotoDocument firstPhoto(SiteDescriptionDocument document) {
        if (document == null) {
            return null;
        }
        migrateLegacyPhoto(document);
        return mutablePhotos(document).stream().findFirst().orElse(null);
    }

    private String normalizeImageContentType(String contentType, String filename) {
        if (contentType != null) {
            String normalized = contentType.toLowerCase(Locale.ROOT).trim();
            if (normalized.startsWith("image/")) {
                return normalized;
            }
        }

        String lowerFilename = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lowerFilename.endsWith(".jpg") || lowerFilename.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerFilename.endsWith(".png")) {
            return "image/png";
        }
        if (lowerFilename.endsWith(".webp")) {
            return "image/webp";
        }
        if (lowerFilename.endsWith(".gif")) {
            return "image/gif";
        }

        return null;
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }

        String trimmed = note.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.length() > 3000 ? trimmed.substring(0, 3000) : trimmed;
    }

    private Double parseOrientation(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        try {
            double parsed = Double.parseDouble(value.trim().replace(',', '.'));
            if (parsed < 0 || parsed > 360) {
                throw new IllegalArgumentException("L'orientation doit être entre 0° et 360°.");
            }
            return round2(parsed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Orientation invalide. Exemple: 180 ou 135.5");
        }
    }

    private SiteDescriptionDto toDto(
            SiteDocument site,
            SiteDescriptionDocument description,
            Comparison comparison,
            PerformanceIndex performanceIndex
    ) {
        return SiteDescriptionDto.builder()
                .brand(site.getBrand() != null ? site.getBrand().name() : null)
                .siteCode(site.getSiteCode())
                .siteName(site.getSiteName())
                .notice(comparison.notice())
                .comparisonPeriod(comparison.period())
                .governorate(comparison.governorate())
                .comparableSitesCount(comparison.peerCount())
                .bestSiteName(comparison.bestSiteName())
                .bestSiteCode(comparison.bestSiteCode())
                .currentSpecificYieldKwhPerKwp(comparison.currentYield())
                .bestSpecificYieldKwhPerKwp(comparison.bestYield())
                .gapPercent(comparison.gapPercent())
                .comparisonPercent(comparison.comparisonPercent())
                .swapDate(site.getSwapDate() != null ? site.getSwapDate().toString() : null)
                .gridConnectionDate(site.getGridConnectionDate())
                .totalEnergyKwh(round2(nullToZero(site.getTotalEnergyKwh())))
                .performanceIndexDate(performanceIndex.date() != null ? performanceIndex.date().toString() : null)
                .performanceIndexPercent(performanceIndex.indexPercent())
                .performanceActualKwh(performanceIndex.actualKwh())
                .performanceTheoreticalKwh(performanceIndex.theoreticalKwh())
                .performanceStatus(performanceIndex.status())
                .note(description != null ? description.getNote() : null)
                .orientationDeg(description != null ? description.getOrientationDeg() : null)
                .hasPhoto(!photoDtos(description).isEmpty())
                .photoDataUrl(firstPhotoDataUrl(description))
                .photoFilename(firstPhotoFilename(description))
                .photoUpdatedAt(firstPhotoUpdatedAt(description))
                .photos(photoDtos(description))
                .build();
    }

    private List<SiteDescriptionPhotoDto> photoDtos(SiteDescriptionDocument description) {
        if (description == null) {
            return List.of();
        }

        migrateLegacyPhoto(description);

        return mutablePhotos(description).stream()
                .map(photo -> SiteDescriptionPhotoDto.builder()
                        .id(photo.getId())
                        .filename(photo.getFilename())
                        .dataUrl(photoDataUrl(photo))
                        .updatedAt(photo.getUpdatedAt() != null ? photo.getUpdatedAt().toString() : null)
                        .build())
                .toList();
    }

    private String firstPhotoDataUrl(SiteDescriptionDocument description) {
        return photoDataUrl(firstPhoto(description));
    }

    private String firstPhotoFilename(SiteDescriptionDocument description) {
        SiteDescriptionPhotoDocument photo = firstPhoto(description);
        return photo != null ? photo.getFilename() : null;
    }

    private String firstPhotoUpdatedAt(SiteDescriptionDocument description) {
        SiteDescriptionPhotoDocument photo = firstPhoto(description);
        return photo != null && photo.getUpdatedAt() != null ? photo.getUpdatedAt().toString() : null;
    }

    private String photoDataUrl(SiteDescriptionPhotoDocument photo) {
        if (photo == null || photo.getBase64() == null || photo.getBase64().isBlank()) {
            return null;
        }

        String contentType = photo.getContentType();
        if (contentType == null || contentType.isBlank()) {
            contentType = "image/jpeg";
        }

        return "data:" + contentType + ";base64," + photo.getBase64();
    }

    private Comparison buildComparison(SiteDocument site) {
        LocalDate endDay = LocalDate.now(TUNIS_ZONE).minusDays(1);
        String governorate = productionEstimationService.governorateForSite(site);
        double power = nullToZero(site.getInstalledPowerKw());

        if (governorate == null || governorate.isBlank() || power <= 0) {
            return Comparison.noData(
                    governorate,
                    "Comparaison indisponible: gouvernorat ou puissance kWc manquant pour ce site."
            ).withPeriod(lastMonitoredPeriodLabel(endDay));
        }

        if (!isHealthy(site)) {
            return Comparison.noData(
                    governorate,
                    "Comparaison par gouvernorat disponible uniquement pour les sites Healthy."
            ).withPeriod(lastMonitoredPeriodLabel(endDay));
        }

        List<PeerYield> peerYields = siteRepository.findAllByOrderBySiteNameAsc().stream()
                .filter(SiteAccessPolicy::isActive)
                .filter(this::isHealthy)
                .filter(candidate -> governorate.equals(productionEstimationService.governorateForSite(candidate)))
                .map(candidate -> latestPeerYield(candidate, endDay))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();

        if (peerYields.size() <= 1) {
            return Comparison.noData(
                    governorate,
                    "Pas encore assez de sites Healthy comparables avec kWh réel sur le dernier jour monitorisé dans le même gouvernorat."
            ).withPeerCount(peerYields.size()).withPeriod(lastMonitoredPeriodLabel(endDay));
        }

        Optional<PeerYield> current = peerYields.stream()
                .filter(peer -> sameSite(site, peer.site()))
                .findFirst();

        if (current.isEmpty()) {
            return Comparison.noData(
                    governorate,
                    "Comparaison indisponible: aucun jour monitorisé trouvé pour ce site sur les 90 derniers jours."
            ).withPeerCount(peerYields.size()).withPeriod(lastMonitoredPeriodLabel(endDay));
        }

        PeerYield currentYield = current.get();
        PeerYield bestYield = peerYields.stream()
                .max(Comparator.comparingDouble(PeerYield::specificYield))
                .orElse(currentYield);

        double comparisonPercent = bestYield.specificYield() > ProductionEstimationService.EPSILON
                ? round2((currentYield.specificYield() / bestYield.specificYield()) * 100.0)
                : 0.0;
        double gapPercent = round2(Math.max(0.0, 100.0 - comparisonPercent));

        String bestSiteLabel = siteLabel(bestYield.site());
        String notice;
        if (sameSite(site, bestYield.site()) || Math.abs(gapPercent) < 0.01) {
            notice = String.format(
                    Locale.FRANCE,
                    "Écart de 0,00 %% par rapport au meilleur site du gouvernorat %s (%s). Rendement: %.2f kWh/kWc sur le dernier jour monitorisé complet hors aujourd’hui (%s).",
                    governorate,
                    bestSiteLabel,
                    currentYield.specificYield(),
                    currentYield.date()
            );
        } else {
            notice = String.format(
                    Locale.FRANCE,
                    "Écart de -%.2f %% par rapport au meilleur site du gouvernorat %s (%s). Rendement site: %.2f kWh/kWc, meilleur: %.2f kWh/kWc, dernier jour monitorisé complet hors aujourd’hui: %s.",
                    gapPercent,
                    governorate,
                    bestSiteLabel,
                    currentYield.specificYield(),
                    bestYield.specificYield(),
                    currentYield.date()
            );
        }

        return new Comparison(
                notice,
                lastMonitoredPeriodLabel(endDay),
                governorate,
                peerYields.size(),
                safeName(bestYield.site()),
                bestYield.site().getSiteCode(),
                round2(currentYield.specificYield()),
                round2(bestYield.specificYield()),
                round2(gapPercent),
                round2(comparisonPercent)
        );
    }

    private Optional<PeerYield> latestPeerYield(SiteDocument site, LocalDate endDay) {
        double power = nullToZero(site != null ? site.getInstalledPowerKw() : null);
        if (power <= 0) {
            return Optional.empty();
        }

        return lastMonitoredProduction(site, endDay, LAST_MONITORED_LOOKBACK_DAYS)
                .map(result -> new PeerYield(site, result.date(), result.realMonitoredKwh(), result.realMonitoredKwh() / power));
    }

    private PerformanceIndex buildPerformanceIndex(SiteDocument site) {
        LocalDate endDay = LocalDate.now(TUNIS_ZONE).minusDays(1);
        Optional<ProductionDayResult> latest = lastMonitoredProduction(site, endDay, LAST_MONITORED_LOOKBACK_DAYS);

        if (latest.isEmpty()) {
            return new PerformanceIndex(null, null, null, null, "NO_MONITORED_DAY");
        }

        ProductionDayResult result = latest.get();
        if (result.theoreticalKwh() == null || result.theoreticalKwh() <= ProductionEstimationService.EPSILON) {
            return new PerformanceIndex(result.date(), null, result.realMonitoredKwh(), result.theoreticalKwh(), "NO_THEORETICAL_KWH");
        }

        double index = (result.realMonitoredKwh() / result.theoreticalKwh()) * 100.0;
        return new PerformanceIndex(
                result.date(),
                round2(index),
                round2(result.realMonitoredKwh()),
                round2(result.theoreticalKwh()),
                performanceStatus(index)
        );
    }

    private Optional<ProductionDayResult> lastMonitoredProduction(
            SiteDocument site,
            LocalDate endDay,
            int lookbackDays
    ) {
        if (site == null || endDay == null) {
            return Optional.empty();
        }

        int safeLookbackDays = Math.max(1, lookbackDays);
        LocalDate startDay = endDay.minusDays(safeLookbackDays - 1L);
        ProductionEstimationService.DailySiteProduction production = productionMetadata(site);

        LocalDate cursor = endDay;
        while (!cursor.isBefore(startDay)) {
            if (!productionEstimationService.isProductionDay(production, cursor)) {
                cursor = cursor.minusDays(1);
                continue;
            }

            Optional<Double> dayHistoryTotal = realKwhFromMonthHistory(site, cursor);
            if (dayHistoryTotal.isPresent()) {
                Double theoreticalKwh = productionEstimationService.theoreticalDailyKwh(production, cursor);
                return Optional.of(new ProductionDayResult(
                        cursor,
                        round2(dayHistoryTotal.get()),
                        theoreticalKwh,
                        "REAL_MONITORED_DAY_HISTORY"
                ));
            }

            cursor = cursor.minusDays(1);
        }

        return Optional.empty();
    }

    private Optional<Double> realKwhFromMonthHistory(SiteDocument site, LocalDate day) {
        if (site == null || day == null) {
            return Optional.empty();
        }

        String monthBucket = day.toString().substring(0, 7);

        Optional<SiteHistoryDocument> history = findHistory(
                site,
                HistoryGranularity.MONTH,
                monthBucket
        );

        if (history.isEmpty() || history.get().getPoints() == null || history.get().getPoints().isEmpty()) {
            return Optional.empty();
        }

        for (HistoryPointDto point : history.get().getPoints()) {
            if (point == null || point.getCollectTime() == null) {
                continue;
            }

            LocalDate pointDay = toLocalDate(point.getCollectTime());
            if (!day.equals(pointDay)) {
                continue;
            }

            Double value = productionEstimationService.normalizeHistoryValue(point.getValue());
            if (value != null && value > ProductionEstimationService.EPSILON) {
                return Optional.of(round2(value));
            }
        }

        return Optional.empty();
    }

    private Optional<Double> realKwhFromDayHistory(SiteDocument site, LocalDate day) {
        if (site == null || day == null) {
            return Optional.empty();
        }

        Optional<SiteHistoryDocument> history = findHistory(
                site,
                HistoryGranularity.DAY,
                day.toString()
        );

        if (history.isEmpty() || history.get().getPoints() == null || history.get().getPoints().isEmpty()) {
            return Optional.empty();
        }

        double total = 0.0;
        boolean hasValue = false;

        for (HistoryPointDto point : history.get().getPoints()) {
            if (point == null || point.getCollectTime() == null) {
                continue;
            }

            LocalDate pointDay = toLocalDate(point.getCollectTime());
            if (!day.equals(pointDay)) {
                continue;
            }

            Double value = productionEstimationService.normalizeHistoryValue(point.getValue());
            if (value != null) {
                total += value;
                hasValue = true;
            }
        }

        return hasValue ? Optional.of(round2(total)) : Optional.empty();
    }

    private ProductionEstimationService.DailySiteProduction productionMetadata(SiteDocument site) {
        String governorate = productionEstimationService.governorateForSite(site);
        String zone = productionEstimationService.zoneForGovernorate(governorate);

        return new ProductionEstimationService.DailySiteProduction(
                site,
                site != null ? site.getSiteCode() : null,
                site != null ? site.getSiteName() : null,
                governorate,
                zone,
                site != null ? nullToZero(site.getInstalledPowerKw()) : 0.0,
                site != null ? site.getSwapDate() : null,
                Map.of(),
                Set.of()
        );
    }

    private Optional<SiteHistoryDocument> findHistory(
            SiteDocument site,
            HistoryGranularity granularity,
            String bucket
    ) {
        if (site == null) {
            return Optional.empty();
        }

        Optional<SiteHistoryDocument> grouped = siteHistoryRepository
                .findByBrandAndProviderAccountGroupAndSiteCodeAndGranularityAndBucket(
                        site.getBrand(),
                        accountGroupOf(site),
                        site.getSiteCode(),
                        granularity,
                        bucket
                );

        if (grouped.isPresent()) {
            return grouped;
        }

        return siteHistoryRepository.findByBrandAndSiteCodeAndGranularityAndBucket(
                site.getBrand(),
                site.getSiteCode(),
                granularity,
                bucket
        );
    }

    private String accountGroupOf(SiteDocument site) {
        if (site != null && site.getProviderAccountGroup() != null && !site.getProviderAccountGroup().isBlank()) {
            return site.getProviderAccountGroup().trim();
        }
        if (site != null && site.getProviderAccountKey() != null && !site.getProviderAccountKey().isBlank()) {
            return site.getProviderAccountKey().trim();
        }
        return "primary";
    }

    private LocalDate toLocalDate(Long collectTime) {
        if (collectTime == null) {
            return null;
        }
        return toInstant(collectTime).atZone(TUNIS_ZONE).toLocalDate();
    }

    private Instant toInstant(Long collectTime) {
        long raw = collectTime;
        long abs = Math.abs(raw);
        long epochMillis;

        if (abs >= 10_000_000_000_000_000L) {
            // Nanoseconds-like timestamp.
            epochMillis = raw / 1_000_000L;
        } else if (abs >= 100_000_000_000_000L) {
            // Microseconds timestamp.
            epochMillis = raw / 1_000L;
        } else if (abs < 10_000_000_000L) {
            // Seconds timestamp.
            epochMillis = raw * 1_000L;
        } else {
            // Milliseconds timestamp.
            epochMillis = raw;
        }

        return Instant.ofEpochMilli(epochMillis);
    }

    private String comparisonPeriodLabel(LocalDate day) {
        return day == null ? "J-1" : "J-1 (" + day + ")";
    }

    private String lastMonitoredPeriodLabel(LocalDate endDay) {
        return endDay == null
                ? "Dernier jour monitorisé complet hors aujourd’hui"
                : "Dernier jour monitorisé complet hors aujourd’hui jusqu'à " + endDay;
    }


    private boolean isHealthy(SiteDocument site) {
        return site != null && site.getStatus() == SiteStatus.HEALTHY;
    }

    private boolean sameSite(SiteDocument a, SiteDocument b) {
        return a != null && b != null
                && a.getBrand() == b.getBrand()
                && String.valueOf(a.getSiteCode()).equals(String.valueOf(b.getSiteCode()));
    }

    private String safeName(SiteDocument site) {
        if (site == null) {
            return "—";
        }
        if (site.getSiteName() != null && !site.getSiteName().isBlank()) {
            return site.getSiteName();
        }
        return site.getSiteCode();
    }

    private String siteLabel(SiteDocument site) {
        if (site == null) {
            return "—";
        }

        String name = safeName(site);
        String code = site.getSiteCode();
        if (code == null || code.isBlank() || code.equals(name)) {
            return name;
        }
        return name + " - " + code;
    }

    private String governorateOf(SiteDocument site) {
        if (site == null) {
            return null;
        }

        String source = firstNonBlank(site.getSiteName(), site.getSiteCode(), site.getProviderSiteId());
        if (source == null) {
            return null;
        }

        String normalized = Normalizer
                .normalize(source.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);

        int underscore = normalized.indexOf('_');
        if (underscore > 0) {
            return normalized.substring(0, underscore).trim();
        }

        int digitIndex = -1;
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isDigit(normalized.charAt(i))) {
                digitIndex = i;
                break;
            }
        }

        if (digitIndex > 0) {
            return normalized.substring(0, digitIndex).replaceAll("[^A-Z]", "").trim();
        }

        return normalized.length() >= 3 ? normalized.substring(0, 3) : normalized;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private double nullToZero(Double value) {
        return value != null ? value : 0.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String performanceStatus(double indexPercent) {
        if (indexPercent > 95.0) {
            return "EXCELLENT";
        }
        if (indexPercent >= 90.0) {
            return "NORMAL";
        }
        if (indexPercent >= 80.0) {
            return "SURVEILLANCE";
        }
        return "FAIBLE";
    }

    private record ProductionDayResult(
            LocalDate date,
            Double realMonitoredKwh,
            Double theoreticalKwh,
            String status
    ) {
    }

    private record PerformanceIndex(
            LocalDate date,
            Double indexPercent,
            Double actualKwh,
            Double theoreticalKwh,
            String status
    ) {
    }

    private record PeerYield(
            SiteDocument site,
            LocalDate date,
            double realKwh,
            double specificYield
    ) {
    }

    private record Comparison(
            String notice,
            String period,
            String governorate,
            int peerCount,
            String bestSiteName,
            String bestSiteCode,
            Double currentYield,
            Double bestYield,
            Double gapPercent,
            Double comparisonPercent
    ) {
        static Comparison noData(String governorate, String notice) {
            return new Comparison(notice, null, governorate, 0, null, null, null, null, null, null);
        }

        Comparison withPeerCount(int value) {
            return new Comparison(notice, period, governorate, value, bestSiteName, bestSiteCode, currentYield, bestYield, gapPercent, comparisonPercent);
        }

        Comparison withPeriod(String value) {
            return new Comparison(notice, value, governorate, peerCount, bestSiteName, bestSiteCode, currentYield, bestYield, gapPercent, comparisonPercent);
        }
    }
}
