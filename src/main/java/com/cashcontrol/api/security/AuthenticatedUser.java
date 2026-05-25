package com.cashcontrol.api.security;

import com.cashcontrol.api.domain.UserSlugConstants;
import com.cashcontrol.api.domain.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public class AuthenticatedUser implements UserDetails {

    private final User user;
    private final List<GrantedAuthority> authorities;

    public AuthenticatedUser(User user, List<GrantedAuthority> authorities) {
        this.user = user;
        this.authorities = List.copyOf(authorities);
    }

    public User getUser() {
        return user;
    }

    public Instant getCredentialsUpdatedAt() {
        return user.getCredentialsUpdatedAt();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        String statusSlug = user.getAccountStatus().getSlug();
        if (!UserSlugConstants.STATUS_LOCKED.equals(statusSlug)) {
            return true;
        }
        Instant expiresAt = user.getLockoutExpiresAt();
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.getDeletedAt() == null
                && UserSlugConstants.STATUS_ACTIVE.equals(user.getAccountStatus().getSlug());
    }

    @Override
    public String toString() {
        return "AuthenticatedUser{userId=" + user.getId() + "}";
    }
}