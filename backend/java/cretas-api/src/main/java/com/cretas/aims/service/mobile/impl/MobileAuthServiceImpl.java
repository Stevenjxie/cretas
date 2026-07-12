package com.cretas.aims.service.mobile.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.Session;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.SessionRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import com.cretas.aims.service.TempTokenService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.MobileAuthService;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.util.BusinessDomainUtils;
import com.cretas.aims.utils.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 移动端认证服务实现
 *
 * @author Cretas Team
 * @since 2026-03-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobileAuthServiceImpl implements MobileAuthService {

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final WhitelistRepository whitelistRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final TempTokenService tempTokenService;
    private final TokenBlacklistService tokenBlacklistService;
    private final MobileDeviceService mobileDeviceService;

    // 演示账号配置 (路演免密登录). cretas.demo.enabled=true 开启.
    @Value("${cretas.demo.enabled:false}")
    private boolean demoEnabled;
    @Value("${cretas.demo.rest.factory-id:DEMO_REST}")
    private String demoRestFactoryId;
    @Value("${cretas.demo.rest.username:demo_rest}")
    private String demoRestUsername;
    @Value("${cretas.demo.factory.factory-id:DEMO_FACTORY}")
    private String demoFactoryFactoryId;
    @Value("${cretas.demo.factory.username:demo_factory}")
    private String demoFactoryUsername;
    @Value("${cretas.demo.logistics.factory-id:DEMO_LOGISTICS}")
    private String demoLogisticsFactoryId;
    @Value("${cretas.demo.logistics.username:dispatcher_demo_logistics}")
    private String demoLogisticsUsername;

    @Override
    @Transactional
    public MobileDTO.LoginResponse unifiedLogin(MobileDTO.LoginRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();

        log.info("移动端统一登录: username={}", username);

        // 优先级1: 检查是否为平台管理员
        Optional<PlatformAdmin> platformAdminOpt = platformAdminRepository.findByUsername(username);
        if (platformAdminOpt.isPresent()) {
            log.info("检测到平台管理员登录: username={}", username);
            return loginAsPlatformAdmin(platformAdminOpt.get(), password, request.getDeviceInfo());
        }

        // 优先级2: 工厂用户登录
        String factoryId = request.getFactoryId();

        // 智能推断 factoryId（如果未提供）
        if (factoryId == null || factoryId.trim().isEmpty()) {
            log.info("factoryId未提供，开始智能推断: username={}", username);
            List<User> users = userRepository.findAllByUsername(username);

            if (users.isEmpty()) {
                throw new BusinessException(401, "用户名或密码错误")
                        .withHint("请检查用户名和密码后重试").withHintTarget("password");
            } else if (users.size() == 1) {
                factoryId = users.get(0).getFactoryId();
                log.info("智能推断成功: username={}, factoryId={}", username, factoryId);
            } else {
                // 存在多个同名用户，必须提供 factoryId
                throw new BusinessException(409, "存在多个同名用户，请提供工厂ID进行登录")
                    .withHint("请在登录界面选择具体工厂").withHintTarget("factoryId");
            }
        }

        log.info("工厂用户登录: factoryId={}, username={}", factoryId, username);

        // 根据工厂ID和用户名查找用户
        User user = userRepository.findByFactoryIdAndUsername(factoryId, username)
                .orElseThrow(() -> new BusinessException(401, "用户名或密码错误")
                        .withHint("请检查用户名和密码后重试").withHintTarget("password"));

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            // 密码错误是客户端输入问题, 不是服务端 bug. 但保持 WARN 级别
            // 有助于审计暴力破解 (同用户连续失败次数可被 SIEM 规则聚合).
            log.warn("密码验证失败 - 用户: {}", username);
            throw new BusinessException(401, "用户名或密码错误")
                    .withHint("请检查用户名和密码后重试").withHintTarget("password");
        }

        log.info("密码验证成功 - 用户: {}", username);

        // 检查用户状态
        if (!user.getIsActive()) {
            throw new BusinessException(403, "用户账号已被禁用")
                    .withHint("请联系工厂管理员重新启用账号");
        }

        // 记录设备信息
        if (request.getDeviceInfo() != null) {
            mobileDeviceService.recordDeviceLogin(user.getId(), request.getDeviceInfo());
        }

        return buildLoginResponse(user);
    }

    /**
     * 演示账号免密登录 (路演扫码演示用). 不验证密码 — 只能登录配置死的三个 demo 账号
     * (DEMO_REST / DEMO_FACTORY / DEMO_LOGISTICS), 由 cretas.demo.enabled 开关控制.
     * DEMO_REST / DEMO_FACTORY 写操作由 DemoReadOnlyInterceptor 拦截只读锁定;
     * DEMO_LOGISTICS 不在 cretas.demo.factory-ids 只读锁名单内 (调度员需在自己租户内
     * 创建/调整/确认排线计划), 隔离仅靠 JwtAuthInterceptor 的 JWT factoryId 锁定
     * (无法跨工厂访问其他 factoryId 路径).
     */
    @Override
    @Transactional
    public MobileDTO.LoginResponse demoLogin(String tenant) {
        if (!demoEnabled) {
            throw new BusinessException(403, "演示模式未开启");
        }
        String factoryId;
        String username;
        if ("rest".equalsIgnoreCase(tenant)) {
            factoryId = demoRestFactoryId;
            username = demoRestUsername;
        } else if ("factory".equalsIgnoreCase(tenant)) {
            factoryId = demoFactoryFactoryId;
            username = demoFactoryUsername;
        } else if ("logistics".equalsIgnoreCase(tenant)) {
            factoryId = demoLogisticsFactoryId;
            username = demoLogisticsUsername;
        } else {
            throw new BusinessException(400, "无效的演示类型 (应为 rest / factory / logistics)");
        }
        log.info("演示登录: tenant={}, factoryId={}, username={}", tenant, factoryId, username);
        User user = userRepository.findByFactoryIdAndUsername(factoryId, username)
                .orElseThrow(() -> new BusinessException(404, "演示账号不存在"));
        if (!user.getIsActive()) {
            throw new BusinessException(403, "演示账号已被禁用");
        }
        return buildLoginResponse(user);
    }

    /**
     * 构建登录响应 (生成令牌 + 更新最后登录时间). unifiedLogin 与 demoLogin 共用.
     */
    private MobileDTO.LoginResponse buildLoginResponse(User user) {
        // 生成令牌（包含角色和工厂信息）
        String role = user.getRoleCode() != null ? user.getRoleCode() : "viewer";
        String token = jwtUtil.generateToken(user.getId(), user.getFactoryId(), user.getUsername(), role);
        String refreshToken = jwtUtil.generateRefreshToken(user.getId().toString());

        // 更新最后登录时间
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        String factoryType = user.getFactory() != null && user.getFactory().getType() != null
                ? user.getFactory().getType().name() : "FACTORY";

        // 构建响应
        return MobileDTO.LoginResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .factoryId(user.getFactoryId())
                .factoryName(user.getFactory() != null ? user.getFactory().getName() : null)
                .factoryType(factoryType)
                .businessDomain(BusinessDomainUtils.resolveDomain(factoryType))
                .role(user.getRole())
                .permissions(parsePermissions(user.getPermissions()))
                .token(token)
                .refreshToken(refreshToken)
                .expiresIn(3600L) // 1小时
                .lastLoginTime(user.getLastLogin())
                .profile(MobileDTO.UserProfile.builder()
                        .name(user.getName())
                        .avatar(user.getAvatar())
                        .department(user.getDepartment())
                        .position(user.getPosition())
                        .phoneNumber(user.getPhone())
                        .email(null)
                        .build())
                .build();
    }

    /**
     * 平台管理员登录
     */
    private MobileDTO.LoginResponse loginAsPlatformAdmin(PlatformAdmin admin, String password,
                                                         MobileDTO.DeviceInfo deviceInfo) {
        // 验证密码
        if (!passwordEncoder.matches(password, admin.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误")
                    .withHint("请检查用户名和密码后重试").withHintTarget("password");
        }

        // 检查账号状态
        if (!admin.isActive()) {
            throw new BusinessException(403, "账号已被禁用")
                    .withHint("请联系管理员重新启用账号");
        }

        // 记录设备信息
        if (deviceInfo != null) {
            mobileDeviceService.recordDeviceLogin(admin.getId(), deviceInfo);
        }

        // 生成令牌（使用 "platform_" 前缀区分平台管理员，包含角色信息和PLATFORM工厂ID）
        String role = admin.getPlatformRole() != null ? admin.getPlatformRole().name().toLowerCase() : "auditor";
        // 平台管理员使用 factoryId="PLATFORM" 以通过跨工厂权限验证
        String token = jwtUtil.generateToken(admin.getId(), "PLATFORM", admin.getUsername(), role);
        String refreshToken = jwtUtil.generateRefreshToken("platform_" + admin.getId());

        // 更新最后登录时间
        admin.setLastLoginAt(LocalDateTime.now());
        platformAdminRepository.save(admin);

        // 构建响应
        return MobileDTO.LoginResponse.builder()
                .userId(admin.getId())
                .username(admin.getUsername())
                .factoryId(null) // 平台管理员没有 factoryId
                .factoryName("平台管理")
                .role(admin.getPlatformRole().name())
                .permissions(Arrays.asList(admin.getPermissions()))
                .token(token)
                .refreshToken(refreshToken)
                .expiresIn(3600L) // 1小时
                .lastLoginTime(admin.getLastLoginAt())
                .profile(MobileDTO.UserProfile.builder()
                        .name(admin.getRealName())
                        .avatar(null)
                        .department("平台管理部")
                        .position(admin.getPlatformRole().name())
                        .phoneNumber(admin.getPhoneNumber())
                        .email(admin.getEmail())
                        .build())
                .build();
    }

    @Override
    public MobileDTO.LoginResponse refreshToken(String refreshToken) {
        log.debug("刷新令牌: token={}", refreshToken);

        // 验证刷新令牌
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new BusinessException(401, "无效的刷新令牌")
                    .withHint("请重新登录").withHintTarget("refreshToken");
        }

        String userIdStr = jwtUtil.getUserIdFromTokenAsString(refreshToken);

        // 处理平台管理员 (userId格式为 "platform_X")
        if (userIdStr != null && userIdStr.startsWith("platform_")) {
            String platformIdStr = userIdStr.substring("platform_".length());
            try {
                Long platformId = Long.parseLong(platformIdStr);
                PlatformAdmin admin = platformAdminRepository.findById(platformId)
                        .orElseThrow(() -> new ResourceNotFoundException("平台管理员不存在"));

                String role = admin.getPlatformRole() != null ? admin.getPlatformRole().name().toLowerCase() : "auditor";
                String newToken = jwtUtil.generateToken(admin.getId(), "PLATFORM", admin.getUsername(), role);

                return MobileDTO.LoginResponse.builder()
                        .token(newToken)
                        .refreshToken(refreshToken)
                        .expiresIn(3600L)
                        .userId(admin.getId())
                        .username(admin.getUsername())
                        .role(admin.getPlatformRole().name())
                        .build();
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "无效的平台管理员ID格式")
                        .withHint("请重新登录获取有效令牌");
            }
        }

        // 处理普通工厂用户
        try {
            Long userId = Long.parseLong(userIdStr);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));

            String role = user.getRoleCode() != null ? user.getRoleCode() : "viewer";
            String newToken = jwtUtil.generateToken(user.getId(), user.getFactoryId(), user.getUsername(), role);

            // 获取工厂类型
            String factoryType = "FACTORY";
            if (user.getFactory() != null && user.getFactory().getType() != null) {
                factoryType = user.getFactory().getType().name();
            }

            return MobileDTO.LoginResponse.builder()
                    .token(newToken)
                    .refreshToken(refreshToken)
                    .expiresIn(3600L)
                    .userId(user.getId())
                    .username(user.getUsername())
                    .factoryId(user.getFactoryId())
                    .factoryType(factoryType)
                    .businessDomain(BusinessDomainUtils.resolveDomain(factoryType))
                    .role(role)
                    .build();
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无效的用户ID格式: " + userIdStr)
                    .withHint("请重新登录获取有效令牌");
        }
    }

    @Override
    @Transactional
    public void logout(Long userId, String deviceId, String token) {
        log.info("用户登出: userId={}, deviceId={}", userId, deviceId);

        // 移除设备记录
        if (StringUtils.hasText(deviceId)) {
            mobileDeviceService.removeDevice(userId, deviceId);
        }

        // 将当前token加入黑名单
        if (StringUtils.hasText(token)) {
            Date expiration = jwtUtil.getExpirationDateFromToken(token);
            long remainingTtlMs = 0;
            if (expiration != null) {
                remainingTtlMs = expiration.getTime() - System.currentTimeMillis();
            }
            if (remainingTtlMs > 0) {
                tokenBlacklistService.blacklistToken(token, remainingTtlMs);
            }
        }

        // 撤销用户的所有活跃session
        if (userId != null) {
            List<Session> activeSessions = sessionRepository.findByUserIdAndIsRevokedFalse(userId);
            for (Session session : activeSessions) {
                session.setIsRevoked(true);
                // 将session中的token也加入黑名单
                if (StringUtils.hasText(session.getToken())) {
                    Date expiration = jwtUtil.getExpirationDateFromToken(session.getToken());
                    long ttl = 0;
                    if (expiration != null) {
                        ttl = expiration.getTime() - System.currentTimeMillis();
                    }
                    if (ttl > 0) {
                        tokenBlacklistService.blacklistToken(session.getToken(), ttl);
                    }
                }
            }
            sessionRepository.saveAll(activeSessions);
            log.info("已撤销用户 {} 的 {} 个活跃session", userId, activeSessions.size());
        }
    }

    @Override
    public boolean validateToken(String token) {
        if (!jwtUtil.validateToken(token)) {
            return false;
        }
        return sessionRepository.findByTokenAndIsRevokedFalse(token)
                .map(session -> session.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    @Override
    public UserDTO getUserFromToken(String token) {
        String userIdStr = jwtUtil.getUserIdFromTokenAsString(token);

        // 处理平台管理员 (userId格式为 "platform_X")
        if (userIdStr != null && userIdStr.startsWith("platform_")) {
            String platformIdStr = userIdStr.substring("platform_".length());
            try {
                Long platformId = Long.parseLong(platformIdStr);
                PlatformAdmin admin = platformAdminRepository.findById(platformId)
                        .orElseThrow(() -> new ResourceNotFoundException("平台管理员不存在"));

                // 将 PlatformAdmin 转换为 UserDTO
                return UserDTO.builder()
                        .id(admin.getId())
                        .username(admin.getUsername())
                        .fullName(admin.getRealName())
                        .email(admin.getEmail())
                        .phone(admin.getPhoneNumber())
                        .isActive(true)
                        .build();
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "无效的平台管理员ID格式")
                        .withHint("请重新登录获取有效令牌");
            }
        }

        // 处理普通工厂用户
        try {
            Long userId = Long.parseLong(userIdStr);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
            String factoryType = "FACTORY";
            if (user.getFactory() != null && user.getFactory().getType() != null) {
                factoryType = user.getFactory().getType().name();
            }
            return userMapper.toDTO(user, factoryType);
        } catch (NumberFormatException e) {
            throw new BusinessException(400, "无效的用户ID格式: " + userIdStr)
                    .withHint("请重新登录获取有效令牌");
        }
    }

    @Override
    @Transactional
    public MobileDTO.RegisterPhaseOneResponse registerPhaseOne(MobileDTO.RegisterPhaseOneRequest request) {
        String phoneNumber = request.getPhoneNumber();
        String factoryId = request.getFactoryId();

        // 智能推断 factoryId（如果未提供）
        if (factoryId == null || factoryId.trim().isEmpty()) {
            log.info("factoryId未提供，通过手机号查找白名单: phone={}", phoneNumber);
            List<Whitelist> whitelists = whitelistRepository.findAllByPhoneNumber(phoneNumber);

            if (whitelists.isEmpty()) {
                throw new BusinessException(403, "该手机号未在任何工厂白名单中")
                        .withHint("请联系管理员将该手机号加入工厂白名单").withHintTarget("phoneNumber");
            } else if (whitelists.size() == 1) {
                factoryId = whitelists.get(0).getFactoryId();
                log.info("智能推断成功: phone={}, factoryId={}", phoneNumber, factoryId);
            } else {
                // 手机号在多个工厂白名单中，必须指定
                throw new BusinessException(409, "该手机号在多个工厂白名单中")
                        .withHint("请在注册界面选择具体工厂").withHintTarget("factoryId");
            }
        }

        log.info("移动端注册第一阶段: phone={}, factoryId={}", phoneNumber, factoryId);

        // 检查白名单
        Whitelist whitelist = whitelistRepository.findByFactoryIdAndPhoneNumber(factoryId, phoneNumber)
                .orElseThrow(() -> new BusinessException(403, "该手机号未在白名单中，无法注册")
                        .withHint("请联系管理员将该手机号加入工厂白名单").withHintTarget("phoneNumber"));

        // 检查状态和有效性
        if (!whitelist.isValid()) {
            if (whitelist.getStatus() != WhitelistStatus.ACTIVE) {
                throw new BusinessException(403, "该手机号已被禁用")
                        .withHint("请联系管理员重新启用");
            } else {
                throw new BusinessException(403, "该手机号白名单已过期")
                        .withHint("请联系管理员续期或重新加入白名单");
            }
        }

        // 检查用户是否已存在
        Optional<User> existingUser = userRepository.findByFactoryIdAndPhone(factoryId, phoneNumber);
        boolean isNewUser = !existingUser.isPresent();

        // 生成临时令牌（30分钟有效）
        String tempToken = tempTokenService.generateTempToken(phoneNumber, 30);
        long expiresAt = System.currentTimeMillis() + 30 * 60 * 1000;

        log.info("手机验证成功: phone={}, isNewUser={}", phoneNumber, isNewUser);

        return MobileDTO.RegisterPhaseOneResponse.builder()
                .tempToken(tempToken)
                .expiresAt(expiresAt)
                .phoneNumber(phoneNumber)
                .factoryId(factoryId)
                .isNewUser(isNewUser)
                .message(isNewUser ? "验证成功，请继续填写注册信息" : "该手机号已注册")
                .build();
    }

    @Override
    @Transactional
    public MobileDTO.RegisterPhaseTwoResponse registerPhaseTwo(MobileDTO.RegisterPhaseTwoRequest request) {
        log.info("移动端注册第二阶段: factory={}, username={}", request.getFactoryId(), request.getUsername());

        // 验证临时令牌
        String phoneNumber = tempTokenService.validateAndGetPhone(request.getTempToken());
        if (phoneNumber == null) {
            throw new BusinessException(401, "临时令牌无效或已过期")
                    .withHint("请重新验证手机号");
        }

        // 获取或推断 factoryId
        String factoryId = request.getFactoryId();
        if (factoryId == null || factoryId.trim().isEmpty()) {
            // 从白名单推断 factoryId
            List<Whitelist> whitelists = whitelistRepository.findAllByPhoneNumber(phoneNumber);
            if (whitelists.isEmpty()) {
                throw new BusinessException(400, "无法推断工厂ID")
                        .withHint("请在注册界面选择具体工厂").withHintTarget("factoryId");
            } else if (whitelists.size() == 1) {
                factoryId = whitelists.get(0).getFactoryId();
                log.info("从白名单推断factoryId: phone={}, factoryId={}", phoneNumber, factoryId);
            } else {
                throw new BusinessException(409, "该手机号在多个工厂白名单中")
                        .withHint("请在注册界面选择具体工厂").withHintTarget("factoryId");
            }
        }

        // 检查用户名是否已存在（用户名全局唯一）
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new BusinessException(409, "该用户名已被使用")
                    .withHint("请使用其他用户名").withHintTarget("username");
        }

        // 创建用户
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getRealName());
        user.setPhone(phoneNumber);
        user.setPosition(request.getPosition() != null ? request.getPosition() : FactoryUserRole.unactivated.name());
        user.setIsActive(false); // 需要管理员激活
        user = userRepository.save(user);

        // 删除临时令牌
        tempTokenService.deleteTempToken(request.getTempToken());

        // 构建用户资料
        MobileDTO.UserProfile profile = MobileDTO.UserProfile.builder()
                .name(user.getFullName())
                .phoneNumber(user.getPhone())
                .email(null) // email字段已删除
                .position(user.getPosition())
                .build();

        log.info("移动端注册成功: userId={}, username={}", user.getId(), user.getUsername());

        return MobileDTO.RegisterPhaseTwoResponse.builder()
                .role(user.getPosition() != null ? user.getPosition() : "unactivated") // 使用position字段
                .profile(profile)
                .message("注册成功，请等待管理员激活您的账户")
                .registeredAt(LocalDateTime.now())
                .build();
    }

    /**
     * 解析权限字符串
     */
    private List<String> parsePermissions(String permissions) {
        if (!StringUtils.hasText(permissions)) {
            return new ArrayList<>();
        }
        return Arrays.asList(permissions.split(","));
    }
}
