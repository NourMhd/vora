package com.fusionsolar.monitoring.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.dto.report.AiNarrativeDto;
import com.fusionsolar.monitoring.dto.report.AiReportPreviewDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiReportNarrativeService {

    private final ObjectMapper objectMapper;

    @Value("${app.ai-report.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}")
    private String geminiBaseUrl;

    @Value("${app.ai-report.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai-report.gemini.model:gemini-2.5-flash}")
    private String geminiModel;

    @Value("${app.ai-report.gemini.timeout-seconds:180}")
    private int geminiTimeoutSeconds;

    @Value("${app.ai-report.gemini.max-output-tokens:65000}")
    private int geminiMaxOutputTokens;

    public GeminiReportNarrativeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NarrativeResult generate(AiReportPreviewDto preview) {
        validateConfiguration();

        String prompt;
        try {
            prompt = buildPrompt(preview);
        } catch (Exception e) {
            throw new GeminiAiUnavailableException("Impossible de préparer le prompt du rapport IA Gemini : " + compactMessage(e), e);
        }

        try {
            String text = callGeminiStructured(
                    prompt,
                    maxTokensFor(preview),
                    Duration.ofSeconds(Math.max(60, geminiTimeoutSeconds))
            );

            try {
                AiNarrativeDto narrative = parseNarrativeStrict(text);
                return new NarrativeResult(narrative, "GEMINI_OK");
            } catch (Exception parseException) {
                AiNarrativeDto repaired = repairNarrativeWithGemini(text, parseException);
                return new NarrativeResult(repaired, "GEMINI_OK_JSON_REPAIRED");
            }
        } catch (GeminiAiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new GeminiAiUnavailableException(
                    "Rapport AI non généré : Gemini est obligatoire et la génération a échoué. "
                            + compactMessage(e),
                    e
            );
        }
    }

    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "gemini");
        result.put("model", cleanModel());
        result.put("baseUrl", cleanUrl(geminiBaseUrl));

        try {
            validateConfiguration();

            String text = callGeminiSimple(
                    "Réponds uniquement en JSON: {\"message\":\"OK\"}",
                    60,
                    Duration.ofSeconds(45)
            );

            result.put("status", "UP");
            result.put("testResponse", text);
            return result;
        } catch (Exception e) {
            result.put("status", "DOWN");
            result.put("error", compactMessage(e));
            result.put("commands", List.of(
                    "Vérifier app.ai-report.gemini.api-key dans application.yml",
                    "Vérifier que la clé vient du projet Gemini dans Google AI Studio",
                    "Vérifier la connexion Internet",
                    "Tester /api/ai-report/gemini-health"
            ));
            return result;
        }
    }

    private String callGeminiStructured(String prompt, int maxOutputTokens, Duration timeout) throws Exception {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0.1);
        generationConfig.put("topP", 0.8);
        generationConfig.put("maxOutputTokens", Math.max(2048, maxOutputTokens));
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", narrativeSchema());
        return callGemini(prompt, generationConfig, timeout);
    }

    private String callGeminiSimple(String prompt, int maxOutputTokens, Duration timeout) throws Exception {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", 0);
        generationConfig.put("maxOutputTokens", Math.max(30, maxOutputTokens));
        generationConfig.put("responseMimeType", "application/json");
        return callGemini(prompt, generationConfig, timeout);
    }

    private String callGemini(String prompt, Map<String, Object> generationConfig, Duration timeout) throws Exception {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("contents", List.of(
                Map.of("parts", List.of(Map.of("text", prompt)))
        ));
        requestBody.put("generationConfig", generationConfig);

        HttpResponse<String> response = postJson(
                generateContentUrl(),
                requestBody,
                timeout
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new GeminiAiUnavailableException(
                    "Gemini API HTTP " + response.statusCode() + " - " + truncate(response.body(), 900)
            );
        }

        return extractText(response.body());
    }

    private AiNarrativeDto repairNarrativeWithGemini(String invalidText, Exception parseException) {
        try {
            String repairPrompt = """
                    Tu es un validateur JSON strict.
                    La réponse Gemini suivante est une réponse IA valide sur le fond, mais son JSON est invalide ou tronqué.
                    Répare-la en retournant uniquement un JSON strict compatible avec ce format exact :
                    {
                      "executiveSummary": "texte court",
                      "mainFindings": ["constat 1", "constat 2", "constat 3"],
                      "riskAnalysis": "texte court",
                      "recommendedActions": ["action 1", "action 2", "action 3"],
                      "conclusion": "texte court"
                    }
                    Règles :
                    - Ne mets aucun markdown.
                    - Ne mets aucun texte avant ou après le JSON.
                    - Si une phrase est tronquée, termine-la proprement sans inventer de nouveau KPI.
                    - Garde le rapport en français.

                    Réponse JSON invalide à réparer :
                    """ + truncateForPrompt(invalidText, 7000);

            String repairedText = callGeminiStructured(
                    repairPrompt,
                    Math.max(4096, Math.min(65000, geminiMaxOutputTokens)),
                    Duration.ofSeconds(Math.max(60, geminiTimeoutSeconds))
            );
            return parseNarrativeStrict(repairedText);
        } catch (Exception repairException) {
            return buildNarrativeFromRawGeminiText(invalidText, parseException, repairException);
        }
    }

    private AiNarrativeDto buildNarrativeFromRawGeminiText(String rawText, Exception parseException, Exception repairException) {
        String cleaned = normalizeReadableText(rawText);
        if (cleaned.isBlank()) {
            cleaned = "Gemini a retourné une réponse non exploitable en JSON strict.";
        }
        String summary = "Gemini a généré une analyse, mais la réponse JSON était partiellement invalide. "
                + "Le backend a conservé le contenu IA Gemini pour ne pas bloquer le rapport. "
                + truncate(cleaned, 900);

        List<String> findings = new ArrayList<>();
        String executive = extractLooseString(rawText, "executiveSummary");
        String risk = extractLooseString(rawText, "riskAnalysis");
        String conclusion = extractLooseString(rawText, "conclusion");
        findings.addAll(extractLooseArray(rawText, "mainFindings"));
        if (findings.isEmpty() && !executive.isBlank()) findings.add(truncate(executive, 220));
        if (findings.isEmpty() && !risk.isBlank()) findings.add(truncate(risk, 220));
        if (findings.isEmpty()) findings.add("Analyse IA Gemini reçue, mais normalisée car le JSON était incomplet.");

        List<String> actions = extractLooseArray(rawText, "recommendedActions");
        if (actions.isEmpty()) {
            actions.add("Relancer la génération si un niveau de détail plus long est nécessaire.");
            actions.add("Limiter temporairement le rapport au type Résumé si le JSON Gemini est tronqué.");
            actions.add("Conserver la génération Gemini only avec max-output-tokens augmenté.");
        }

        return AiNarrativeDto.builder()
                .executiveSummary(executive.isBlank() ? summary : executive)
                .mainFindings(findings.stream().limit(6).toList())
                .riskAnalysis(risk.isBlank()
                        ? "La réponse Gemini a été reçue, mais le JSON n'était pas complètement valide. Erreur initiale : " + compactMessage(parseException)
                        : risk)
                .recommendedActions(actions.stream().limit(6).toList())
                .conclusion(conclusion.isBlank()
                        ? "Rapport généré avec Gemini API en mode récupération JSON."
                        : conclusion)
                .build();
    }

    private HttpResponse<String> postJson(String url, Map<String, Object> requestBody, Duration timeout) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("Accept", "application/json")
                .header("x-goog-api-key", cleanApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(requestBody),
                        StandardCharsets.UTF_8
                ))
                .build();

        return httpClient(timeout).send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpClient httpClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(30, Math.max(5, timeout.toSeconds()))))
                .build();
    }

    private void validateConfiguration() {
        if (cleanApiKey().isBlank()
                || "PASTE_YOUR_GEMINI_API_KEY_HERE".equals(cleanApiKey())
                || "YOUR_GEMINI_API_KEY".equals(cleanApiKey())) {
            throw new GeminiAiUnavailableException("Configuration Gemini manquante : mettez votre vraie clé dans app.ai-report.gemini.api-key.");
        }
        if (cleanModel().isBlank()) {
            throw new GeminiAiUnavailableException("Configuration Gemini manquante : app.ai-report.gemini.model est vide.");
        }
        if (cleanUrl(geminiBaseUrl).isBlank()) {
            throw new GeminiAiUnavailableException("Configuration Gemini manquante : app.ai-report.gemini.base-url est vide.");
        }
    }

    private String generateContentUrl() {
        String model = URLEncoder.encode(cleanModel(), StandardCharsets.UTF_8);
        return cleanUrl(geminiBaseUrl) + "/models/" + model + ":generateContent";
    }

    private String cleanApiKey() {
        return geminiApiKey == null ? "" : geminiApiKey.trim();
    }

    private String cleanModel() {
        return geminiModel == null ? "gemini-2.5-flash" : geminiModel.trim();
    }

    private String cleanUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private int maxTokensFor(AiReportPreviewDto preview) {
        String type = preview == null || preview.getReportType() == null ? "STANDARD" : preview.getReportType().trim().toUpperCase();
        int configured = Math.max(4096, geminiMaxOutputTokens);
        return switch (type) {
            case "RESUME", "SUMMARY" -> Math.max(4096, Math.min(configured, 65000));
            default -> Math.max(65000, configured);
        };
    }

    private String buildPrompt(AiReportPreviewDto preview) throws Exception {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("period", preview.getPeriod());
        compact.put("scope", preview.getScope());
        compact.put("scopeValue", preview.getScopeValue());
        compact.put("reportType", preview.getReportType());
        compact.put("globalKpis", preview.getGlobalKpis());
        compact.put("productionSummary", preview.getProductionSummary());
        compact.put("dataQuality", preview.getDataQuality());
        compact.put("zoneAnalysis", safeLimit(preview.getZoneAnalysis(), 8));
        compact.put("governorateAnalysis", safeLimit(preview.getGovernorateAnalysis(), 12));
        compact.put("bestSites", safeLimit(preview.getBestSites(), 6));
        compact.put("worstSites", safeLimit(preview.getWorstSites(), 6));
        compact.put("alarms", preview.getAlarms());
        compact.put("recommendationsBackend", safeLimit(preview.getRecommendations(), 8));

        return """
                Tu es expert en monitoring photovoltaïque et performance énergétique.

                Génère la partie narrative d'un rapport AI professionnel en français.
                Analyse uniquement les chiffres fournis.
                Ne calcule pas de nouveaux KPI.
                N'invente aucune donnée.
                Ne modifie pas les valeurs numériques.
                Sois clair, concis, opérationnel et orienté décision.

                Contraintes de sortie obligatoires :
                - Répondre uniquement en JSON strict valide.
                - Respecter exactement les champs du schema.
                - Pas de markdown.
                - Pas de texte avant ou après le JSON.
                - Chaque champ texte doit rester court : maximum 900 caractères.
                - Chaque élément des tableaux doit rester court : maximum 220 caractères.
                - mainFindings : maximum 5 éléments.
                - recommendedActions : maximum 5 éléments.

                Données du rapport :
                """
                + objectMapper.writeValueAsString(compact);
    }

    private Map<String, Object> narrativeSchema() {
        Map<String, Object> stringField = Map.of("type", "string");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("executiveSummary", Map.of(
                "type", "string",
                "description", "Résumé exécutif professionnel de la période analysée. Maximum 900 caractères."
        ));
        properties.put("mainFindings", Map.of(
                "type", "array",
                "items", stringField,
                "description", "Maximum 5 constats clés, courts et basés uniquement sur les KPI fournis."
        ));
        properties.put("riskAnalysis", Map.of(
                "type", "string",
                "description", "Analyse courte des risques opérationnels, data quality et performance. Maximum 900 caractères."
        ));
        properties.put("recommendedActions", Map.of(
                "type", "array",
                "items", stringField,
                "description", "Maximum 5 actions prioritaires et concrètes."
        ));
        properties.put("conclusion", Map.of(
                "type", "string",
                "description", "Conclusion courte et exploitable. Maximum 600 caractères."
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "executiveSummary",
                "mainFindings",
                "riskAnalysis",
                "recommendedActions",
                "conclusion"
        ));
        return schema;
    }

    private List<?> safeLimit(List<?> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().limit(limit).toList();
    }

    private String extractText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) {
            String blockReason = root.path("promptFeedback").path("blockReason").asText("");
            throw new GeminiAiUnavailableException(
                    "Gemini n'a retourné aucun candidat"
                            + (blockReason.isBlank() ? "" : " (blockReason=" + blockReason + ")")
                            + ". Réponse : " + truncate(body, 700)
            );
        }

        JsonNode parts = candidates.get(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new GeminiAiUnavailableException("Réponse Gemini sans contenu exploitable : " + truncate(body, 700));
        }

        StringBuilder builder = new StringBuilder();
        parts.forEach(part -> {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                builder.append(text);
            }
        });

        String text = builder.toString().trim();
        if (text.isBlank()) {
            throw new GeminiAiUnavailableException("Gemini a retourné une réponse vide. Réponse : " + truncate(body, 700));
        }
        return text;
    }

    private AiNarrativeDto parseNarrativeStrict(String text) throws Exception {
        String cleaned = normalizeJsonText(text);
        JsonNode root = objectMapper.readTree(cleaned);
        String executiveSummary = root.path("executiveSummary").asText("").trim();
        if (executiveSummary.isBlank()) {
            throw new GeminiAiUnavailableException(
                    "Gemini a retourné un JSON incomplet : executiveSummary est vide. Réponse : " + truncate(cleaned, 700)
            );
        }

        return AiNarrativeDto.builder()
                .executiveSummary(executiveSummary)
                .mainFindings(toStringList(root.path("mainFindings")))
                .riskAnalysis(root.path("riskAnalysis").asText(""))
                .recommendedActions(toStringList(root.path("recommendedActions")))
                .conclusion(root.path("conclusion").asText(""))
                .build();
    }

    private String normalizeJsonText(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isBlank()) {
            throw new GeminiAiUnavailableException("Gemini a retourné une réponse vide. Le rapport n’a pas été généré.");
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```(?:json)?", "")
                    .replaceFirst("```$", "")
                    .trim();
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1).trim();
        }
        return cleaned;
    }

    private List<String> toStringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isBlank()) {
                values.add(text);
            }
        });
        return values;
    }

    private String extractLooseString(String rawText, String fieldName) {
        if (rawText == null || rawText.isBlank()) return "";
        String marker = "\"" + fieldName + "\"";
        int field = rawText.indexOf(marker);
        if (field < 0) return "";
        int colon = rawText.indexOf(':', field + marker.length());
        if (colon < 0) return "";
        int firstQuote = rawText.indexOf('"', colon + 1);
        if (firstQuote < 0) return "";
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = firstQuote + 1; i < rawText.length(); i++) {
            char c = rawText.charAt(i);
            if (escaped) {
                value.append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                value.append(c);
            }
        }
        return value.toString().replaceAll("\\s+", " ").trim();
    }

    private List<String> extractLooseArray(String rawText, String fieldName) {
        if (rawText == null || rawText.isBlank()) return List.of();
        String marker = "\"" + fieldName + "\"";
        int field = rawText.indexOf(marker);
        if (field < 0) return List.of();
        int start = rawText.indexOf('[', field + marker.length());
        if (start < 0) return List.of();
        int end = rawText.indexOf(']', start + 1);
        String segment = rawText.substring(start + 1, end > start ? end : rawText.length());
        List<String> values = new ArrayList<>();
        int pos = 0;
        while (pos < segment.length()) {
            int q1 = segment.indexOf('"', pos);
            if (q1 < 0) break;
            StringBuilder item = new StringBuilder();
            boolean escaped = false;
            int i = q1 + 1;
            for (; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (escaped) {
                    item.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    break;
                } else {
                    item.append(c);
                }
            }
            String value = item.toString().replaceAll("\\s+", " ").trim();
            if (!value.isBlank()) values.add(value);
            pos = Math.max(i + 1, q1 + 1);
            if (values.size() >= 8) break;
        }
        return values;
    }

    private String normalizeReadableText(String text) {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```(?:json)?", "")
                    .replaceFirst("```$", "")
                    .trim();
        }
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    private String compactMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String type = root instanceof ConnectException ? "Connexion refusée" : root.getClass().getSimpleName();
        String message = root.getMessage() == null || root.getMessage().isBlank() ? e.getMessage() : root.getMessage();
        return type + (message == null || message.isBlank() ? "" : " : " + truncate(message, 500));
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLength) return clean;
        return clean.substring(0, maxLength) + "...";
    }

    private String truncateForPrompt(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength);
    }

    public static class GeminiAiUnavailableException extends RuntimeException {
        public GeminiAiUnavailableException(String message) {
            super(message);
        }

        public GeminiAiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record NarrativeResult(AiNarrativeDto narrative, String status) {
    }
}
