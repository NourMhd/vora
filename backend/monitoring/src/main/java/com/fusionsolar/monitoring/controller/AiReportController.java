package com.fusionsolar.monitoring.controller;

import com.fusionsolar.monitoring.dto.report.AiReportPreviewDto;
import com.fusionsolar.monitoring.service.report.AiReportService;
import com.fusionsolar.monitoring.service.report.GeminiReportNarrativeService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/ai-report")
public class AiReportController {

    private final AiReportService aiReportService;
    private final GeminiReportNarrativeService geminiReportNarrativeService;

    public AiReportController(
            AiReportService aiReportService,
            GeminiReportNarrativeService geminiReportNarrativeService
    ) {
        this.aiReportService = aiReportService;
        this.geminiReportNarrativeService = geminiReportNarrativeService;
    }

    @GetMapping("/preview")
    public AiReportPreviewDto preview(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(required = false) String scopeValue,
            @RequestParam(defaultValue = "STANDARD") String type,
            @RequestParam(defaultValue = "true") boolean useAi
    ) {
        return aiReportService.buildPreview(
                parseDate(startDate, "startDate"),
                parseDate(endDate, "endDate"),
                normalize(scope, "ALL"),
                scopeValue,
                normalizeReportType(type),
                useAi
        );
    }

    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "ALL") String scope,
            @RequestParam(required = false) String scopeValue,
            @RequestParam(defaultValue = "STANDARD") String type,
            @RequestParam(defaultValue = "true") boolean useAi
    ) {
        byte[] bytes = aiReportService.buildPdf(
                parseDate(startDate, "startDate"),
                parseDate(endDate, "endDate"),
                normalize(scope, "ALL"),
                scopeValue,
                normalizeReportType(type),
                useAi
        );

        String filename = "rapport-ai-" + startDate + "-" + endDate + ".pdf";
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }

    @GetMapping("/gemini-health")
    public Map<String, Object> geminiHealth() {
        return geminiReportNarrativeService.health();
    }

    @ExceptionHandler(GeminiReportNarrativeService.GeminiAiUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleGeminiAiError(GeminiReportNarrativeService.GeminiAiUnavailableException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "GEMINI_REQUIRED_FAILED");
        body.put("message", e.getMessage());
        body.put("status", 502);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "BAD_REQUEST");
        body.put("message", e.getMessage());
        body.put("status", 400);
        return ResponseEntity.badRequest().body(body);
    }

    private LocalDate parseDate(String raw, String fieldName) {
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " must use ISO format yyyy-MM-dd");
        }
    }

    private String normalizeReportType(String raw) {
        String value = normalize(raw, "STANDARD");
        return "SUMMARY".equals(value) ? "SUMMARY" : "STANDARD";
    }

    private String normalize(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw.trim();
        return value.toUpperCase(Locale.ROOT);
    }
}
