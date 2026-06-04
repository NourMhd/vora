package com.fusionsolar.monitoring.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class HuaweiApiEnvelope {

    private Boolean success;
    private Integer failCode;
    private String message;
    private JsonNode data;

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Integer getFailCode() {
        return failCode;
    }

    public void setFailCode(Integer failCode) {
        this.failCode = failCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }
}