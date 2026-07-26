package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.entity.Session;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.SessionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import com.cretas.aims.service.TempTokenService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileAuthServiceImplMultiSessionTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private SessionRepository sessionRepository;
    @Mock
    private PlatformAdminRepository platformAdminRepository;
    @Mock
    private WhitelistRepository whitelistRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private UserMapper userMapper;
    @Mock
    private TempTokenService tempTokenService;
    @Mock
    private TokenBlacklistService tokenBlacklistService;
    @Mock
    private MobileDeviceService mobileDeviceService;

    private MobileAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MobileAuthServiceImpl(
                userRepository, sessionRepository, platformAdminRepository, whitelistRepository,
                passwordEncoder, jwtUtil, userMapper, tempTokenService, tokenBlacklistService,
                mobileDeviceService);
    }

    @Test
    @DisplayName("一台手机登出只撤销当前令牌，不影响同账号其他手机")
    void logoutRevokesOnlyCurrentSession() {
        Session currentSession = new Session();
        currentSession.setId("session-a");
        currentSession.setUserId(101L);
        currentSession.setToken("access-a");
        currentSession.setIsRevoked(false);
        Session otherDeviceSession = new Session();
        otherDeviceSession.setId("session-b");
        otherDeviceSession.setUserId(101L);
        otherDeviceSession.setToken("access-b");
        otherDeviceSession.setIsRevoked(false);

        when(jwtUtil.getExpirationDateFromToken("access-a"))
                .thenReturn(new Date(System.currentTimeMillis() + 60_000L));
        when(sessionRepository.findByTokenAndIsRevokedFalse("access-a"))
                .thenReturn(Optional.of(currentSession));

        service.logout(101L, "device-a", "access-a");

        verify(mobileDeviceService).removeDevice(101L, "device-a");
        verify(tokenBlacklistService).blacklistToken(
                eq("access-a"),
                longThat(ttl -> ttl > 0 && ttl <= 60_000L));
        verify(sessionRepository).save(currentSession);
        verify(sessionRepository, never()).findByUserIdAndIsRevokedFalse(101L);
        verify(sessionRepository, never()).saveAll(org.mockito.ArgumentMatchers.any());
        verify(tokenBlacklistService, never()).blacklistToken(eq("access-b"), anyLong());
        assertThat(otherDeviceSession.getIsRevoked()).isFalse();
    }
}
