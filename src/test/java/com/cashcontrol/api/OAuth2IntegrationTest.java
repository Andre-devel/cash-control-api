package com.cashcontrol.api;

import com.cashcontrol.api.config.PostgresTestContainerConfig;
import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import com.cashcontrol.api.repository.LookupCache;
import com.cashcontrol.api.repository.OauthAccountRepository;
import com.cashcontrol.api.repository.UserRepository;
import com.cashcontrol.api.security.oauth2.OAuth2AuthenticationSuccessHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
class OAuth2IntegrationTest {

    @Autowired private OAuth2AuthenticationSuccessHandler successHandler;
    @Autowired private UserRepository userRepository;
    @Autowired private OauthAccountRepository oauthAccountRepository;
    @Autowired private LookupCache lookupCache;

    @BeforeEach
    void setUp() {
    }

    @Test
    @Transactional
    void newGoogleUser_createsUserAndOauthAccountInDb() throws Exception {
        String email = "new-oauth2-" + System.nanoTime() + "@example.com";
        String providerUserId = "google-sub-" + System.nanoTime();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "TestBrowser/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication authentication = buildAuthentication(email, providerUserId, "New Google User");

        successHandler.onAuthenticationSuccess(request, response, authentication);

        Optional<User> createdUser = userRepository.findByEmailAndDeletedAtIsNull(email);
        assertThat(createdUser).isPresent();
        assertThat(createdUser.get().getAccountStatus().getSlug()).isEqualTo(UserSlugConstants.STATUS_ACTIVE);
        assertThat(createdUser.get().getAuthOrigin().getSlug()).isEqualTo(UserSlugConstants.ORIGIN_GOOGLE);

        assertThat(oauthAccountRepository.findAll())
                .anyMatch(oa -> providerUserId.equals(oa.getProviderUserId()));

        assertThat(response.getRedirectedUrl()).contains("?token=");
    }

    @Test
    @Transactional
    void existingLocalUser_accountLinkedAndOriginSetToMixed() throws Exception {
        String email = "local-oauth2-" + System.nanoTime() + "@example.com";
        String providerUserId = "google-sub-link-" + System.nanoTime();

        // Create pre-existing LOCAL user
        User existingUser = new User();
        existingUser.setEmail(email);
        existingUser.setAccountStatus(lookupCache.requireAccountStatus(UserSlugConstants.STATUS_ACTIVE));
        existingUser.setAuthOrigin(lookupCache.requireAuthOrigin(UserSlugConstants.ORIGIN_LOCAL));
        existingUser.setEmailVerifiedAt(Instant.now());
        existingUser.setCredentialsUpdatedAt(Instant.now());
        userRepository.save(existingUser);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "TestBrowser/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Authentication authentication = buildAuthentication(email, providerUserId, "Local User");

        successHandler.onAuthenticationSuccess(request, response, authentication);

        User updated = userRepository.findByEmailAndDeletedAtIsNull(email).orElseThrow();
        assertThat(updated.getAuthOrigin().getSlug()).isEqualTo(UserSlugConstants.ORIGIN_MIXED);

        assertThat(oauthAccountRepository.findAll())
                .anyMatch(oa -> providerUserId.equals(oa.getProviderUserId()));

        assertThat(response.getRedirectedUrl()).contains("?token=");
    }

    @Test
    @Transactional
    void missingEmailInGoogleProfile_redirectsToFailureUrl() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "TestBrowser/1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Build OAuth2User with no email attribute
        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("email")).thenReturn(null);
        when(oAuth2User.getAttribute("sub")).thenReturn("some-provider-id");
        when(oAuth2User.getAttribute("name")).thenReturn("No Email User");

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oAuth2User);

        successHandler.onAuthenticationSuccess(request, response, authentication);

        assertThat(response.getRedirectedUrl()).contains("?error=oauth_failed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Authentication buildAuthentication(String email, String providerUserId, String displayName) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("email", email);
        attributes.put("sub", providerUserId);
        attributes.put("name", displayName);

        OAuth2User oAuth2User = mock(OAuth2User.class);
        when(oAuth2User.getAttribute("email")).thenReturn(email);
        when(oAuth2User.getAttribute("sub")).thenReturn(providerUserId);
        when(oAuth2User.getAttribute("name")).thenReturn(displayName);
        when(oAuth2User.getAttributes()).thenReturn(attributes);

        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(oAuth2User);
        return authentication;
    }
}
