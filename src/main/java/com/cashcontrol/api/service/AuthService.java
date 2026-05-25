package com.cashcontrol.api.service;

import com.cashcontrol.api.dto.request.ChangePasswordRequest;
import com.cashcontrol.api.dto.request.LoginRequest;
import com.cashcontrol.api.dto.request.RegisterRequest;
import com.cashcontrol.api.dto.response.AuthResponse;
import com.cashcontrol.api.dto.response.MessageResponse;

import java.util.UUID;

public interface AuthService {

    MessageResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String ipAddress, String userAgent);

    void logout(UUID userId);

    void changePassword(UUID userId, ChangePasswordRequest request);
}