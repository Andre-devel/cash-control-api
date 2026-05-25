package com.cashcontrol.api.service;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.util.DataMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("!test & !dev")
@RequiredArgsConstructor
public class SmtpEmailService implements EmailService {

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;
    private final DataMasker dataMasker;

    @Override
    public void sendEmailVerification(String toEmail, String verificationToken, String displayName) {
        String link = appProperties.getBaseUrl() + "/auth/verify-email?token=" + verificationToken;
        String text = buildVerificationBody(displayName, link,
                appProperties.getSecurity().getEmailVerificationExpiryHours());
        sendEmail(toEmail, "Verify your email address", text);
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken, String displayName) {
        String link = appProperties.getBaseUrl() + "/auth/reset-password?token=" + resetToken;
        String text = buildPasswordResetBody(displayName, link,
                appProperties.getSecurity().getPasswordResetExpiryMinutes());
        sendEmail(toEmail, "Reset your password", text);
    }

    @Override
    public void sendAccountAlreadyExistsEmail(String toEmail) {
        String text = "An account with this email address already exists.\n\n" +
                "If you did not attempt to register, please ignore this email.\n\n" +
                "To log in, visit: " + appProperties.getBaseUrl() + "/auth/login";
        sendEmail(toEmail, "Account already exists", text);
    }

    @Override
    public void sendEmailChangeVerification(String newEmail, String verificationToken) {
        String link = appProperties.getBaseUrl() + "/auth/verify-email?token=" + verificationToken;
        String text = "Please verify your new email address by clicking the link below:\n\n" + link +
                "\n\nThis link will expire in " +
                appProperties.getSecurity().getEmailVerificationExpiryHours() + " hours.\n\n" +
                "If you did not request an email change, please contact support immediately.";
        sendEmail(newEmail, "Verify your new email address", text);
    }

    private void sendEmail(String toEmail, String subject, String text) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(toEmail);
            message.setSubject(subject);
            message.setText(text);
            mailSender.send(message);
            log.debug("Email sent to {}", dataMasker.maskEmail(toEmail));
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", dataMasker.maskEmail(toEmail), e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }

    private String buildVerificationBody(String displayName, String link, int expiryHours) {
        String greeting = (displayName != null && !displayName.isBlank())
                ? "Hello " + displayName + ","
                : "Hello,";
        return greeting + "\n\nPlease verify your email address by clicking the link below:\n\n" +
                link + "\n\nThis link will expire in " + expiryHours + " hours.\n\n" +
                "If you did not create an account, you can safely ignore this email.";
    }

    private String buildPasswordResetBody(String displayName, String link, int expiryMinutes) {
        String greeting = (displayName != null && !displayName.isBlank())
                ? "Hello " + displayName + ","
                : "Hello,";
        return greeting + "\n\nYou requested a password reset. " +
                "Click the link below to set a new password:\n\n" +
                link + "\n\nThis link will expire in " + expiryMinutes + " minutes.\n\n" +
                "If you did not request a password reset, you can safely ignore this email.";
    }
}