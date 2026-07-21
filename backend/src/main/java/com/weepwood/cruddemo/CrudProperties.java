package com.weepwood.cruddemo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "app.crud")
class CrudProperties {
    private List<String> allowedSchemas = new ArrayList<>();
    private int maxPageSize = 200;
    private boolean readOnly;
    private String username = "admin";
    private String password = "change-me-now";
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173", "http://localhost:8080"));

    public List<String> getAllowedSchemas() { return allowedSchemas; }
    public void setAllowedSchemas(List<String> value) { allowedSchemas = value == null ? new ArrayList<>() : value; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int value) { maxPageSize = Math.max(1, value); }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean value) { readOnly = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> value) { allowedOrigins = value == null ? new ArrayList<>() : value; }
}
