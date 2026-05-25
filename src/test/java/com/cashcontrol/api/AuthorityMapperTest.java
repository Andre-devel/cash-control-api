package com.cashcontrol.api;

import com.cashcontrol.api.security.AuthorityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorityMapperTest {

    private final AuthorityMapper mapper = new AuthorityMapper();

    @Test
    void mapsPermissionStringsToGrantedAuthorities() {
        List<GrantedAuthority> authorities = mapper.fromPermissionList(List.of("user:read", "role:create"));
        assertThat(authorities).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("user:read", "role:create");
    }

    @Test
    void emptyListReturnsEmptyAuthorities() {
        assertThat(mapper.fromPermissionList(List.of())).isEmpty();
    }

    @Test
    void nullListReturnsEmptyAuthorities() {
        assertThat(mapper.fromPermissionList(null)).isEmpty();
    }

    @Test
    void singlePermissionMapsCorrectly() {
        List<GrantedAuthority> authorities = mapper.fromPermissionList(List.of("audit:view"));
        assertThat(authorities).hasSize(1);
        assertThat(authorities.getFirst().getAuthority()).isEqualTo("audit:view");
    }
}