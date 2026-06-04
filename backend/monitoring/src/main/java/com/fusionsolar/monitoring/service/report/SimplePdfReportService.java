package com.fusionsolar.monitoring.service.report;

import com.fusionsolar.monitoring.dto.report.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class SimplePdfReportService {

    private static final double PAGE_WIDTH = 842;   // A4 landscape
    private static final double PAGE_HEIGHT = 595;
    private static final double TOP = 555;
    private static final double BOTTOM = 34;
    private static final double LEFT = 38;
    private static final int TEXT_WRAP = 118;
    private static final Charset PDF_CHARSET = Charset.forName("windows-1252");

    public byte[] generate(AiReportPreviewDto report) {
        List<PdfLine> lines = buildLines(report);
        List<List<PdfLine>> pages = paginate(lines);
        return writePdf(pages);
    }

    private List<PdfLine> buildLines(AiReportPreviewDto report) {
        List<PdfLine> lines = new ArrayList<>();
        String type = safe(report.getReportType()).toUpperCase(Locale.ROOT);
        boolean summary = "SUMMARY".equals(type);
        boolean detailed = false;

        lines.add(title("RAPPORT AI - PERFORMANCE PHOTOVOLTAIQUE"));
        lines.add(text("Période: " + period(report) + "   |   Périmètre: " + scope(report) + "   |   Type: " + reportTypeLabel(type)));
        lines.add(text("Généré le: " + safe(report.getGeneratedAt()) + "   |   Rédaction: Gemini API   |   Statut IA: " + safe(report.getAiStatus())));
        lines.add(blank());

        if (report.getAiNarrative() != null) {
            section(lines, "1. Résumé exécutif");
            paragraph(lines, report.getAiNarrative().getExecutiveSummary());
            addList(lines, "Points clés", report.getAiNarrative().getMainFindings(), 6);
        }

        section(lines, "2. KPI globaux");
        ReportGlobalKpisDto kpi = report.getGlobalKpis();
        if (kpi != null) {
            table(lines,
                    List.of("Indicateur", "Valeur", "Commentaire"),
                    List.of(24, 22, 70),
                    List.of(
                            List.of("Sites", n(kpi.getTotalSites()), "Nombre de sites dans le périmètre filtré"),
                            List.of("Healthy / Faulty / Offline", n(kpi.getHealthySites()) + " / " + n(kpi.getFaultySites()) + " / " + n(kpi.getDisconnectedSites()), "État global des sites"),
                            List.of("Puissance installée", d(kpi.getTotalInstalledPowerKw()) + " kWp", "Somme des puissances installées"),
                            List.of("Alertes actives", n(kpi.getActiveAlarms()), "Alertes actives au moment de génération"),
                            List.of("Statut global", safe(kpi.getGlobalStatus()), "Statut calculé par le backend")
                    ));
        }

        section(lines, "3. Production globale");
        ReportProductionSummaryDto prod = report.getProductionSummary();
        if (prod != null) {
            table(lines,
                    List.of("Métrique", "kWh / %", "Rôle dans le rapport"),
                    List.of(24, 18, 74),
                    List.of(
                            List.of("Production prévue", d(prod.getExpectedKwh()) + " kWh", "Référence théorique sur la période"),
                            List.of("Réel monitorisé", d(prod.getCapturedKwh()) + " kWh", "Données captées réellement"),
                            List.of("Estimé", d(prod.getEstimatedKwh()) + " kWh", "Complément pour les jours absents selon les règles métier"),
                            List.of("Production affichée", d(prod.getDisplayedKwh()) + " kWh", "Réel monitorisé + estimé"),
                            List.of("Écart", d(prod.getDifferenceKwh()) + " kWh / " + signed(prod.getDifferencePercent()) + " %", "Différence entre affiché et prévu"),
                            List.of("Bonus / Malus", n(prod.getBonusSites()) + " / " + n(prod.getMalusSites()), "Répartition des sites selon l’écart")
                    ));
        }

        section(lines, "4. Qualité des données");
        ReportDataQualityDto quality = report.getDataQuality();
        if (quality != null) {
            table(lines,
                    List.of("Indicateur", "Valeur", "Interprétation"),
                    List.of(28, 20, 68),
                    List.of(
                            List.of("Jours de production", n(quality.getExpectedProductionDays()), "Jours attendus dans le périmètre"),
                            List.of("Jours monitorisés", n(quality.getCapturedDays()), "Jours avec données réelles"),
                            List.of("Jours estimés", n(quality.getEstimatedDays()), "Jours calculés par estimation"),
                            List.of("Fiabilité", d(quality.getReliabilityPercent()) + " %", "Plus la valeur est élevée, plus le rapport est fiable"),
                            List.of("Part estimée", d(quality.getEstimatedSharePercent()) + " %", "Part calculée, à surveiller si élevée"),
                            List.of("Statut qualité", safe(quality.getStatus()), "Qualification globale des données")
                    ));
        }

        if (!summary) {
            section(lines, "5. Analyse par zone");
            List<List<String>> zoneRows = new ArrayList<>();
            if (report.getZoneAnalysis() != null) {
                for (ReportZoneAnalysisDto row : report.getZoneAnalysis()) {
                    zoneRows.add(List.of(
                            safe(row.getZone()), n(row.getSitesCount()), d(row.getInstalledPowerKw()),
                            d(row.getExpectedKwh()), d(row.getActualKwh()), d(row.getEstimatedKwh()),
                            signed(row.getDifferencePercent()) + " %", n(row.getActiveAlarms())
                    ));
                }
            }
            table(lines, List.of("Zone", "Sites", "kWp", "Prévu", "Affiché", "Estimé", "Écart", "Alertes"),
                    List.of(12, 6, 9, 12, 12, 10, 10, 8), zoneRows);

            section(lines, "6. Analyse par gouvernorat");
            List<List<String>> govRows = new ArrayList<>();
            if (report.getGovernorateAnalysis() != null) {
                int limit = detailed ? report.getGovernorateAnalysis().size() : 14;
                report.getGovernorateAnalysis().stream().limit(limit).forEach(row -> govRows.add(List.of(
                        safe(row.getGovernorate()), safe(row.getZone()), n(row.getSitesCount()),
                        d(row.getInstalledPowerKw()), d(row.getExpectedKwh()), d(row.getActualKwh()),
                        signed(row.getDifferencePercent()) + " %", n(row.getActiveAlarms())
                )));
            }
            table(lines, List.of("Gouv", "Zone", "Sites", "kWp", "Prévu", "Affiché", "Écart", "Alertes"),
                    List.of(8, 10, 6, 9, 12, 12, 10, 8), govRows);
        }

        section(lines, summary ? "5. Sites faibles prioritaires" : "7. Sites performants");
        if (!summary) {
            rankingTable(lines, report.getBestSites(), detailed ? 12 : 8);
            section(lines, "8. Sites faibles / priorités");
        }
        rankingTable(lines, report.getWorstSites(), summary ? 5 : (detailed ? 12 : 8));

        if (!summary) {
            section(lines, "9. Alertes actives");
            addAlarms(lines, report.getAlarms(), detailed ? 10 : 6);
        }

        section(lines, summary ? "6. Recommandations techniques" : "10. Recommandations techniques");
        addRecommendations(lines, report.getRecommendations(), detailed ? 10 : 6);

        if (report.getAiNarrative() != null) {
            section(lines, summary ? "7. Conclusion" : "11. Conclusion");
            paragraph(lines, report.getAiNarrative().getConclusion());
        }

        if (!summary) {
            section(lines, "12. Annexe technique" + (detailed ? " complète" : " courte"));
            technicalAppendix(lines, report.getTechnicalAppendix(), detailed ? Integer.MAX_VALUE : 80);
        }
        return lines;
    }

    private void addAlarms(List<PdfLine> lines, ReportAlarmSummaryDto alarms, int limit) {
        if (alarms == null || alarms.getActiveCount() == null || alarms.getActiveCount() == 0) {
            lines.add(text("Aucune alerte active détectée sur le périmètre du rapport."));
            return;
        }
        table(lines,
                List.of("Indicateur", "Valeur", "Commentaire"),
                List.of(24, 14, 78),
                List.of(
                        List.of("Alertes actives", n(alarms.getActiveCount()), "Total des alertes non résolues"),
                        List.of("Critiques", n(alarms.getCriticalCount()), "À traiter en priorité"),
                        List.of("Warning", n(alarms.getWarningCount()), "À surveiller"),
                        List.of("Historique", n(alarms.getHistoryCount()), "Alertes passées conservées")
                ));
        if (alarms.getByType() != null && !alarms.getByType().isEmpty()) {
            List<List<String>> typeRows = alarms.getByType().stream()
                    .map(t -> List.of(safe(t.getType()), safe(t.getTitle()), n(t.getCount())))
                    .toList();
            table(lines, List.of("Type", "Libellé", "Nombre"), List.of(28, 68, 10), typeRows);
        }
        if (alarms.getTopActiveAlarms() != null && !alarms.getTopActiveAlarms().isEmpty()) {
            List<List<String>> rows = alarms.getTopActiveAlarms().stream().limit(limit)
                    .map(a -> List.of(safe(a.getSeverity()), safe(a.getSiteName()), safe(a.getGovernorate()), safe(a.getTitle()), safe(a.getMessage())))
                    .toList();
            table(lines, List.of("Sévérité", "Site", "Gouv", "Titre", "Message"),
                    List.of(10, 22, 6, 26, 56), rows);
        }
    }

    private void addRecommendations(List<PdfLine> lines, List<ReportRecommendationDto> recommendations, int limit) {
        if (recommendations == null || recommendations.isEmpty()) {
            lines.add(text("Aucune recommandation spécifique n’a été générée."));
            return;
        }
        List<List<String>> rows = recommendations.stream().limit(limit)
                .map(rec -> List.of(
                        safe(rec.getPriority()), safe(rec.getTarget()), safe(rec.getIssue()),
                        safe(rec.getAction()), safe(rec.getJustification())
                ))
                .toList();
        table(lines, List.of("Priorité", "Cible", "Problème", "Action", "Justification"),
                List.of(10, 22, 30, 48, 42), rows);
    }

    private void rankingTable(List<PdfLine> lines, List<ReportSiteRankingDto> ranking, int limit) {
        if (ranking == null || ranking.isEmpty()) {
            lines.add(text("Aucun site dans cette section."));
            return;
        }
        List<List<String>> rows = ranking.stream().limit(limit)
                .map(site -> List.of(
                        safe(site.getSiteName()), safe(site.getGovernorate()), safe(site.getStatus()),
                        d(site.getExpectedKwh()), d(site.getActualKwh()), d(site.getEstimatedKwh()),
                        signed(site.getDifferencePercent()) + " %", safe(site.getPriority()), safe(site.getReason())
                ))
                .toList();
        table(lines, List.of("Site", "Gouv", "Statut", "Prévu", "Affiché", "Estimé", "Écart", "Priorité", "Raison"),
                List.of(22, 6, 12, 10, 10, 10, 9, 10, 34), rows);
    }

    private void technicalAppendix(List<PdfLine> lines, List<ReportTechnicalRowDto> appendix, int limit) {
        if (appendix == null || appendix.isEmpty()) {
            lines.add(text("Aucune ligne technique disponible."));
            return;
        }
        List<List<String>> rows = appendix.stream().limit(limit)
                .map(row -> List.of(
                        safe(row.getSiteName()), safe(row.getBrand()), safe(row.getStatus()),
                        safe(row.getZone()), safe(row.getGovernorate()), d(row.getInstalledPowerKw()),
                        n(row.getProductionDaysCount()), n(row.getCapturedDaysCount()), n(row.getEstimatedDaysCount()),
                        d(row.getExpectedKwh()), d(row.getCapturedKwh()), d(row.getEstimatedKwh()),
                        d(row.getDisplayedKwh()), signed(row.getDifferencePercent()) + " %", safe(row.getDataStatus())
                ))
                .toList();
        table(lines,
                List.of("Site", "Brand", "Statut", "Zone", "Gouv", "kWp", "Prod", "Capt", "Est", "Prévu", "Réel", "Estimé", "Affiché", "Écart", "Data"),
                List.of(18, 8, 10, 7, 6, 6, 5, 5, 5, 9, 9, 9, 9, 8, 16), rows);
        if (appendix.size() > limit) {
            lines.add(text("Annexe limitée à " + limit + " lignes dans ce type de rapport."));
        }
    }

    private void section(List<PdfLine> lines, String title) {
        lines.add(blank());
        lines.add(section(title));
        lines.add(tableLine("-".repeat(130), false));
    }

    private void paragraph(List<PdfLine> lines, String text) {
        if (text == null || text.isBlank()) return;
        for (String wrapped : wrap(text, TEXT_WRAP)) {
            lines.add(text(wrapped));
        }
    }

    private void addList(List<PdfLine> lines, String title, List<String> values, int limit) {
        if (values == null || values.isEmpty()) return;
        lines.add(text(title + ":"));
        values.stream().limit(limit).forEach(value -> paragraph(lines, "- " + value));
    }

    private void table(List<PdfLine> lines, List<String> headers, List<Integer> widths, List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) {
            lines.add(text("Aucune donnée disponible."));
            return;
        }
        lines.add(tableLine(formatRow(headers, widths), true));
        lines.add(tableLine(separator(widths), false));
        for (List<String> row : rows) {
            lines.add(tableLine(formatRow(row, widths), false));
        }
    }

    private String formatRow(List<String> cells, List<Integer> widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.size(); i++) {
            String raw = i < cells.size() ? cells.get(i) : "";
            sb.append(pad(clip(clean(raw), widths.get(i)), widths.get(i)));
            if (i < widths.size() - 1) sb.append("  ");
        }
        return sb.toString();
    }

    private String separator(List<Integer> widths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < widths.size(); i++) {
            sb.append("-".repeat(widths.get(i)));
            if (i < widths.size() - 1) sb.append("  ");
        }
        return sb.toString();
    }

    private String pad(String value, int width) {
        if (value.length() >= width) return value;
        return value + " ".repeat(width - value.length());
    }

    private String clip(String value, int width) {
        if (value.length() <= width) return value;
        if (width <= 1) return value.substring(0, width);
        return value.substring(0, width - 1) + "...";
    }

    private String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim();
    }

    private List<List<PdfLine>> paginate(List<PdfLine> input) {
        List<List<PdfLine>> pages = new ArrayList<>();
        List<PdfLine> current = new ArrayList<>();
        double used = 0;
        double max = TOP - BOTTOM;
        for (PdfLine line : input) {
            if (used + line.leading > max && !current.isEmpty()) {
                pages.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(line);
            used += line.leading;
        }
        if (!current.isEmpty()) pages.add(current);
        if (pages.isEmpty()) pages.add(List.of(text("Rapport vide.")));
        return pages;
    }

    private List<String> wrap(String text, int maxChars) {
        String safe = clean(text);
        if (safe.length() <= maxChars) return List.of(safe);
        List<String> out = new ArrayList<>();
        String remaining = safe;
        while (remaining.length() > maxChars) {
            int cut = remaining.lastIndexOf(' ', maxChars);
            if (cut < 45) cut = maxChars;
            out.add(remaining.substring(0, cut).trim());
            remaining = remaining.substring(Math.min(cut + 1, remaining.length())).trim();
        }
        if (!remaining.isEmpty()) out.add(remaining);
        return out;
    }

    private byte[] writePdf(List<List<PdfLine>> pages) {
        try {
            List<byte[]> objects = new ArrayList<>();
            int fontBold = 3;
            int fontText = 4;
            int fontMono = 5;
            int fontMonoBold = 6;
            List<Integer> pageObjects = new ArrayList<>();
            List<Integer> contentObjects = new ArrayList<>();

            for (int i = 0; i < pages.size(); i++) {
                pageObjects.add(7 + i * 2);
                contentObjects.add(8 + i * 2);
            }

            String kids = pageObjects.stream().map(id -> id + " 0 R").reduce("", (a, b) -> a + " " + b).trim();
            objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(PDF_CHARSET));
            objects.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>").getBytes(PDF_CHARSET));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes(PDF_CHARSET));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes(PDF_CHARSET));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>".getBytes(PDF_CHARSET));
            objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>".getBytes(PDF_CHARSET));

            for (int i = 0; i < pages.size(); i++) {
                int contentObj = contentObjects.get(i);
                String page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_WIDTH + " " + (int) PAGE_HEIGHT + "] "
                        + "/Resources << /Font << /F1 " + fontBold + " 0 R /F2 " + fontText + " 0 R /F3 " + fontMono + " 0 R /F4 " + fontMonoBold + " 0 R >> >> "
                        + "/Contents " + contentObj + " 0 R >>";
                objects.add(page.getBytes(PDF_CHARSET));
                String content = contentStream(pages.get(i), i + 1, pages.size());
                byte[] contentBytes = content.getBytes(PDF_CHARSET);
                String streamObject = "<< /Length " + contentBytes.length + " >>\nstream\n" + content + "\nendstream";
                objects.add(streamObject.getBytes(PDF_CHARSET));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("%PDF-1.4\n".getBytes(PDF_CHARSET));
            List<Integer> offsets = new ArrayList<>();
            offsets.add(0);
            for (int i = 0; i < objects.size(); i++) {
                offsets.add(out.size());
                out.write(((i + 1) + " 0 obj\n").getBytes(PDF_CHARSET));
                out.write(objects.get(i));
                out.write("\nendobj\n".getBytes(PDF_CHARSET));
            }
            int xref = out.size();
            out.write(("xref\n0 " + offsets.size() + "\n").getBytes(PDF_CHARSET));
            out.write("0000000000 65535 f \n".getBytes(PDF_CHARSET));
            for (int i = 1; i < offsets.size(); i++) {
                out.write(String.format(Locale.ROOT, "%010d 00000 n \n", offsets.get(i)).getBytes(PDF_CHARSET));
            }
            out.write(("trailer\n<< /Size " + offsets.size() + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF").getBytes(PDF_CHARSET));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to generate PDF report", e);
        }
    }

    private String contentStream(List<PdfLine> lines, int pageNumber, int totalPages) {
        StringBuilder sb = new StringBuilder();
        double y = TOP;
        for (PdfLine line : lines) {
            if (!line.text.isBlank()) {
                sb.append("BT ")
                        .append("/").append(line.font).append(" ")
                        .append(String.format(Locale.ROOT, "%.1f", line.size)).append(" Tf ")
                        .append(String.format(Locale.ROOT, "%.1f %.1f", line.x, y)).append(" Td ")
                        .append("(").append(escapePdf(line.text)).append(") Tj ET\n");
            }
            y -= line.leading;
        }
        sb.append("BT /F2 8 Tf ")
                .append(String.format(Locale.ROOT, "%.1f %.1f", PAGE_WIDTH - 110, 18.0))
                .append(" Td (Page ").append(pageNumber).append(" / ").append(totalPages).append(") Tj ET\n");
        return sb.toString();
    }

    private String escapePdf(String text) {
        return (text == null ? "" : text)
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private PdfLine title(String value) { return new PdfLine(value, "F1", 17.0, 22.0, LEFT); }
    private PdfLine section(String value) { return new PdfLine(value, "F1", 12.2, 17.0, LEFT); }
    private PdfLine text(String value) { return new PdfLine(value, "F2", 10.2, 13.5, LEFT); }
    private PdfLine tableLine(String value, boolean header) { return new PdfLine(value, header ? "F4" : "F3", 7.1, 9.3, LEFT); }
    private PdfLine blank() { return new PdfLine("", "F2", 8.0, 7.0, LEFT); }

    private String period(AiReportPreviewDto report) {
        if (report.getPeriod() == null) return "-";
        return safe(report.getPeriod().getStartDate()) + " -> " + safe(report.getPeriod().getEndDate())
                + " (" + n(report.getPeriod().getDaysCount()) + " jours)";
    }

    private String scope(AiReportPreviewDto report) {
        String base = safe(report.getScope());
        return report.getScopeValue() == null || report.getScopeValue().isBlank() ? base : base + " / " + report.getScopeValue();
    }

    private String reportTypeLabel(String type) {
        return switch (type) {
            case "SUMMARY" -> "Résumé - lecture rapide";
            default -> "Standard - rapport mensuel";
        };
    }

    private String safe(String value) { return value == null || value.isBlank() ? "-" : value; }
    private String n(Integer value) { return value == null ? "0" : String.valueOf(value); }
    private String d(Double value) { return value == null ? "0.00" : String.format(Locale.FRANCE, "%.2f", value); }
    private String signed(Double value) { return value == null ? "-" : (value > 0 ? "+" : "") + d(value); }

    private static class PdfLine {
        final String text;
        final String font;
        final double size;
        final double leading;
        final double x;

        PdfLine(String text, String font, double size, double leading, double x) {
            this.text = text == null ? "" : text;
            this.font = font;
            this.size = size;
            this.leading = leading;
            this.x = x;
        }
    }
}
