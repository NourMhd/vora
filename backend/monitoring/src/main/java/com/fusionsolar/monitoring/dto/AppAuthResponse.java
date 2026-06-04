package com.fusionsolar.monitoring.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AppAuthResponse {
    private boolean authenticated;
    private String username;
    private String scope;
}
