package com.cashcontrol.api;

import com.cashcontrol.api.domain.entity.AccountStatus;
import com.cashcontrol.api.domain.entity.AuthOrigin;
import com.cashcontrol.api.domain.entity.AuthenticationMethod;
import com.cashcontrol.api.domain.entity.BaseLookupEntity;
import com.cashcontrol.api.domain.entity.LockoutType;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Factory helpers for creating test instances of immutable lookup entities.
 * BaseLookupEntity has no setters by design; ReflectionTestUtils is used here only in tests.
 */
final class TestEntityFactory {

    private TestEntityFactory() {}

    static AccountStatus accountStatus(String slug) {
        AccountStatus s = new AccountStatus();
        setSlug(s, slug);
        return s;
    }

    static AuthOrigin authOrigin(String slug) {
        AuthOrigin o = new AuthOrigin();
        setSlug(o, slug);
        return o;
    }

    static LockoutType lockoutType(String slug) {
        LockoutType l = new LockoutType();
        setSlug(l, slug);
        return l;
    }

    static AuthenticationMethod authMethod(String slug) {
        AuthenticationMethod m = new AuthenticationMethod();
        setSlug(m, slug);
        return m;
    }

    private static void setSlug(BaseLookupEntity entity, String slug) {
        ReflectionTestUtils.setField(entity, "slug", slug);
    }
}