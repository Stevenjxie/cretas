package com.cretas.aims.service.mobile;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.user.UserDTO;

/**
 * 移动端认证服务接口
 * <p>
 * 负责登录、登出、Token刷新/验证、注册流程。
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
public interface MobileAuthService {

    MobileDTO.LoginResponse unifiedLogin(MobileDTO.LoginRequest request);

    /** 演示账号免密登录 (tenant = "rest" | "factory"). 路演扫码演示用. */
    MobileDTO.LoginResponse demoLogin(String tenant);

    MobileDTO.LoginResponse refreshToken(String refreshToken);

    void logout(Long userId, String deviceId, String token);

    boolean validateToken(String token);

    UserDTO getUserFromToken(String token);

    MobileDTO.RegisterPhaseOneResponse registerPhaseOne(MobileDTO.RegisterPhaseOneRequest request);

    MobileDTO.RegisterPhaseTwoResponse registerPhaseTwo(MobileDTO.RegisterPhaseTwoRequest request);
}
