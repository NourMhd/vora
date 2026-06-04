package com.fusionsolar.monitoring.dto;

import lombok.Data;

@Data
public class LoginResponse {
    private Boolean success;
    private String data;
    private Integer failCode;
    private String message;
}