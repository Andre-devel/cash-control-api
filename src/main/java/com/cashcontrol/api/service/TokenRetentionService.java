package com.cashcontrol.api.service;

public interface TokenRetentionService {

    void purgeExpiredPasswordResetTokens();

    void purgeExpiredVerificationTokens();
}
