package com.cashcontrol.api;

import com.cashcontrol.api.domain.exception.AccountDeletedException;
import com.cashcontrol.api.domain.exception.AccountDisabledException;
import com.cashcontrol.api.domain.exception.AccountLockedException;
import com.cashcontrol.api.domain.exception.AccountNotVerifiedException;
import com.cashcontrol.api.domain.exception.AuthException;
import com.cashcontrol.api.domain.exception.ConflictException;
import com.cashcontrol.api.domain.exception.EmailAlreadyExistsException;
import com.cashcontrol.api.domain.exception.InvalidCredentialsException;
import com.cashcontrol.api.domain.exception.OAuthProviderException;
import com.cashcontrol.api.domain.exception.PermissionDeniedException;
import com.cashcontrol.api.domain.exception.ResourceNotFoundException;
import com.cashcontrol.api.domain.exception.TokenAlreadyConsumedException;
import com.cashcontrol.api.domain.exception.TokenExpiredException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionTest {

    @Test
    void allExceptionsExtendsAuthException() {
        assertThat(InvalidCredentialsException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(AccountLockedException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(AccountNotVerifiedException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(AccountDisabledException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(AccountDeletedException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(TokenExpiredException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(TokenAlreadyConsumedException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(EmailAlreadyExistsException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(ResourceNotFoundException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(ConflictException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(PermissionDeniedException.class.getSuperclass()).isEqualTo(AuthException.class);
        assertThat(OAuthProviderException.class.getSuperclass()).isEqualTo(AuthException.class);
    }

    @Test
    void authExceptionExtendsRuntimeException() {
        assertThat(AuthException.class.getSuperclass()).isEqualTo(RuntimeException.class);
    }

    @Test
    void allExceptionsAreUnchecked() {
        assertThat(RuntimeException.class.isAssignableFrom(AuthException.class)).isTrue();
        assertThat(RuntimeException.class.isAssignableFrom(InvalidCredentialsException.class)).isTrue();
        assertThat(RuntimeException.class.isAssignableFrom(TokenExpiredException.class)).isTrue();
    }

    @Test
    void correlationIdIsGeneratedOnConstruction() {
        InvalidCredentialsException ex = new InvalidCredentialsException();
        assertThat(ex.getCorrelationId()).isNotNull();
    }

    @Test
    void correlationIdCanBeProvidedExplicitly() {
        UUID id = UUID.randomUUID();
        InvalidCredentialsException ex = new InvalidCredentialsException(id);
        assertThat(ex.getCorrelationId()).isEqualTo(id);
    }

    @Test
    void authExceptionWithNullCorrelationIdGeneratesOne() {
        AuthException ex = new AuthException("test", (UUID) null);
        assertThat(ex.getCorrelationId()).isNotNull();
    }

    @Test
    void antiEnumerationExceptionsUseGenericMessage() {
        // These exceptions must never reveal the real failure reason to callers
        assertThat(new InvalidCredentialsException().getMessage()).doesNotContain("email");
        assertThat(new InvalidCredentialsException().getMessage()).doesNotContain("password");
        assertThat(new AccountLockedException().getMessage()).doesNotContain("locked");
        assertThat(new AccountNotVerifiedException().getMessage()).doesNotContain("verified");
        assertThat(new AccountDisabledException().getMessage()).doesNotContain("disabled");
        assertThat(new AccountDeletedException().getMessage()).doesNotContain("deleted");
    }

    @Test
    void tokenExpiredExceptionCarriesMessage() {
        TokenExpiredException ex = new TokenExpiredException("Token has expired.");
        assertThat(ex.getMessage()).isEqualTo("Token has expired.");
        assertThat(ex.getCorrelationId()).isNotNull();
    }

    @Test
    void resourceNotFoundExceptionCarriesMessage() {
        ResourceNotFoundException ex = new ResourceNotFoundException("User not found.");
        assertThat(ex.getMessage()).isEqualTo("User not found.");
    }

    @Test
    void conflictExceptionCarriesMessage() {
        ConflictException ex = new ConflictException("Role name already exists.");
        assertThat(ex.getMessage()).isEqualTo("Role name already exists.");
    }

    @Test
    void permissionDeniedExceptionUsesGenericMessage() {
        PermissionDeniedException ex = new PermissionDeniedException();
        assertThat(ex.getMessage()).isEqualTo("Access denied.");
        assertThat(ex.getMessage()).doesNotContain("permission");
    }

    @Test
    void oauthProviderExceptionSupportsCauseChaining() {
        RuntimeException cause = new RuntimeException("provider error");
        OAuthProviderException ex = new OAuthProviderException("OAuth2 flow failed.", cause);
        assertThat(ex.getCause()).isEqualTo(cause);
    }
}