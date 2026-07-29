package com.cashcontrol.api;

import com.cashcontrol.api.config.AppProperties;
import com.cashcontrol.api.service.SmtpEmailService;
import com.cashcontrol.api.util.DataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailService emailService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.setBaseUrl("http://app.example.com");
        props.setFrontendBaseUrl("http://front.example.com");
        props.getSecurity().setEmailVerificationExpiryHours(24);
        props.getSecurity().setPasswordResetExpiryMinutes(60);
        props.getMail().setFrom("noreply@app.example.com");
        emailService = new SmtpEmailService(mailSender, props, new DataMasker());
    }

    @Test
    void sendEmailVerification_sendsToCorrectRecipient() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        SimpleMailMessage msg = captor.getValue();
        assertThat(msg.getTo()).containsExactly("user@example.com");
    }

    @Test
    void sendEmailVerification_setsConfiguredFromAddress() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getFrom()).isEqualTo("noreply@app.example.com");
    }

    @Test
    void sendEmailVerification_subjectDoesNotContainToken() {
        emailService.sendEmailVerification("user@example.com", "tok-secret", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getSubject()).doesNotContain("tok-secret");
    }

    @Test
    void sendEmailVerification_bodyContainsVerificationLink() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("http://front.example.com/verify-email?token=tok-abc");
    }

    @Test
    void sendEmailVerification_bodyContainsExpiryHours() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText()).contains("24 hours");
    }

    @Test
    void sendEmailVerification_bodyContainsDisplayName() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", "Alice");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText()).contains("Alice");
    }

    @Test
    void sendEmailVerification_nullDisplayName_usesGenericGreeting() {
        emailService.sendEmailVerification("user@example.com", "tok-abc", null);

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText()).contains("Hello,");
    }

    @Test
    void sendPasswordResetEmail_sendsToCorrectRecipient() {
        emailService.sendPasswordResetEmail("user@example.com", "reset-token", "Bob");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getTo()).containsExactly("user@example.com");
    }

    @Test
    void sendPasswordResetEmail_bodyContainsResetLink() {
        emailService.sendPasswordResetEmail("user@example.com", "reset-token", "Bob");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("http://front.example.com/reset-password?token=reset-token");
    }

    @Test
    void sendPasswordResetEmail_bodyContainsExpiryMinutes() {
        emailService.sendPasswordResetEmail("user@example.com", "reset-token", "Bob");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText()).contains("60 minutes");
    }

    @Test
    void sendAccountAlreadyExistsEmail_sendsToCorrectRecipient() {
        emailService.sendAccountAlreadyExistsEmail("existing@example.com");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getTo()).containsExactly("existing@example.com");
    }

    @Test
    void sendAccountAlreadyExistsEmail_bodyDoesNotRevealPassword() {
        emailService.sendAccountAlreadyExistsEmail("existing@example.com");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        String body = captor.getValue().getText();
        assertThat(body).doesNotContainIgnoringCase("password");
    }

    @Test
    void sendEmailChangeVerification_sendsToNewEmail() {
        emailService.sendEmailChangeVerification("new@example.com", "change-token");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getTo()).containsExactly("new@example.com");
    }

    @Test
    void sendEmailChangeVerification_bodyContainsVerifyLink() {
        emailService.sendEmailChangeVerification("new@example.com", "change-token");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());

        assertThat(captor.getValue().getText())
                .contains("http://front.example.com/verify-email?token=change-token");
    }
}