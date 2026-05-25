package com.cashcontrol.api.security.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class PasswordPolicy implements ConstraintValidator<ValidPassword, String> {

    private static final int MIN_LENGTH = 12;
    private static final Pattern UPPERCASE = Pattern.compile(".*[A-Z].*", Pattern.DOTALL);
    private static final Pattern LOWERCASE = Pattern.compile(".*[a-z].*", Pattern.DOTALL);
    private static final Pattern DIGIT = Pattern.compile(".*\\d.*", Pattern.DOTALL);
    private static final Pattern SPECIAL = Pattern.compile(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?`~].*", Pattern.DOTALL);

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
        if (password == null) {
            return false;
        }
        return password.length() >= MIN_LENGTH
                && UPPERCASE.matcher(password).matches()
                && LOWERCASE.matcher(password).matches()
                && DIGIT.matcher(password).matches()
                && SPECIAL.matcher(password).matches();
    }
}