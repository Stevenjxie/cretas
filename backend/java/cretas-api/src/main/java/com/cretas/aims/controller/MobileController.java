package com.cretas.aims.controller;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.EquipmentAlert;
import com.cretas.aims.entity.enums.AlertStatus;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.SecurityUtils;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.annotation.RateLimit.LimitType;
import com.cretas.aims.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 移动端接口控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile")
@Tag(name = "移动端接口", description = "移动端专用接口")
@RequiredArgsConstructor
public class MobileController {

    private final MobileService mobileService;
    private final EquipmentAlertRepository equipmentAlertRepository;
    private final CookieAuthHelper cookieAuthHelper;

    @Value("${cretas.app.min-version:1.0.0}")
    private String appMinVersion;

    // ==================== 健康检查 ====================

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "公开端点，无需认证。返回 appMinVersion 用于 App 强制升级提示。")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        result.put("appMinVersion", appMinVersion);
        return result;
    }

    // ==================== 认证相关接口 ====================

    @PostMapping("/auth/unified-login")
    @Operation(summary = "统一登录接口")
    public ApiResponse<MobileDTO.LoginResponse> unifiedLogin(
            @RequestBody @Valid MobileDTO.LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        log.info("统一登录: username={}, clientType={}", request.getUsername(),
                httpRequest.getHeader(CookieAuthHelper.CLIENT_TYPE_HEADER));
        MobileDTO.LoginResponse response = mobileService.unifiedLogin(request);

        // For web clients, set HttpOnly cookies in addition to the JSON response
        if (cookieAuthHelper.isWebClient(httpRequest) && response.getToken() != null) {
            cookieAuthHelper.setAuthCookies(httpResponse, response.getToken(), response.getRefreshToken());
            log.debug("Web client detected, auth cookies set for user={}", request.getUsername());
        }

        return ApiResponse.success(response);
    }

    @PostMapping("/auth/demo-login")
    @Operation(summary = "演示账号免密登录", description = "路演扫码演示用. tenant=rest|factory|logistics, 登录配置死的 demo 账号. rest/factory 写操作被只读锁拦截; logistics 在自己租户内可读写排线计划 (JWT factoryId 锁定, 无法跨工厂).")
    @RateLimit(count = 20, period = 60, limitType = LimitType.IP, message = "演示登录请求过于频繁，请稍后再试")
    public ApiResponse<MobileDTO.LoginResponse> demoLogin(
            @RequestParam String tenant,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        log.info("演示登录: tenant={}, clientType={}", tenant,
                httpRequest.getHeader(CookieAuthHelper.CLIENT_TYPE_HEADER));
        MobileDTO.LoginResponse response = mobileService.demoLogin(tenant);

        // For web clients, set HttpOnly cookies in addition to the JSON response
        if (cookieAuthHelper.isWebClient(httpRequest) && response.getToken() != null) {
            cookieAuthHelper.setAuthCookies(httpResponse, response.getToken(), response.getRefreshToken());
        }

        return ApiResponse.success(response);
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "刷新访问令牌", description = "使用刷新令牌获取新的访问令牌。令牌可通过 JSON body {\"refreshToken\":\"...\"} 或 query param ?refreshToken=... 或 cookie 传入。")
    @RateLimit(count = 10, period = 60, limitType = LimitType.USER, message = "令牌刷新过于频繁，请稍后再试")
    public ApiResponse<MobileDTO.LoginResponse> refreshToken(
            @RequestBody(required = false) @Parameter(description = "JSON body 传入刷新令牌") MobileDTO.RefreshTokenRequest body,
            @RequestParam(required = false) @Parameter(description = "刷新令牌（query param，兼容旧调用方）", example = "eyJhbGciOiJIUzI1NiJ9...") String refreshToken,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // Priority: JSON body > query param > cookie
        String tokenToUse = null;
        if (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isEmpty()) {
            tokenToUse = body.getRefreshToken();
        } else if (refreshToken != null && !refreshToken.isEmpty()) {
            tokenToUse = refreshToken;
        } else if (cookieAuthHelper.isWebClient(httpRequest)) {
            tokenToUse = CookieAuthHelper.extractCookieValue(httpRequest, CookieAuthHelper.REFRESH_TOKEN_COOKIE);
        }

        log.debug("刷新令牌, source={}", (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isEmpty()) ? "body" : (refreshToken != null && !refreshToken.isEmpty()) ? "param" : "cookie");
        MobileDTO.LoginResponse response = mobileService.refreshToken(tokenToUse);

        // Update cookies for web clients
        if (cookieAuthHelper.isWebClient(httpRequest) && response.getToken() != null) {
            cookieAuthHelper.setAuthCookies(httpResponse, response.getToken(), response.getRefreshToken());
        }

        return ApiResponse.success(response);
    }

    @PostMapping("/auth/logout")
    @Operation(summary = "用户登出", description = "登出当前用户，可选择性地清除指定设备的登录状态")
    public ApiResponse<Void> logout(
            @RequestParam(required = false) @Parameter(description = "设备ID", example = "device-uuid-12345") String deviceId,
            HttpServletRequest request,
            HttpServletResponse httpResponse) {
        Long userId = SecurityUtils.getCurrentUserId();
        // Extract token from header first, then cookie fallback
        String token = null;
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        if (token == null) {
            token = CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
        }

        log.info("用户登出: userId={}, deviceId={}", userId, deviceId);
        mobileService.logout(userId, deviceId, token);

        // Clear cookies for web clients (always safe to clear, even for mobile)
        if (cookieAuthHelper.isWebClient(request)) {
            cookieAuthHelper.clearAuthCookies(httpResponse);
        }

        return ApiResponse.success();
    }

    // ==================== 忘记密码接口 ====================

    @PostMapping("/auth/send-verification-code")
    @Operation(summary = "发送验证码")
    public ApiResponse<MobileDTO.SendVerificationCodeResponse> sendVerificationCode(
            @RequestBody @Valid MobileDTO.SendVerificationCodeRequest request) {
        log.info("发送验证码: phoneNumber={}, type={}", request.getPhoneNumber(), request.getVerificationType());
        MobileDTO.SendVerificationCodeResponse response = mobileService.sendVerificationCode(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/auth/verify-reset-code")
    @Operation(summary = "验证重置验证码")
    public ApiResponse<MobileDTO.VerifyResetCodeResponse> verifyResetCode(
            @RequestBody @Valid MobileDTO.VerifyResetCodeRequest request) {
        log.info("验证重置码: phoneNumber={}", request.getPhoneNumber());
        MobileDTO.VerifyResetCodeResponse response = mobileService.verifyResetCode(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/auth/forgot-password")
    @Operation(summary = "忘记密码-重置密码")
    public ApiResponse<MobileDTO.ForgotPasswordResponse> forgotPassword(
            @RequestBody @Valid MobileDTO.ForgotPasswordRequest request) {
        log.info("忘记密码重置: phoneNumber={}", request.getPhoneNumber());
        MobileDTO.ForgotPasswordResponse response = mobileService.forgotPassword(request);
        return ApiResponse.success(response);
    }

    // ==================== 设备激活接口 ====================

    @PostMapping("/activation/activate")
    @Operation(summary = "设备激活")
    public ApiResponse<MobileDTO.ActivationResponse> activateDevice(
            @RequestBody @Valid MobileDTO.ActivationRequest request) {
        log.info("设备激活: code={}", request.getActivationCode());
        MobileDTO.ActivationResponse response = mobileService.activateDevice(request);
        return ApiResponse.success(response);
    }

    // ==================== 注册接口 ====================

    @PostMapping("/auth/register-phase-one")
    @Operation(summary = "移动端注册-第一阶段（验证手机号）")
    @RateLimit(count = 3, period = 60, limitType = LimitType.IP, message = "注册请求过于频繁，请60秒后再试")
    public ApiResponse<MobileDTO.RegisterPhaseOneResponse> registerPhaseOne(
            @RequestBody @Valid MobileDTO.RegisterPhaseOneRequest request) {
        log.info("移动端注册第一阶段: phone={}", request.getPhoneNumber());
        MobileDTO.RegisterPhaseOneResponse response = mobileService.registerPhaseOne(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/auth/register-phase-two")
    @Operation(summary = "移动端注册-第二阶段（创建账户）")
    @RateLimit(count = 3, period = 60, limitType = LimitType.IP, message = "注册请求过于频繁，请60秒后再试")
    public ApiResponse<MobileDTO.RegisterPhaseTwoResponse> registerPhaseTwo(
            @RequestBody @Valid MobileDTO.RegisterPhaseTwoRequest request) {
        log.info("移动端注册第二阶段: factoryId={}, username={}", request.getFactoryId(), request.getUsername());
        MobileDTO.RegisterPhaseTwoResponse response = mobileService.registerPhaseTwo(request);
        return ApiResponse.success(response);
    }

    // ==================== 文件上传接口 ====================

    @PostMapping("/upload")
    @Operation(summary = "移动端文件上传", description = "上传图片、文档等文件，支持多文件批量上传")
    public ApiResponse<MobileDTO.UploadResponse> uploadFiles(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(required = false) @Parameter(description = "文件分类", example = "quality_photo") String category,
            @RequestParam(required = false) @Parameter(description = "元数据JSON", example = "{\"batchId\":\"BATCH-001\"}") String metadata) {
        log.info("文件上传: count={}, category={}", files.size(), category);
        MobileDTO.UploadResponse response = mobileService.uploadFiles(files, category, metadata);
        return ApiResponse.success(response);
    }

    // ==================== 仪表盘数据接口 ====================

    @GetMapping("/dashboard/{factoryId}")
    @Operation(summary = "获取移动端仪表盘数据", description = "获取工厂概览数据，包括今日产量、设备状态、待处理任务等")
    public ApiResponse<MobileDTO.DashboardData> getMobileDashboard(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.debug("获取移动端仪表盘数据: factoryId={}, userId={}", factoryId, userId);
        MobileDTO.DashboardData data = mobileService.getDashboardData(factoryId, userId);
        return ApiResponse.success(data);
    }

    // ==================== 数据同步接口 ====================

    @PostMapping("/sync/{factoryId}")
    @Operation(summary = "数据同步", description = "同步移动端离线数据到服务器")
    public ApiResponse<MobileDTO.SyncResponse> syncData(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestBody MobileDTO.SyncRequest request) {
        log.info("数据同步: factoryId={}", factoryId);
        MobileDTO.SyncResponse response = mobileService.syncData(factoryId, request);
        return ApiResponse.success(response);
    }

    @GetMapping("/offline/{factoryId}")
    @Operation(summary = "获取离线数据包", description = "获取工厂离线数据包，供移动端缓存使用")
    public ApiResponse<MobileDTO.OfflineDataPackage> getOfflineDataPackage(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("获取离线数据包: factoryId={}, userId={}", factoryId, userId);
        MobileDTO.OfflineDataPackage data = mobileService.getOfflineDataPackage(factoryId, userId);
        return ApiResponse.success(data);
    }

    // ==================== 推送通知接口 ====================

    @PostMapping("/push/register")
    @Operation(summary = "注册推送通知")
    public ApiResponse<Void> registerPushNotification(
            @RequestBody @Valid MobileDTO.PushRegistration registration) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("注册推送: userId={}, platform={}", userId, registration.getPlatform());
        mobileService.registerPushNotification(userId, registration);
        return ApiResponse.success();
    }

    @DeleteMapping("/push/unregister")
    @Operation(summary = "取消推送通知注册", description = "取消设备的推送通知订阅")
    public ApiResponse<Void> unregisterPushNotification(
            @RequestParam @Parameter(description = "设备令牌", example = "FCM_token_xxxxx") String deviceToken) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("取消推送: userId={}, token={}", userId, deviceToken);
        mobileService.unregisterPushNotification(userId, deviceToken);
        return ApiResponse.success();
    }

    // ==================== 设备管理接口 ====================

    @GetMapping("/devices")
    @Operation(summary = "获取用户设备列表")
    public ApiResponse<List<MobileDTO.DeviceInfo>> getUserDevices() {
        Long userId = SecurityUtils.getCurrentUserId();
        log.debug("获取设备列表: userId={}", userId);
        List<MobileDTO.DeviceInfo> devices = mobileService.getUserDevices(userId);
        return ApiResponse.success(devices);
    }

    @DeleteMapping("/devices/{deviceId}")
    @Operation(summary = "移除设备", description = "从账户中移除已登录的设备")
    public ApiResponse<Void> removeDevice(
            @PathVariable @Parameter(description = "设备ID", example = "device-uuid-12345") String deviceId) {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("移除设备: userId={}, deviceId={}", userId, deviceId);
        mobileService.removeDevice(userId, deviceId);
        return ApiResponse.success();
    }

    // ==================== 版本管理接口 ====================

    @GetMapping("/version/check")
    @Operation(summary = "检查应用版本", description = "检查是否有新版本可更新")
    public ApiResponse<MobileDTO.VersionCheckResponse> checkVersion(
            @RequestParam @Parameter(description = "当前版本", example = "1.0.0") String currentVersion,
            @RequestParam @Parameter(description = "平台: ios/android", example = "android") String platform) {
        log.debug("检查版本: current={}, platform={}", currentVersion, platform);
        MobileDTO.VersionCheckResponse response = mobileService.checkVersion(currentVersion, platform);
        return ApiResponse.success(response);
    }

    // ==================== 配置接口 ====================

    @GetMapping("/config/{factoryId}")
    @Operation(summary = "获取移动端配置", description = "获取工厂的移动端配置信息，如功能开关、主题等")
    public ApiResponse<Object> getMobileConfig(
            @PathVariable @Parameter(description = "工厂ID", example = "F001") String factoryId,
            @RequestParam @Parameter(description = "平台: ios/android", example = "android") String platform) {
        log.debug("获取配置: factoryId={}, platform={}", factoryId, platform);
        Object config = mobileService.getMobileConfig(factoryId, platform);
        return ApiResponse.success(config);
    }

    // ==================== 监控接口 ====================

    @PostMapping("/report/crash")
    @Operation(summary = "上报崩溃日志")
    public ApiResponse<Void> reportCrash(
            @RequestBody Map<String, Object> crashData) {
        log.error("收到崩溃报告: {}", crashData);

        // 解析设备信息和崩溃日志
        MobileDTO.DeviceInfo deviceInfo = null;
        String crashLog = null;

        if (crashData.containsKey("deviceInfo")) {
            // TODO: 解析设备信息
        }
        if (crashData.containsKey("crashLog")) {
            crashLog = crashData.get("crashLog").toString();
        }

        mobileService.reportCrash(deviceInfo, crashLog);
        return ApiResponse.success();
    }

    @PostMapping("/report/performance")
    @Operation(summary = "上报性能数据")
    public ApiResponse<Void> reportPerformance(
            @RequestBody Map<String, Object> performanceData) {
        log.debug("收到性能数据: {}", performanceData);

        // 解析设备信息和性能数据
        MobileDTO.DeviceInfo deviceInfo = null;
        Object perfData = performanceData.get("data");

        if (performanceData.containsKey("deviceInfo")) {
            // TODO: 解析设备信息
        }

        mobileService.reportPerformance(deviceInfo, perfData);
        return ApiResponse.success();
    }

    // ==================== 认证工具接口 ====================

    /**
     * 验证Token
     */
    @GetMapping("/auth/validate")
    @Operation(summary = "验证令牌", description = "验证JWT令牌是否有效")
    public ApiResponse<Boolean> validateToken(
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest httpRequest) {
        String token = resolveToken(authorization, httpRequest);
        boolean isValid = mobileService.validateToken(token);
        return ApiResponse.success(isValid);
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/auth/me")
    @Operation(summary = "获取当前用户信息", description = "根据Token获取当前登录用户的详细信息")
    public ApiResponse<UserDTO> getCurrentUser(
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader(value = "Authorization", required = false) String authorization,
            HttpServletRequest httpRequest) {
        String token = resolveToken(authorization, httpRequest);
        UserDTO user = mobileService.getUserFromToken(token);
        return ApiResponse.success(user);
    }

    /**
     * 修改密码
     */
    @PostMapping("/auth/change-password")
    @Operation(summary = "修改密码", description = "修改当前用户的登录密码")
    public ApiResponse<Void> changePassword(
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Parameter(description = "原密码", required = true, example = "OldPass@123")
            @RequestParam String oldPassword,
            @Parameter(description = "新密码", required = true, example = "NewPass@456")
            @RequestParam String newPassword,
            HttpServletRequest httpRequest) {
        String token = resolveToken(authorization, httpRequest);
        UserDTO user = mobileService.getUserFromToken(token);
        log.info("修改密码: userId={}", user.getId());
        mobileService.changePassword(user.getId(), oldPassword, newPassword);
        return ApiResponse.success("密码修改成功", null);
    }

    /**
     * 重置密码（管理员功能）
     * 需要 factory_super_admin 或 platform_admin 角色
     */
    @PostMapping("/auth/reset-password")
    @Operation(summary = "重置密码（管理员）", description = "管理员重置指定用户的密码，需要管理员权限")
    public ApiResponse<Void> resetPassword(
            HttpServletRequest request,
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @RequestParam String factoryId,
            @Parameter(description = "用户名", required = true, example = "worker001")
            @RequestParam String username,
            @Parameter(description = "新密码", required = true, example = "NewPass@123")
            @RequestParam String newPassword) {
        // 验证调用者是管理员
        String role = (String) request.getAttribute("role");
        if (role == null || (!role.equals("factory_super_admin") && !role.equals("platform_admin")
                && !role.equals("super_admin") && !role.equals("hr_admin"))) {
            throw new BusinessException(403, "无权重置密码，需要管理员权限").withSeverity("error");
        }
        // 🔒 多租户隔离 (2026-06-17 实测确认跨租户账号接管漏洞): /auth/* 被 JwtAuthInterceptor 的
        // path-factoryId gate 排除, 而本端点用**入参** factoryId 定位用户。若不校验, 工厂级管理员
        // (factory_super_admin/hr_admin) 可传别家 factoryId 重置别家用户密码 = 跨租户账号接管。
        // 平台级角色 (platform_admin/super_admin) 可跨工厂 (by design); 工厂级角色必须本厂。
        boolean isPlatformRole = role.equals("platform_admin") || role.equals("super_admin");
        if (!isPlatformRole) {
            String callerFactoryId = (String) request.getAttribute("factoryId");
            if (callerFactoryId == null || !callerFactoryId.equals(factoryId)) {
                throw new BusinessException(403, "无权重置其他工厂的用户密码").withSeverity("error");
            }
        }
        log.info("重置密码: factoryId={}, username={}, operator={}", factoryId, username, request.getAttribute("username"));
        mobileService.resetPassword(factoryId, username, newPassword);
        return ApiResponse.success("密码重置成功", null);
    }

    // ==================== 人员报表接口 ====================

    /**
     * 获取人员总览统计
     */
    @GetMapping("/{factoryId}/personnel/statistics")
    @Operation(summary = "获取人员总览统计", description = "获取工厂人员出勤、工时等统计数据")
    public ApiResponse<MobileDTO.PersonnelStatistics> getPersonnelStatistics(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam(required = false) @Parameter(description = "开始日期", example = "2025-01-01") String startDate,
            @RequestParam(required = false) @Parameter(description = "结束日期", example = "2025-01-31") String endDate) {
        log.info("获取人员统计: factoryId={}, startDate={}, endDate={}", factoryId, startDate, endDate);
        MobileDTO.PersonnelStatistics statistics = mobileService.getPersonnelStatistics(factoryId, startDate, endDate);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取工时排行榜
     */
    @GetMapping("/{factoryId}/personnel/work-hours-ranking")
    @Operation(summary = "获取工时排行榜", description = "获取员工工时排名，默认返回前10名")
    public ApiResponse<List<MobileDTO.WorkHoursRankingItem>> getWorkHoursRanking(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam @Parameter(description = "开始日期", required = true, example = "2025-01-01") String startDate,
            @RequestParam @Parameter(description = "结束日期", required = true, example = "2025-01-31") String endDate,
            @RequestParam(defaultValue = "10") @Parameter(description = "返回前N名", example = "10") Integer limit) {
        log.info("获取工时排行: factoryId={}, startDate={}, endDate={}, limit={}", factoryId, startDate, endDate, limit);
        List<MobileDTO.WorkHoursRankingItem> ranking = mobileService.getWorkHoursRanking(factoryId, startDate, endDate, limit);
        return ApiResponse.success(ranking);
    }

    /**
     * 获取加班统计
     */
    @GetMapping("/{factoryId}/personnel/overtime-statistics")
    @Operation(summary = "获取加班统计", description = "获取员工加班时长统计，可按部门筛选")
    public ApiResponse<MobileDTO.OvertimeStatistics> getOvertimeStatistics(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam @Parameter(description = "开始日期", required = true, example = "2025-01-01") String startDate,
            @RequestParam @Parameter(description = "结束日期", required = true, example = "2025-01-31") String endDate,
            @RequestParam(required = false) @Parameter(description = "部门ID筛选", example = "DEPT001") String departmentId) {
        log.info("获取加班统计: factoryId={}, startDate={}, endDate={}, departmentId={}", factoryId, startDate, endDate, departmentId);
        MobileDTO.OvertimeStatistics statistics = mobileService.getOvertimeStatistics(factoryId, startDate, endDate, departmentId);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取人员绩效统计
     */
    @GetMapping("/{factoryId}/personnel/performance")
    @Operation(summary = "获取人员绩效统计", description = "获取员工绩效评分，可指定单个员工查看详情")
    public ApiResponse<List<MobileDTO.PerformanceItem>> getPersonnelPerformance(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam @Parameter(description = "开始日期", required = true, example = "2025-01-01") String startDate,
            @RequestParam @Parameter(description = "结束日期", required = true, example = "2025-01-31") String endDate,
            @RequestParam(required = false) @Parameter(description = "用户ID", example = "1") Long userId) {
        log.info("获取人员绩效: factoryId={}, startDate={}, endDate={}, userId={}", factoryId, startDate, endDate, userId);
        List<MobileDTO.PerformanceItem> performance = mobileService.getPersonnelPerformance(factoryId, startDate, endDate, userId);
        return ApiResponse.success(performance);
    }

    // ==================== 成本对比接口 ====================

    /**
     * 获取批次成本对比数据
     */
    @GetMapping("/{factoryId}/processing/cost-comparison")
    @Operation(summary = "获取批次成本对比数据", description = "对比多个生产批次的成本数据")
    public ApiResponse<List<MobileDTO.BatchCostData>> getBatchCostComparison(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam @Parameter(description = "批次ID列表（逗号分隔）", required = true, example = "BATCH-001,BATCH-002") String batchIds) {
        log.info("获取批次成本对比: factoryId={}, batchIds={}", factoryId, batchIds);

        // 解析批次ID列表
        List<String> batchIdList = Arrays.asList(batchIds.split(","));

        List<MobileDTO.BatchCostData> costData = mobileService.getBatchCostComparison(factoryId, batchIdList);
        return ApiResponse.success(costData);
    }

    // ==================== 设备告警接口 ====================
    // 注意: 获取告警列表已移至 EquipmentAlertsController

    /**
     * 确认设备告警
     */
    @PostMapping("/{factoryId}/equipment/alerts/{alertId}/acknowledge")
    @Operation(summary = "确认设备告警", description = "确认已收到告警，进入处理流程")
    public ApiResponse<MobileDTO.AlertResponse> acknowledgeAlert(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @PathVariable @Parameter(description = "告警ID", required = true, example = "123") String alertId,
            @RequestBody(required = false) MobileDTO.AcknowledgeAlertRequest request,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        log.info("确认设备告警: factoryId={}, alertId={}, userId={}", factoryId, alertId, userId);

        MobileDTO.AlertResponse response = mobileService.acknowledgeAlert(factoryId, alertId, userId, username, request);
        return ApiResponse.success("告警已确认", response);
    }

    /**
     * 解决设备告警
     */
    @PostMapping("/{factoryId}/equipment/alerts/{alertId}/resolve")
    @Operation(summary = "解决设备告警", description = "标记告警已解决，需提供解决方案")
    public ApiResponse<MobileDTO.AlertResponse> resolveAlert(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @PathVariable @Parameter(description = "告警ID", required = true, example = "123") String alertId,
            @RequestBody(required = false) MobileDTO.ResolveAlertRequest request,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        log.info("解决设备告警: factoryId={}, alertId={}, userId={}", factoryId, alertId, userId);

        MobileDTO.AlertResponse response = mobileService.resolveAlert(factoryId, alertId, userId, username, request);
        return ApiResponse.success("告警已解决", response);
    }

    /**
     * 忽略设备告警
     */
    @PostMapping("/{factoryId}/equipment/alerts/{alertId}/ignore")
    @Operation(summary = "忽略设备告警", description = "标记告警为已忽略，不再显示在活跃告警列表中")
    public ApiResponse<MobileDTO.AlertResponse> ignoreAlert(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @PathVariable @Parameter(description = "告警ID", required = true, example = "123") String alertId,
            @RequestBody(required = false) @Parameter(description = "忽略原因（可选）") MobileDTO.IgnoreAlertRequest request,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute("username") String username) {
        log.info("忽略设备告警: factoryId={}, alertId={}, userId={}, reason={}",
                factoryId, alertId, userId, request != null ? request.getReason() : "未填写");

        // 解析alertId
        Integer alertIdInt;
        try {
            alertIdInt = Integer.parseInt(alertId);
        } catch (NumberFormatException e) {
            log.warn("alertId解析失败: {}", alertId);
            throw new BusinessException(400, "无效的告警ID");
        }

        // 查找告警记录
        EquipmentAlert alert = equipmentAlertRepository.findByFactoryIdAndId(factoryId, alertIdInt)
                .orElseThrow(() -> new RuntimeException("告警不存在: alertId=" + alertId));

        // 检查状态
        if (alert.getStatus() == AlertStatus.IGNORED) {
            throw new BusinessException(400, "该告警已被忽略");
        }

        // 更新为IGNORED状态
        alert.setStatus(AlertStatus.IGNORED);
        alert.setIgnoredAt(LocalDateTime.now());
        alert.setIgnoredBy(userId);
        alert.setIgnoredByName(username);
        if (request != null && request.getReason() != null) {
            alert.setIgnoreReason(request.getReason());
        }

        equipmentAlertRepository.save(alert);
        log.info("告警已忽略: alertId={}, userId={}", alertId, userId);

        // 构建响应
        MobileDTO.AlertResponse response = new MobileDTO.AlertResponse();
        response.setId(alert.getId());
        response.setFactoryId(alert.getFactoryId());
        response.setEquipmentId(alert.getEquipmentId() != null ? String.valueOf(alert.getEquipmentId()) : null);
        response.setAlertType(alert.getAlertType());
        response.setLevel(alert.getLevel().name());
        response.setStatus(alert.getStatus().name());
        response.setMessage(alert.getMessage());
        response.setDetails(alert.getDetails());
        response.setTriggeredAt(alert.getTriggeredAt() != null ? alert.getTriggeredAt().toString() : null);
        response.setIgnoredAt(alert.getIgnoredAt() != null ? alert.getIgnoredAt().toString() : null);
        response.setIgnoredBy(alert.getIgnoredByName());
        response.setIgnoreReason(alert.getIgnoreReason());

        return ApiResponse.success("告警已忽略", response);
    }

    /**
     * 获取设备告警统计信息
     */
    @GetMapping("/{factoryId}/equipment-alerts/statistics")
    @Operation(summary = "获取告警统计", description = "获取设备告警的统计信息，包括总数、按严重程度分类、按类型分类等")
    public ApiResponse<Map<String, Object>> getAlertStatistics(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestParam(required = false, defaultValue = "week") @Parameter(description = "时间范围", example = "week") String timeRange) {
        log.info("获取告警统计: factoryId={}, timeRange={}", factoryId, timeRange);

        // 查询所有告警
        List<EquipmentAlert> allAlerts = equipmentAlertRepository.findByFactoryIdOrderByTriggeredAtDesc(factoryId);

        Map<String, Object> statistics = new java.util.HashMap<>();

        // 总体统计
        long totalAlerts = allAlerts.size();
        long activeAlerts = allAlerts.stream().filter(a -> a.getStatus() == AlertStatus.ACTIVE).count();
        long resolvedAlerts = allAlerts.stream().filter(a -> a.getStatus() == AlertStatus.RESOLVED).count();
        long ignoredAlerts = allAlerts.stream().filter(a -> a.getStatus() == AlertStatus.IGNORED).count();
        long acknowledgedAlerts = allAlerts.stream().filter(a -> a.getStatus() == AlertStatus.ACKNOWLEDGED).count();

        statistics.put("totalAlerts", totalAlerts);
        statistics.put("activeAlerts", activeAlerts);
        statistics.put("resolvedAlerts", resolvedAlerts);
        statistics.put("ignoredAlerts", ignoredAlerts);
        statistics.put("acknowledgedAlerts", acknowledgedAlerts);

        // 按严重程度分类
        Map<String, Long> bySeverity = allAlerts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        a -> a.getLevel().name().toLowerCase(),
                        java.util.stream.Collectors.counting()
                ));
        statistics.put("bySeverity", bySeverity);

        // 按类型分类
        Map<String, Long> byType = allAlerts.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        EquipmentAlert::getAlertType,
                        java.util.stream.Collectors.counting()
                ));
        statistics.put("byType", byType);

        // 按设备分类 (Top 5)
        Map<String, Long> byEquipment = allAlerts.stream()
                .filter(alert -> alert.getEquipmentId() != null)
                .collect(java.util.stream.Collectors.groupingBy(
                        alert -> String.valueOf(alert.getEquipmentId()),
                        java.util.stream.Collectors.counting()
                ))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(5)
                .collect(java.util.LinkedHashMap::new,
                        (m, e) -> m.put(e.getKey(), e.getValue()),
                        LinkedHashMap::putAll);
        statistics.put("byEquipment", byEquipment);

        // 趋势数据（最近7天）
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
        List<Map<String, Object>> trend = new java.util.ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            java.time.LocalDate date = java.time.LocalDate.now().minusDays(i);
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            long count = allAlerts.stream()
                    .filter(a -> a.getTriggeredAt() != null &&
                            !a.getTriggeredAt().isBefore(dayStart) &&
                            a.getTriggeredAt().isBefore(dayEnd))
                    .count();

            Map<String, Object> dayData = new java.util.HashMap<>();
            dayData.put("date", date.toString());
            dayData.put("count", count);
            trend.add(dayData);
        }
        statistics.put("trend", trend);

        // 计算平均响应时间（acknowledgedAt - triggeredAt）
        double avgResponseTime = allAlerts.stream()
                .filter(a -> a.getAcknowledgedAt() != null && a.getTriggeredAt() != null)
                .mapToLong(a -> java.time.Duration.between(a.getTriggeredAt(), a.getAcknowledgedAt()).toMinutes())
                .average()
                .orElse(0.0);
        statistics.put("avgResponseTime", Math.round(avgResponseTime));

        // 计算平均解决时间（resolvedAt - triggeredAt）
        double avgResolutionTime = allAlerts.stream()
                .filter(a -> a.getResolvedAt() != null && a.getTriggeredAt() != null)
                .mapToLong(a -> java.time.Duration.between(a.getTriggeredAt(), a.getResolvedAt()).toMinutes())
                .average()
                .orElse(0.0);
        statistics.put("avgResolutionTime", Math.round(avgResolutionTime));

        // 时间范围
        statistics.put("timeRange", timeRange);
        statistics.put("generatedAt", java.time.LocalDateTime.now());

        return ApiResponse.success(statistics);
    }

    // ========== 用户反馈管理 ==========
    // 注意：工厂设置相关API已迁移至 FactorySettingsController，避免路径冲突

    /**
     * 提交用户反馈
     */
    @PostMapping("/{factoryId}/feedback")
    @Operation(summary = "提交用户反馈", description = "提交问题反馈、功能建议等")
    public ApiResponse<MobileDTO.FeedbackResponse> submitFeedback(
            @PathVariable @Parameter(description = "工厂ID", required = true, example = "F001") String factoryId,
            @RequestBody MobileDTO.SubmitFeedbackRequest request,
            @RequestAttribute("userId") Long userId) {
        log.info("提交用户反馈: factoryId={}, userId={}, type={}", factoryId, userId, request.getType());

        MobileDTO.FeedbackResponse response = mobileService.submitFeedback(factoryId, request, userId);
        return ApiResponse.success("反馈提交成功", response);
    }

    // ==================== Private helpers ====================

    /**
     * Resolve JWT token from Authorization header (mobile) or cookie (web).
     * Used by endpoints that need the raw token (validate, me, change-password).
     */
    private String resolveToken(String authorization, HttpServletRequest request) {
        // 1. Try Authorization header (mobile clients)
        if (authorization != null && !authorization.isEmpty()) {
            return TokenUtils.extractToken(authorization);
        }
        // 2. Fallback: cookie (web clients)
        String cookieToken = CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
        if (cookieToken != null && !cookieToken.isEmpty()) {
            return cookieToken;
        }
        throw new com.cretas.aims.exception.BusinessException("未提供认证信息");
    }
}
