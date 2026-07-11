package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.exception.BusinessException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 6a — 一加物流 demoLogin("logistics") 分支回归测试.
 *
 * <p>镜像 rest/factory 分支已有的 demoLogin 行为 (无独立测试类, 本类是该方法的首个单测覆盖),
 * 断言: tenant=logistics 命中 DEMO_LOGISTICS/dispatcher_demo_logistics 配置项, 拒绝未开启
 * demo 开关, 并确认签发的 JWT 用 user.getFactoryId() (= DEMO_LOGISTICS) 生成 —
 * 即 JwtAuthInterceptor 之后该 token 只能访问 /api/mobile/DEMO_LOGISTICS/** 路径,
 * 无法跨工厂 (JWT-locked, 见 worktree-and-main-only-deploy / concurrent-edit-safety 隔离铁律的
 * 同一原理: 演示租户绝不能触达其他工厂数据).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MobileAuthServiceImplDemoLoginTest {

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
        ReflectionTestUtils.setField(service, "demoEnabled", true);
        ReflectionTestUtils.setField(service, "demoRestFactoryId", "DEMO_REST");
        ReflectionTestUtils.setField(service, "demoRestUsername", "demo_rest");
        ReflectionTestUtils.setField(service, "demoFactoryFactoryId", "DEMO_FACTORY2");
        ReflectionTestUtils.setField(service, "demoFactoryUsername", "demo_factory2");
        ReflectionTestUtils.setField(service, "demoLogisticsFactoryId", "DEMO_LOGISTICS");
        ReflectionTestUtils.setField(service, "demoLogisticsUsername", "dispatcher_demo_logistics");
    }

    private User buildLogisticsDemoUser() {
        Factory factory = new Factory();
        factory.setId("DEMO_LOGISTICS");
        factory.setName("一加物流调度中心");
        factory.setType(FactoryType.LOGISTICS);

        User user = new User();
        user.setId(9001L);
        user.setFactoryId("DEMO_LOGISTICS");
        user.setUsername("dispatcher_demo_logistics");
        user.setFullName("演示调度员");
        user.setRoleCode("dispatcher");
        user.setIsActive(true);
        user.setFactory(factory);
        return user;
    }

    @Test
    @DisplayName("tenant=logistics 命中 DEMO_LOGISTICS/dispatcher_demo_logistics 并返回 factoryType=LOGISTICS")
    void demoLogin_logistics_returnsLogisticsIdentity() {
        User user = buildLogisticsDemoUser();
        when(userRepository.findByFactoryIdAndUsername("DEMO_LOGISTICS", "dispatcher_demo_logistics"))
                .thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("fake-jwt-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("fake-refresh-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        MobileDTO.LoginResponse response = service.demoLogin("logistics");

        assertThat(response.getFactoryId()).isEqualTo("DEMO_LOGISTICS");
        assertThat(response.getFactoryType()).isEqualTo("LOGISTICS");
        assertThat(response.getBusinessDomain()).isEqualTo("LOGISTICS");
        assertThat(response.getRole()).isEqualTo("dispatcher");
        assertThat(response.getToken()).isNotBlank();
    }

    @Test
    @DisplayName("大小写不敏感: tenant=LOGISTICS 同样命中")
    void demoLogin_logisticsCaseInsensitive() {
        User user = buildLogisticsDemoUser();
        when(userRepository.findByFactoryIdAndUsername("DEMO_LOGISTICS", "dispatcher_demo_logistics"))
                .thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("fake-jwt-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("fake-refresh-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        MobileDTO.LoginResponse response = service.demoLogin("LOGISTICS");

        assertThat(response.getFactoryId()).isEqualTo("DEMO_LOGISTICS");
    }

    @Test
    @DisplayName("JWT 生成必须以 user.getFactoryId()=DEMO_LOGISTICS 为准 (JWT-locked, 无法跨工厂)")
    void demoLogin_logistics_jwtIsLockedToDemoLogisticsFactoryId() {
        User user = buildLogisticsDemoUser();
        when(userRepository.findByFactoryIdAndUsername("DEMO_LOGISTICS", "dispatcher_demo_logistics"))
                .thenReturn(Optional.of(user));
        when(jwtUtil.generateToken(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn("fake-jwt-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("fake-refresh-token");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.demoLogin("logistics");

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> factoryIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> usernameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> roleCaptor = ArgumentCaptor.forClass(String.class);
        verify(jwtUtil).generateToken(userIdCaptor.capture(), factoryIdCaptor.capture(),
                usernameCaptor.capture(), roleCaptor.capture());

        // JwtAuthInterceptor 之后按此 factoryId 做路径匹配 — 必须锁死为 DEMO_LOGISTICS,
        // 不能是调用方能操纵的任意值, 否则演示账号可跨工厂访问其他 factoryId 数据.
        assertThat(factoryIdCaptor.getValue()).isEqualTo("DEMO_LOGISTICS");
        assertThat(userIdCaptor.getValue()).isEqualTo(9001L);
        assertThat(usernameCaptor.getValue()).isEqualTo("dispatcher_demo_logistics");
        assertThat(roleCaptor.getValue()).isEqualTo("dispatcher");
    }

    @Test
    @DisplayName("demo 账号不存在 -> 404 BusinessException")
    void demoLogin_logistics_accountMissing_throws404() {
        when(userRepository.findByFactoryIdAndUsername("DEMO_LOGISTICS", "dispatcher_demo_logistics"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.demoLogin("logistics"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演示账号不存在");
    }

    @Test
    @DisplayName("demo 开关关闭 -> 403 BusinessException, 即使 tenant=logistics 合法")
    void demoLogin_disabled_throws403() {
        ReflectionTestUtils.setField(service, "demoEnabled", false);

        assertThatThrownBy(() -> service.demoLogin("logistics"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("演示模式未开启");
    }

    @Test
    @DisplayName("未知 tenant -> 400 BusinessException")
    void demoLogin_unknownTenant_throws400() {
        assertThatThrownBy(() -> service.demoLogin("unknown-tenant"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无效的演示类型");
    }
}
