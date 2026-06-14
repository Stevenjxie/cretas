package com.cretas.aims.service.impl;

import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.mobile.MobileAuthService;
import com.cretas.aims.service.mobile.MobileBusinessService;
import com.cretas.aims.service.mobile.MobileDeviceService;
import com.cretas.aims.service.mobile.MobilePasswordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 移动端服务 Facade 实现
 * <p>
 * 保持 {@link MobileService} 接口不变，将所有业务逻辑委托给 4 个专职子服务：
 * <ul>
 *   <li>{@link MobileAuthService} — 认证/登录/注册</li>
 *   <li>{@link MobileDeviceService} — 设备管理/推送</li>
 *   <li>{@link MobilePasswordService} — 密码管理/验证码</li>
 *   <li>{@link MobileBusinessService} — 仪表盘/报表/告警/设置等</li>
 * </ul>
 *
 * @author Cretas Team
 * @version 2.0.0 — Facade refactor
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class MobileServiceImpl implements MobileService {

    private final MobileAuthService mobileAuthService;
    private final MobileDeviceService mobileDeviceService;
    private final MobilePasswordService mobilePasswordService;
    private final MobileBusinessService mobileBusinessService;

    // ==================== Auth ====================

    @Override
    public MobileDTO.LoginResponse unifiedLogin(MobileDTO.LoginRequest request) {
        return mobileAuthService.unifiedLogin(request);
    }

    @Override
    public MobileDTO.LoginResponse demoLogin(String tenant) {
        return mobileAuthService.demoLogin(tenant);
    }

    @Override
    public MobileDTO.LoginResponse refreshToken(String refreshToken) {
        return mobileAuthService.refreshToken(refreshToken);
    }

    @Override
    public void logout(Long userId, String deviceId, String token) {
        mobileAuthService.logout(userId, deviceId, token);
    }

    @Override
    public boolean validateToken(String token) {
        return mobileAuthService.validateToken(token);
    }

    @Override
    public UserDTO getUserFromToken(String token) {
        return mobileAuthService.getUserFromToken(token);
    }

    @Override
    public MobileDTO.RegisterPhaseOneResponse registerPhaseOne(MobileDTO.RegisterPhaseOneRequest request) {
        return mobileAuthService.registerPhaseOne(request);
    }

    @Override
    public MobileDTO.RegisterPhaseTwoResponse registerPhaseTwo(MobileDTO.RegisterPhaseTwoRequest request) {
        return mobileAuthService.registerPhaseTwo(request);
    }

    // ==================== Device ====================

    @Override
    public MobileDTO.ActivationResponse activateDevice(MobileDTO.ActivationRequest request) {
        return mobileDeviceService.activateDevice(request);
    }

    @Override
    public void recordDeviceLogin(Long userId, MobileDTO.DeviceInfo deviceInfo) {
        mobileDeviceService.recordDeviceLogin(userId, deviceInfo);
    }

    @Override
    public List<MobileDTO.DeviceInfo> getUserDevices(Long userId) {
        return mobileDeviceService.getUserDevices(userId);
    }

    @Override
    public void removeDevice(Long userId, String deviceId) {
        mobileDeviceService.removeDevice(userId, deviceId);
    }

    @Override
    public void registerPushNotification(Long userId, MobileDTO.PushRegistration registration) {
        mobileDeviceService.registerPushNotification(userId, registration);
    }

    @Override
    public void unregisterPushNotification(Long userId, String deviceToken) {
        mobileDeviceService.unregisterPushNotification(userId, deviceToken);
    }

    // ==================== Password ====================

    @Override
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        mobilePasswordService.changePassword(userId, oldPassword, newPassword);
    }

    @Override
    public void resetPassword(String factoryId, String username, String newPassword) {
        mobilePasswordService.resetPassword(factoryId, username, newPassword);
    }

    @Override
    public MobileDTO.SendVerificationCodeResponse sendVerificationCode(MobileDTO.SendVerificationCodeRequest request) {
        return mobilePasswordService.sendVerificationCode(request);
    }

    @Override
    public MobileDTO.VerifyResetCodeResponse verifyResetCode(MobileDTO.VerifyResetCodeRequest request) {
        return mobilePasswordService.verifyResetCode(request);
    }

    @Override
    public MobileDTO.ForgotPasswordResponse forgotPassword(MobileDTO.ForgotPasswordRequest request) {
        return mobilePasswordService.forgotPassword(request);
    }

    // ==================== Business ====================

    @Override
    public MobileDTO.DashboardData getDashboardData(String factoryId, Long userId) {
        return mobileBusinessService.getDashboardData(factoryId, userId);
    }

    @Override
    public MobileDTO.SyncResponse syncData(String factoryId, MobileDTO.SyncRequest request) {
        return mobileBusinessService.syncData(factoryId, request);
    }

    @Override
    public MobileDTO.OfflineDataPackage getOfflineDataPackage(String factoryId, Long userId) {
        return mobileBusinessService.getOfflineDataPackage(factoryId, userId);
    }

    @Override
    public MobileDTO.VersionCheckResponse checkVersion(String currentVersion, String platform) {
        return mobileBusinessService.checkVersion(currentVersion, platform);
    }

    @Override
    public Object getMobileConfig(String factoryId, String platform) {
        return mobileBusinessService.getMobileConfig(factoryId, platform);
    }

    @Override
    public MobileDTO.UploadResponse uploadFiles(List<MultipartFile> files, String category, String metadata) {
        return mobileBusinessService.uploadFiles(files, category, metadata);
    }

    @Override
    public void reportCrash(MobileDTO.DeviceInfo deviceInfo, String crashLog) {
        mobileBusinessService.reportCrash(deviceInfo, crashLog);
    }

    @Override
    public void reportPerformance(MobileDTO.DeviceInfo deviceInfo, Object performanceData) {
        mobileBusinessService.reportPerformance(deviceInfo, performanceData);
    }

    @Override
    public MobileDTO.PersonnelStatistics getPersonnelStatistics(String factoryId, String startDate, String endDate) {
        return mobileBusinessService.getPersonnelStatistics(factoryId, startDate, endDate);
    }

    @Override
    public List<MobileDTO.WorkHoursRankingItem> getWorkHoursRanking(String factoryId, String startDate, String endDate, Integer limit) {
        return mobileBusinessService.getWorkHoursRanking(factoryId, startDate, endDate, limit);
    }

    @Override
    public MobileDTO.OvertimeStatistics getOvertimeStatistics(String factoryId, String startDate, String endDate, String departmentId) {
        return mobileBusinessService.getOvertimeStatistics(factoryId, startDate, endDate, departmentId);
    }

    @Override
    public List<MobileDTO.PerformanceItem> getPersonnelPerformance(String factoryId, String startDate, String endDate, Long userId) {
        return mobileBusinessService.getPersonnelPerformance(factoryId, startDate, endDate, userId);
    }

    @Override
    public List<MobileDTO.BatchCostData> getBatchCostComparison(String factoryId, List<String> batchIds) {
        return mobileBusinessService.getBatchCostComparison(factoryId, batchIds);
    }

    @Override
    public com.cretas.aims.dto.common.PageResponse<MobileDTO.AlertResponse> getEquipmentAlerts(String factoryId, String status, com.cretas.aims.dto.common.PageRequest pageRequest) {
        return mobileBusinessService.getEquipmentAlerts(factoryId, status, pageRequest);
    }

    @Override
    public MobileDTO.AlertResponse acknowledgeAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.AcknowledgeAlertRequest request) {
        return mobileBusinessService.acknowledgeAlert(factoryId, alertId, userId, username, request);
    }

    @Override
    public MobileDTO.AlertResponse resolveAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.ResolveAlertRequest request) {
        return mobileBusinessService.resolveAlert(factoryId, alertId, userId, username, request);
    }

    @Override
    public MobileDTO.FactorySettingsResponse getFactorySettings(String factoryId) {
        return mobileBusinessService.getFactorySettings(factoryId);
    }

    @Override
    public MobileDTO.FactorySettingsResponse updateFactorySettings(String factoryId, MobileDTO.UpdateFactorySettingsRequest request, Long userId) {
        return mobileBusinessService.updateFactorySettings(factoryId, request, userId);
    }

    @Override
    public MobileDTO.FeedbackResponse submitFeedback(String factoryId, MobileDTO.SubmitFeedbackRequest request, Long userId) {
        return mobileBusinessService.submitFeedback(factoryId, request, userId);
    }
}
