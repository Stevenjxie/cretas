package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MobileAuthServiceImplPhoneRegistrationTest {

    @Mock UserRepository userRepository;
    @Mock SessionRepository sessionRepository;
    @Mock PlatformAdminRepository platformAdminRepository;
    @Mock WhitelistRepository whitelistRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtUtil jwtUtil;
    @Mock UserMapper userMapper;
    @Mock TempTokenService tempTokenService;
    @Mock TokenBlacklistService tokenBlacklistService;
    @Mock MobileDeviceService mobileDeviceService;

    private MobileAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MobileAuthServiceImpl(
                userRepository, sessionRepository, platformAdminRepository, whitelistRepository,
                passwordEncoder, jwtUtil, userMapper, tempTokenService, tokenBlacklistService,
                mobileDeviceService);
    }

    @Test
    void trustedWhitelistCreatesActivePhoneAccountWithServerAssignedRole() {
        String phone = "13800138031";
        Whitelist whitelist = invitation("LIUSHANMEN", phone, FactoryUserRole.quality_inspector);
        when(tempTokenService.validateAndGetPhone("temp")).thenReturn(phone);
        when(whitelistRepository.findByFactoryIdAndPhoneNumber("LIUSHANMEN", phone))
                .thenReturn(Optional.of(whitelist));
        when(userRepository.findByFactoryIdAndPhone("LIUSHANMEN", phone)).thenReturn(Optional.empty());
        when(userRepository.existsByUsername(phone)).thenReturn(false);
        when(passwordEncoder.encode("secure123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(501L);
            return user;
        });

        MobileDTO.RegisterPhaseTwoResponse response = service.registerPhaseTwo(
                request("temp", phone, "LIUSHANMEN"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo(phone);
        assertThat(saved.getValue().getPhone()).isEqualTo(phone);
        assertThat(saved.getValue().getFactoryId()).isEqualTo("LIUSHANMEN");
        assertThat(saved.getValue().getRoleCode()).isEqualTo("quality_inspector");
        assertThat(saved.getValue().getIsActive()).isTrue();
        assertThat(saved.getValue().getFullName()).isEqualTo("六扇门质检员");
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getUsername()).isEqualTo(phone);
        assertThat(response.getRole()).isEqualTo("quality_inspector");
        assertThat(response.getMessage()).contains("手机号登录");
        verify(tempTokenService).deleteTempToken("temp");
    }

    @Test
    void phaseOneReturnsTheServerInvitationAndPhoneLoginAccount() {
        String phone = "13800138030";
        Whitelist whitelist = invitation("LIUSHANMEN", phone, FactoryUserRole.quality_inspector);
        when(whitelistRepository.findAllByPhoneNumber(phone)).thenReturn(List.of(whitelist));
        when(whitelistRepository.findByFactoryIdAndPhoneNumber("LIUSHANMEN", phone))
                .thenReturn(Optional.of(whitelist));
        when(userRepository.findByFactoryIdAndPhone("LIUSHANMEN", phone)).thenReturn(Optional.empty());
        when(tempTokenService.generateTempToken(phone, 30)).thenReturn("temp");

        MobileDTO.RegisterPhaseOneResponse response = service.registerPhaseOne(
                MobileDTO.RegisterPhaseOneRequest.builder()
                        .phoneNumber(phone)
                        .build());

        assertThat(response.getFactoryId()).isEqualTo("LIUSHANMEN");
        assertThat(response.getLoginAccount()).isEqualTo(phone);
        assertThat(response.getInvitedName()).isEqualTo("六扇门质检员");
        assertThat(response.getInvitedRole()).isEqualTo("quality_inspector");
        assertThat(response.getInvitedRoleName()).isEqualTo("质检员");
        assertThat(response.getIsNewUser()).isTrue();
    }

    @Test
    void phaseTwoRejectsFactoryWithoutMatchingWhitelist() {
        String phone = "13800138032";
        when(tempTokenService.validateAndGetPhone("temp")).thenReturn(phone);
        when(whitelistRepository.findByFactoryIdAndPhoneNumber("OTHER_FACTORY", phone))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.registerPhaseTwo(
                request("temp", phone, "OTHER_FACTORY")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("当前工厂");
    }

    @Test
    void phaseTwoRejectsClientDefinedUsername() {
        String phone = "13800138033";
        Whitelist whitelist = invitation("LIUSHANMEN", phone, FactoryUserRole.operator);
        when(tempTokenService.validateAndGetPhone("temp")).thenReturn(phone);
        when(whitelistRepository.findByFactoryIdAndPhoneNumber("LIUSHANMEN", phone))
                .thenReturn(Optional.of(whitelist));

        MobileDTO.RegisterPhaseTwoRequest request = request("temp", "custom-name", "LIUSHANMEN");

        assertThatThrownBy(() -> service.registerPhaseTwo(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("受邀手机号一致");
    }

    @Test
    void legacyWhitelistCreatesInactiveUnactivatedPhoneAccount() {
        String phone = "13800138034";
        Whitelist whitelist = invitation("LIUSHANMEN", phone, null);
        when(tempTokenService.validateAndGetPhone("temp")).thenReturn(phone);
        when(whitelistRepository.findByFactoryIdAndPhoneNumber("LIUSHANMEN", phone))
                .thenReturn(Optional.of(whitelist));
        when(userRepository.findByFactoryIdAndPhone("LIUSHANMEN", phone)).thenReturn(Optional.empty());
        when(userRepository.existsByUsername(phone)).thenReturn(false);
        when(passwordEncoder.encode("secure123")).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(502L);
            return user;
        });

        MobileDTO.RegisterPhaseTwoResponse response = service.registerPhaseTwo(
                request("temp", phone, "LIUSHANMEN"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getRoleCode()).isEqualTo("unactivated");
        assertThat(saved.getValue().getIsActive()).isFalse();
        assertThat(response.getMessage()).contains("等待管理员激活");
    }

    private Whitelist invitation(String factoryId, String phone, FactoryUserRole role) {
        return Whitelist.builder()
                .factoryId(factoryId)
                .phoneNumber(phone)
                .name("六扇门质检员")
                .department("quality")
                .status(WhitelistStatus.ACTIVE)
                .invitedRoleCode(role)
                .addedBy(1L)
                .build();
    }

    private MobileDTO.RegisterPhaseTwoRequest request(
            String token, String username, String factoryId) {
        return MobileDTO.RegisterPhaseTwoRequest.builder()
                .tempToken(token)
                .username(username)
                .password("secure123")
                .realName("客户端姓名")
                .factoryId(factoryId)
                .build();
    }
}
