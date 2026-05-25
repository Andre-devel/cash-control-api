package com.cashcontrol.api.util;

import org.springframework.stereotype.Component;

@Component
public class DataMasker {

    public String maskEmail(String email) {
        if (email == null) return null;
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) return "***";
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        String maskedLocal = local.substring(0, 1) + "***";
        return maskedLocal + domain;
    }

    public String maskIpV4(String ip) {
        if (ip == null) return null;
        int lastDot = ip.lastIndexOf('.');
        if (lastDot < 0) return ip;
        return ip.substring(0, lastDot + 1) + "0";
    }

    public String maskIpV6(String ip) {
        if (ip == null) return null;
        // Zero the last 80 bits (last 5 groups of 16 bits in a full IPv6 address)
        String[] parts = ip.split(":", -1);
        if (parts.length < 4) return ip;
        int zerosFrom = Math.max(0, parts.length - 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(":");
            if (i >= zerosFrom) sb.append("0");
            else sb.append(parts[i]);
        }
        return sb.toString();
    }

    public String maskIp(String ip) {
        if (ip == null) return null;
        if (ip.contains(":")) {
            return maskIpV6(ip);
        }
        return maskIpV4(ip);
    }

    public String truncateUserAgent(String ua, int maxLength) {
        if (ua == null) return null;
        if (ua.length() <= maxLength) return ua;
        return ua.substring(0, maxLength);
    }

    public String sanitizeTokenValue(String token) {
        return "[REDACTED]";
    }
}