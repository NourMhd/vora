package com.fusionsolar.monitoring.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Strict inventory parsing: a missing array is not an empty inventory. */
public record ProviderInventoryPage(List<JsonNode> records, Integer total) {
    public static ProviderInventoryPage parse(JsonNode data, String... names) {
        if (data == null || data.isNull() || data.isMissingNode())
            throw new IllegalStateException("Provider inventory payload missing");
        List<JsonNode> containers = List.of(data, data.path("page"), data.path("data"),
                data.path("data").path("page"));
        JsonNode array = data.isArray() ? data : null;
        Integer total = null;
        for (JsonNode container : containers) {
            for (String key : new String[]{"total", "totalCount", "recordCount", "count"}) {
                JsonNode value = container.path(key);
                if ("count".equals(key) && total != null) continue;
                if (!value.isMissingNode() && !value.isNull()) {
                    try {
                        int parsed = Integer.parseInt(value.asText());
                        if (parsed < 0) throw new NumberFormatException();
                        if (total != null && total != parsed)
                            throw new IllegalStateException("Conflicting inventory totals");
                        total = parsed;
                    } catch (NumberFormatException e) {
                        throw new IllegalStateException("Invalid inventory total", e);
                    }
                }
            }
            if (array == null && container.isArray()) array = container;
            for (String name : names) {
                if (array == null && container.path(name).isArray()) array = container.path(name);
            }
        }
        if (array == null) throw new IllegalStateException("Provider inventory array missing");
        List<JsonNode> records = new ArrayList<>();
        array.forEach(records::add);
        return new ProviderInventoryPage(records, total);
    }

    public boolean hasNext(int received, int pageSize) {
        if (total != null) {
            if (received > total) throw new IllegalStateException("Inventory exceeds advertised total");
            if (records.isEmpty() && received < total)
                throw new IllegalStateException("Inventory ended before advertised total");
            return received < total;
        }
        return records.size() >= pageSize;
    }
}
