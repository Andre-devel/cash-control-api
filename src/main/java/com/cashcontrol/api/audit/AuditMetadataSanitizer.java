package com.cashcontrol.api.audit;

import com.cashcontrol.api.util.DataMasker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class AuditMetadataSanitizer {

    private static final Set<String> BLOCKED_KEY_SUBSTRINGS = Set.of(
            "password", "token", "secret", "hash", "credential"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$"
    );

    private final DataMasker dataMasker;

    public Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Map<String, Object> sanitized = new HashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isBlockedKey(key)) {
                sanitized.put(key, "[REDACTED]");
            } else if (value instanceof String strValue && looksLikeEmail(strValue)) {
                sanitized.put(key, dataMasker.maskEmail(strValue));
            } else {
                sanitized.put(key, value);
            }
        }
        return sanitized;
    }

    private boolean isBlockedKey(String key) {
        String lowerKey = key.toLowerCase();
        return BLOCKED_KEY_SUBSTRINGS.stream().anyMatch(lowerKey::contains);
    }

    private boolean looksLikeEmail(String value) {
        return EMAIL_PATTERN.matcher(value).matches();
    }
}