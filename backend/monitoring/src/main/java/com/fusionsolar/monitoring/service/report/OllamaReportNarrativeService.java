package com.fusionsolar.monitoring.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fusionsolar.monitoring.dto.report.AiNarrativeDto;
import com.fusionsolar.monitoring.dto.report.AiReportPreviewDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
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
@RequiredArgsConstructor
public class OllamaReportNarrativeService {

    private final ObjectMapper objectMapper;

    @Value("${app.ai-report.ollama-url:http://127.0.0.1:11434}")
    private String ollamaUrl;

    @Value("${app.ai-report.ollama-model:llama3.2:3b}")
    private String ollamaModel;

    public NarrativeResult generate(AiReportPreviewDto preview) {
        if (ollamaUrl == null || ollamaUrl.isBlank()) {
            throw new LocalAiUnavailableException("Configuration IA locale manquante : app.ai-report.ollama-url est vide.");
        }
        if (ollamaModel == null || ollamaModel.isBlank()) {
            throw new LocalAiUnavailableException("Configuration IA locale manquante : app.ai-report.ollama-model est vide.");
        }

        try {
            String prompt = buildPrompt(preview);

            Map<String, Object> requestBody = Map.of(
                    "model", ollamaModel.trim(),
                    "prompt", prompt,
                    "stream", false,
                    "format", "json",
                    "options", Map.of(
                            "temperature", 0.2,
                            "num_predict", 2800
                    )
            );

            String endpoint = ollamaUrl.replaceAll("/$", "") + "/api/generate";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(180))
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(requestBody),
                            StandardCharsets.UTF_8
                    ))
                    .build();

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LocalAiUnavailableException(
                        "Llama 3.2/Ollama a refusé la génération du rapport (HTTP "
                                + response.statusCode()
                                + "). Réponse Ollama : "
                                + response.body()
                );
            }

            String text = extractText(response.body());
            AiNarrativeDto narrative = parseNarrative(text);

            return new NarrativeResult(narrative, "OLLAMA_OK");

        } catch (LocalAiUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new LocalAiUnavailableException(
                    "Llama 3.2/Ollama est indisponible ou a retourné une réponse invalide. Le rapport n’a pas été généré. Détail : "
                            + e.getMessage(),
                    e
            );
        }
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
        compact.put("zoneAnalysis", preview.getZoneAnalysis());
        compact.put(
                "governorateAnalysis",
                preview.getGovernorateAnalysis() == null
                        ? null
                        : preview.getGovernorateAnalysis().stream().limit(12).toList()
        );
        compact.put("bestSites", preview.getBestSites());
        compact.put("worstSites", preview.getWorstSites());
        compact.put("alarms", preview.getAlarms());
        compact.put("recommendationsBackend", preview.getRecommendations());

        return """
                Tu es expert en monitoring photovoltaïque.

                Analyse uniquement les chiffres fournis.
                Ne calcule pas de nouveaux KPI.
                N'invente aucune donnée.
                Ne modifie pas les valeurs numériques.
                Utilise un style français professionnel, clair, concis et orienté action.

                Réponds uniquement en JSON strict, sans markdown, sans texte avant/après.
                Tous les champs doivent être présents.

                Structure exacte attendue :
                {
                  "executiveSummary": "string",
                  "mainFindings": ["string"],
                  "riskAnalysis": "string",
                  "recommendedActions": ["string"],
                  "conclusion": "string"
                }

                Données du rapport :
                """
                + objectMapper.writeValueAsString(compact);
    }

    private String extractText(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        String text = root.path("response").asText("").trim();
        if (text.isBlank()) {
            throw new LocalAiUnavailableException("Ollama a retourné une réponse vide. Réponse : " + body);
        }
        return text;
    }

    private AiNarrativeDto parseNarrative(String text) throws Exception {
        String cleaned = text == null ? "" : text.trim();
        if (cleaned.isBlank()) {
            throw new LocalAiUnavailableException("Llama 3.2 a retourné une réponse vide. Le rapport n’a pas été généré.");
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned
                    .replaceFirst("^```(?:json)?", "")
                    .replaceFirst("```$", "")
                    .trim();
        }

        JsonNode root = objectMapper.readTree(cleaned);
        String executiveSummary = root.path("executiveSummary").asText("").trim();
        if (executiveSummary.isBlank()) {
            throw new LocalAiUnavailableException(
                    "Llama 3.2 a retourné un JSON incomplet : executiveSummary est vide. Réponse : " + cleaned
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

    public static class LocalAiUnavailableException extends RuntimeException {
        public LocalAiUnavailableException(String message) {
            super(message);
        }

        public LocalAiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public record NarrativeResult(AiNarrativeDto narrative, String status) {
    }
}
