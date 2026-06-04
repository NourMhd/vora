package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteSwapDateUpdateDto {

    private String brand;

    private String siteCode;

    /**
     * Format attendu côté API : dd/MM/yyyy.
     * Exemple : 29/04/2026.
     * Si null ou vide, la date de swap sera supprimée.
     */
    private String swapDate;
}