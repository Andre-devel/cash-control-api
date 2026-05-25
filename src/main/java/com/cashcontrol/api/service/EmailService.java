package com.cashcontrol.api.service;

public interface EmailService {

    void sendEmailVerification(String toEmail, String verificationToken, String displayName);

    void sendPasswordResetEmail(String toEmail, String resetToken, String displayName);

    void sendAccountAlreadyExistsEmail(String toEmail);

    void sendEmailChangeVerification(String newEmail, String verificationToken);
}