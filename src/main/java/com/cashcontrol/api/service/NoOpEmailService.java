package com.cashcontrol.api.service;

import com.cashcontrol.api.util.DataMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@Profile({"test", "dev"})
public class NoOpEmailService implements EmailService {

    private final DataMasker dataMasker;
    private final List<SentEmail> sentEmails = new CopyOnWriteArrayList<>();

    public NoOpEmailService(DataMasker dataMasker) {
        this.dataMasker = dataMasker;
    }

    @Override
    public void sendEmailVerification(String toEmail, String verificationToken, String displayName) {
        log.info("[NoOp] Verification email to {} | token={}", dataMasker.maskEmail(toEmail), verificationToken);
        sentEmails.add(new SentEmail(toEmail, EmailType.VERIFICATION));
    }

    @Override
    public void sendPasswordResetEmail(String toEmail, String resetToken, String displayName) {
        log.info("[NoOp] Password reset email to {} | token={}", dataMasker.maskEmail(toEmail), resetToken);
        sentEmails.add(new SentEmail(toEmail, EmailType.PASSWORD_RESET));
    }

    @Override
    public void sendAccountAlreadyExistsEmail(String toEmail) {
        log.info("[NoOp] Account-already-exists email to {}", dataMasker.maskEmail(toEmail));
        sentEmails.add(new SentEmail(toEmail, EmailType.ACCOUNT_ALREADY_EXISTS));
    }

    @Override
    public void sendEmailChangeVerification(String newEmail, String verificationToken) {
        log.info("[NoOp] Email-change verification to {}", dataMasker.maskEmail(newEmail));
        sentEmails.add(new SentEmail(newEmail, EmailType.EMAIL_CHANGE_VERIFICATION));
    }

    public List<SentEmail> getSentEmails() {
        return List.copyOf(sentEmails);
    }

    public void clearSentEmails() {
        sentEmails.clear();
    }

    public boolean wasEmailSentTo(String recipient, EmailType type) {
        return sentEmails.stream()
                .anyMatch(e -> e.recipient().equals(recipient) && e.type() == type);
    }

    public enum EmailType {
        VERIFICATION,
        PASSWORD_RESET,
        ACCOUNT_ALREADY_EXISTS,
        EMAIL_CHANGE_VERIFICATION
    }

    public record SentEmail(String recipient, EmailType type) {}
}