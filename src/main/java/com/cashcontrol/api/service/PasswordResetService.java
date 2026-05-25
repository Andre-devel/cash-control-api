package com.cashcontrol.api.service;

public interface PasswordResetService {

    void initiateReset(String email);

    void completeReset(String rawToken, String newPassword);
}