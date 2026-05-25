package com.cashcontrol.api.security.oauth2;

public record OAuth2UserInfo(String email, String providerUserId, String displayName) {
}
