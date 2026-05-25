package com.cashcontrol.api.service;

import java.util.UUID;

public interface EmailVerificationService {

    void verifyEmail(String rawToken);

    void resendVerification(String email);

    void initiateEmailChange(UUID userId, String newEmail);
}