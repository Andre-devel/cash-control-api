package com.cashcontrol.api.security.oauth2;

import com.cashcontrol.api.domain.exception.OAuthProviderException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class OAuth2UserInfoExtractor {

    public OAuth2UserInfo extract(OAuth2User oAuth2User) {
        String email = oAuth2User.getAttribute("email");
        String providerUserId = oAuth2User.getAttribute("sub");
        String displayName = oAuth2User.getAttribute("name");

        if (email == null || email.isBlank()) {
            throw new OAuthProviderException("Email not provided by OAuth2 provider.");
        }
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new OAuthProviderException("Provider user ID not provided by OAuth2 provider.");
        }

        return new OAuth2UserInfo(email, providerUserId, displayName);
    }
}
