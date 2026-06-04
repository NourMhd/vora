package com.fusionsolar.monitoring.service;

import com.fusionsolar.monitoring.dto.SiteSwapDateBulkUpdateRequest;
import com.fusionsolar.monitoring.dto.SiteSwapDateBulkUpdateResponse;
import com.fusionsolar.monitoring.dto.SiteSwapDateDto;
import com.fusionsolar.monitoring.dto.SiteSwapDatePageDto;
import com.fusionsolar.monitoring.dto.SiteSwapDateUpdateDto;
import com.fusionsolar.monitoring.exception.ResourceNotFoundException;
import com.fusionsolar.monitoring.persistence.document.SiteDocument;
import com.fusionsolar.monitoring.persistence.enums.Brand;
import com.fusionsolar.monitoring.persistence.repositories.SiteRepository;
import com.fusionsolar.monitoring.util.SiteNameNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SiteSwapDateService {

    public static final int DEFAULT_PAGE_SIZE = 50;

    private static final int MAX_PAGE_SIZE = 50;

    private static final DateTimeFormatter SWAP_DATE_FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/uuuu")
            .withResolverStyle(ResolverStyle.STRICT);

    private final SiteRepository siteRepository;
    private final CurrentSiteAccessService currentSiteAccessService;

    public SiteSwapDatePageDto getSwapDates(int page, int size, String search, String swapFilter) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        String normalizedSearch = search != null ? search.trim().toLowerCase(Locale.ROOT) : "";
        String normalizedFilter = swapFilter != null ? swapFilter.trim().toLowerCase(Locale.ROOT) : "all";

        List<SiteDocument> filteredSites = siteRepository.findAllByOrderBySiteNameAsc()
                .stream()
                .filter(currentSiteAccessService::canAccess)
                .filter(site -> matchesSwapFilter(site, normalizedFilter))
                .filter(site -> matchesSearch(site, normalizedSearch))
                .sorted(Comparator
                        .comparing((SiteDocument site) -> safeText(site.getSiteName()))
                        .thenComparing(site -> safeText(site.getBrand() == null ? null : site.getBrand().name()))
                        .thenComparing(site -> safeText(site.getSiteCode())))
                .toList();

        int totalElements = filteredSites.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int fromIndex = Math.min(safePage * safeSize, totalElements);
        int toIndex = Math.min(fromIndex + safeSize, totalElements);

        List<SiteSwapDateDto> items = filteredSites.subList(fromIndex, toIndex)
                .stream()
                .map(this::toDto)
                .toList();

        return new SiteSwapDatePageDto(
                items,
                safePage,
                safeSize,
                totalElements,
                totalPages
        );
    }

    public SiteSwapDateBulkUpdateResponse updateSwapDates(SiteSwapDateBulkUpdateRequest request) {
        List<SiteSwapDateUpdateDto> updates = request != null && request.getUpdates() != null
                ? request.getUpdates()
                : List.of();

        List<SiteDocument> changedSites = new ArrayList<>();
        Instant now = Instant.now();

        for (SiteSwapDateUpdateDto update : updates) {
            if (update == null) {
                continue;
            }

            Brand brand = parseBrand(update.getBrand());
            String siteCode = requireText(update.getSiteCode(), "siteCode");
            LocalDate swapDate = parseSwapDate(update.getSwapDate());

            SiteDocument site = siteRepository
                    .findByBrandAndSiteCode(brand, siteCode)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Site not found: " + siteCode + " for brand: " + brand
                    ));

            if (!currentSiteAccessService.canAccess(site)) {
                throw new ResourceNotFoundException(
                        "Site not found for current login: " + siteCode + " for brand: " + brand
                );
            }

            site.setSwapDate(swapDate);
            site.setUpdatedAt(now);

            changedSites.add(site);
        }

        if (!changedSites.isEmpty()) {
            siteRepository.saveAll(changedSites);
        }

        return new SiteSwapDateBulkUpdateResponse(changedSites.size());
    }

    private boolean matchesSwapFilter(SiteDocument site, String normalizedFilter) {
        if ("swapped".equals(normalizedFilter)) {
            return site.getSwapDate() != null;
        }

        if ("unswapped".equals(normalizedFilter)) {
            return site.getSwapDate() == null;
        }

        return true;
    }

    private boolean matchesSearch(SiteDocument site, String normalizedSearch) {
        if (normalizedSearch == null || normalizedSearch.isBlank()) {
            return true;
        }

        return safeText(site.getSiteName()).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                || safeText(site.getSiteCode()).toLowerCase(Locale.ROOT).contains(normalizedSearch)
                || safeText(site.getProviderSiteId()).toLowerCase(Locale.ROOT).contains(normalizedSearch);
    }

    private SiteSwapDateDto toDto(SiteDocument site) {
        return new SiteSwapDateDto(
                site.getBrand() != null ? site.getBrand().name() : null,
                site.getSiteCode(),
                SiteNameNormalizer.normalize(site.getSiteName()),
                site.getGridConnectionDate(),
                formatSwapDate(site.getSwapDate())
        );
    }

    private Brand parseBrand(String value) {
        String normalized = requireText(value, "brand")
                .trim()
                .toUpperCase(Locale.ROOT);

        try {
            return Brand.valueOf(normalized);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid brand: " + value);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }

        return value;
    }

    private LocalDate parseSwapDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(value.trim(), SWAP_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid swapDate format. Expected dd/MM/yyyy");
        }
    }

    private String formatSwapDate(LocalDate value) {
        return value != null ? value.format(SWAP_DATE_FORMATTER) : null;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
