package com.cretas.aims.utils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilMultiSessionTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret",
                "cretas-test-secret-key-for-multi-device-session");
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86_400_000L);
        ReflectionTestUtils.setField(jwtUtil, "refreshExpiration", 604_800_000L);
    }

    @Test
    @DisplayName("同一账号连续登录会获得两个相互独立的访问令牌")
    void accessTokensHaveDistinctSessionIds() {
        String first = jwtUtil.generateToken(101L, "F001", "13800000000", "quality_inspector");
        String second = jwtUtil.generateToken(101L, "F001", "13800000000", "quality_inspector");

        assertThat(first).isNotEqualTo(second);
        assertThat(jwtUtil.getTokenId(first)).isNotBlank();
        assertThat(jwtUtil.getTokenId(second)).isNotBlank();
        assertThat(jwtUtil.getTokenId(first)).isNotEqualTo(jwtUtil.getTokenId(second));
        assertThat(jwtUtil.validateToken(first)).isTrue();
        assertThat(jwtUtil.validateToken(second)).isTrue();
    }

    @Test
    @DisplayName("同一账号连续登录会获得两个相互独立的刷新令牌")
    void refreshTokensHaveDistinctSessionIds() {
        String first = jwtUtil.generateRefreshToken("101");
        String second = jwtUtil.generateRefreshToken("101");

        assertThat(first).isNotEqualTo(second);
        assertThat(jwtUtil.getTokenId(first)).isNotEqualTo(jwtUtil.getTokenId(second));
    }
}
