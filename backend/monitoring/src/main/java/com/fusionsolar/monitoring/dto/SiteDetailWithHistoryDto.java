package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SiteDetailWithHistoryDto {
    private SiteDetailDto summary;
    /** kWh par heure pour une date donnée, par défaut aujourd'hui. */
    private List<HistoryPointDto> dayHistory;
    /** kWh par jour pour le mois courant. */
    private List<HistoryPointDto> monthHistory;
    /** kWh par mois pour l'année courante. */
    private List<HistoryPointDto> yearHistory;
    /** kWh par année sur toute la durée de vie du site. */
    private List<HistoryPointDto> allTimeHistory;
}
