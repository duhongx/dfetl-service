package com.dfygt.dfetl.server.dto;

public record LoginResponse(String token, String refreshToken, String username, String role, long expiresIn) {}
