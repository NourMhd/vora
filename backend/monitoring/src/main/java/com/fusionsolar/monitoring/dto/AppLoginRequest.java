package com.fusionsolar.monitoring.dto;

import lombok.Data;

@Data
public class AppLoginRequest {
    private String username;
    private String password;
}