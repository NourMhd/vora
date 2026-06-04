package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class AuthResultDto {
    private Boolean success;
    private String data;
    private Integer failCode;
    private String message;
    private String xsrfToken;
    private List<String> cookies;
}