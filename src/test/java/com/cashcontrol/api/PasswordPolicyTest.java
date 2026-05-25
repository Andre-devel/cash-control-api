package com.cashcontrol.api;

import com.cashcontrol.api.security.validation.PasswordPolicy;
import com.cashcontrol.api.security.validation.ValidPassword;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void validPasswordPassesAllRules() {
        assertThat(policy.isValid("SecurePass1!", null)).isTrue();
    }

    @Test
    void nullPasswordFails() {
        assertThat(policy.isValid(null, null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "short1!A",
        "onlylowercase1!",
        "ONLYUPPERCASE1!",
        "NoDigitsHere!Aa",
        "NoSpecialChar1Aa"
    })
    void invalidPasswordsFail(String password) {
        assertThat(policy.isValid(password, null)).isFalse();
    }

    @Test
    void exactlyTwelveCharactersWithAllRulesMeetsMinimum() {
        assertThat(policy.isValid("Passw0rd!aBC", null)).isTrue();
    }

    @Test
    void elevenCharactersFailsMinimumLength() {
        assertThat(policy.isValid("Passw0rd!aB", null)).isFalse();
    }

    @Test
    void annotationBasedValidationWorks() {
        record Dto(@ValidPassword String password) {}
        Set<ConstraintViolation<Dto>> violations = validator.validate(new Dto("weak"));
        assertThat(violations).isNotEmpty();
    }

    @Test
    void annotationBasedValidationPassesForStrongPassword() {
        record Dto(@ValidPassword String password) {}
        Set<ConstraintViolation<Dto>> violations = validator.validate(new Dto("Str0ng!Password99"));
        assertThat(violations).isEmpty();
    }
}