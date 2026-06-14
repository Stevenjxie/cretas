package com.cretas.aims.service;

import com.cretas.aims.dto.MobileDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 移动端服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface MobileService {

    /**
     * 移动端统一登录
     *
     * @param request 登录请求
     * @return 登录响应
     */
    MobileDTO.LoginResponse unifiedLogin(MobileDTO.LoginRequest request);

    /**
     * 演示账号免密登录 (路演扫码演示)
     *
     * @param tenant 演示业态 ("rest" | "factory")
     * @return 登录响应
     */
    MobileDTO.LoginResponse demoLogin(String tenant);

    /**
     * 移动端设备激活
     *
     * @param request 激活请求
     * @return 激活响应
     */
    MobileDTO.ActivationResponse activateDevice(MobileDTO.ActivationRequest request);

    /**
     * 移动端文件上传
     *
     * @param files 文件列表
     * @param category 文件分类
     * @param metadata 元数据
     * @return 上传响应
     */
    MobileDTO.UploadResponse uploadFiles(List<MultipartFile> files, String category, String metadata);

    /**
     * 获取移动端仪表盘数据
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @return 仪表盘数据
     */
    MobileDTO.DashboardData getDashboardData(String factoryId, Long userId);

    /**
     * 数据同步
     *
     * @param factoryId 工厂ID
     * @param request 同步请求
     * @return 同步响应
     */
    MobileDTO.SyncResponse syncData(String factoryId, MobileDTO.SyncRequest request);

    /**
     * 注册推送通知
     *
     * @param userId 用户ID
     * @param registration 推送注册信息
     */
    void registerPushNotification(Long userId, MobileDTO.PushRegistration registration);

    /**
     * 取消推送通知注册
     *
     * @param userId 用户ID
     * @param deviceToken 设备令牌
     */
    void unregisterPushNotification(Long userId, String deviceToken);

    /**
     * 检查应用版本
     *
     * @param currentVersion 当前版本
     * @param platform 平台
     * @return 版本检查响应
     */
    MobileDTO.VersionCheckResponse checkVersion(String currentVersion, String platform);

    /**
     * 获取离线数据包
     *
     * @param factoryId 工厂ID
     * @param userId 用户ID
     * @return 离线数据包
     */
    MobileDTO.OfflineDataPackage getOfflineDataPackage(String factoryId, Long userId);

    /**
     * 记录设备登录信息
     *
     * @param userId 用户ID
     * @param deviceInfo 设备信息
     */
    void recordDeviceLogin(Long userId, MobileDTO.DeviceInfo deviceInfo);

    /**
     * 获取用户设备列表
     *
     * @param userId 用户ID
     * @return 设备信息列表
     */
    List<MobileDTO.DeviceInfo> getUserDevices(Long userId);

    /**
     * 移除设备
     *
     * @param userId 用户ID
     * @param deviceId 设备ID
     */
    void removeDevice(Long userId, String deviceId);

    /**
     * 刷新访问令牌
     *
     * @param refreshToken 刷新令牌
     * @return 新的登录响应
     */
    MobileDTO.LoginResponse refreshToken(String refreshToken);

    /**
     * 移动端登出
     *
     * @param userId 用户ID
     * @param deviceId 设备ID
     * @param token 当前请求的JWT token，用于加入黑名单
     */
    void logout(Long userId, String deviceId, String token);

    /**
     * 获取移动端配置
     *
     * @param factoryId 工厂ID
     * @param platform 平台
     * @return 配置信息
     */
    Object getMobileConfig(String factoryId, String platform);

    /**
     * 上报崩溃日志
     *
     * @param deviceInfo 设备信息
     * @param crashLog 崩溃日志
     */
    void reportCrash(MobileDTO.DeviceInfo deviceInfo, String crashLog);

    /**
     * 上报性能数据
     *
     * @param deviceInfo 设备信息
     * @param performanceData 性能数据
     */
    void reportPerformance(MobileDTO.DeviceInfo deviceInfo, Object performanceData);

    /**
     * 移动端注册第一阶段（验证手机号）
     *
     * @param request 注册请求
     * @return 注册响应
     */
    MobileDTO.RegisterPhaseOneResponse registerPhaseOne(MobileDTO.RegisterPhaseOneRequest request);

    /**
     * 移动端注册第二阶段（创建账户）
     *
     * @param request 注册请求
     * @return 注册响应
     */
    MobileDTO.RegisterPhaseTwoResponse registerPhaseTwo(MobileDTO.RegisterPhaseTwoRequest request);

    /**
     * 验证Token
     *
     * @param token 令牌
     * @return 是否有效
     */
    boolean validateToken(String token);

    /**
     * 从Token获取用户信息
     *
     * @param token 令牌
     * @return 用户信息
     */
    com.cretas.aims.dto.user.UserDTO getUserFromToken(String token);

    /**
     * 修改密码
     *
     * @param userId 用户ID
     * @param oldPassword 旧密码
     * @param newPassword 新密码
     */
    void changePassword(Long userId, String oldPassword, String newPassword);

    /**
     * 重置密码
     *
     * @param factoryId 工厂ID
     * @param username 用户名
     * @param newPassword 新密码
     */
    void resetPassword(String factoryId, String username, String newPassword);

    // ==================== 忘记密码相关 ====================

    /**
     * 发送验证码
     *
     * @param request 发送验证码请求
     * @return 发送验证码响应
     */
    MobileDTO.SendVerificationCodeResponse sendVerificationCode(MobileDTO.SendVerificationCodeRequest request);

    /**
     * 验证重置验证码
     *
     * @param request 验证码验证请求
     * @return 验证码验证响应
     */
    MobileDTO.VerifyResetCodeResponse verifyResetCode(MobileDTO.VerifyResetCodeRequest request);

    /**
     * 忘记密码-重置密码
     *
     * @param request 重置密码请求
     * @return 重置密码响应
     */
    MobileDTO.ForgotPasswordResponse forgotPassword(MobileDTO.ForgotPasswordRequest request);

    // ==================== 人员报表相关 ====================

    /**
     * 获取人员总览统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期（可选）
     * @param endDate 结束日期（可选）
     * @return 人员统计数据
     */
    MobileDTO.PersonnelStatistics getPersonnelStatistics(String factoryId, String startDate, String endDate);

    /**
     * 获取工时排行榜
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param limit 返回前N名
     * @return 工时排行榜
     */
    List<MobileDTO.WorkHoursRankingItem> getWorkHoursRanking(String factoryId, String startDate, String endDate, Integer limit);

    /**
     * 获取加班统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param departmentId 部门ID（可选）
     * @return 加班统计数据
     */
    MobileDTO.OvertimeStatistics getOvertimeStatistics(String factoryId, String startDate, String endDate, String departmentId);

    /**
     * 获取人员绩效统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param userId 用户ID（可选）
     * @return 人员绩效列表
     */
    List<MobileDTO.PerformanceItem> getPersonnelPerformance(String factoryId, String startDate, String endDate, Long userId);

    // ==================== 成本对比相关 ====================

    /**
     * 获取批次成本对比数据
     *
     * @param factoryId 工厂ID
     * @param batchIds 批次ID列表
     * @return 批次成本数据列表
     */
    List<MobileDTO.BatchCostData> getBatchCostComparison(String factoryId, List<String> batchIds);

    // ==================== 设备告警相关 ====================

    /**
     * 获取设备告警列表
     *
     * @param factoryId 工厂ID
     * @param status 告警状态（可选）
     * @param pageRequest 分页请求
     * @return 告警列表
     */
    com.cretas.aims.dto.common.PageResponse<MobileDTO.AlertResponse> getEquipmentAlerts(String factoryId, String status, com.cretas.aims.dto.common.PageRequest pageRequest);

    /**
     * 确认设备告警
     *
     * @param factoryId 工厂ID
     * @param alertId 告警ID（支持数字ID或动态ID如MAINT_1）
     * @param userId 用户ID
     * @param username 用户名
     * @param request 确认请求
     * @return 告警响应
     */
    MobileDTO.AlertResponse acknowledgeAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.AcknowledgeAlertRequest request);

    /**
     * 解决设备告警
     *
     * @param factoryId 工厂ID
     * @param alertId 告警ID（支持数字ID或动态ID如MAINT_1）
     * @param userId 用户ID
     * @param username 用户名
     * @param request 解决请求
     * @return 告警响应
     */
    MobileDTO.AlertResponse resolveAlert(String factoryId, String alertId, Long userId, String username, MobileDTO.ResolveAlertRequest request);

    /**
     * 获取工厂设置
     *
     * @param factoryId 工厂ID
     * @return 工厂设置响应
     */
    MobileDTO.FactorySettingsResponse getFactorySettings(String factoryId);

    /**
     * 更新工厂设置
     *
     * @param factoryId 工厂ID
     * @param request 更新请求
     * @param userId 用户ID
     * @return 工厂设置响应
     */
    MobileDTO.FactorySettingsResponse updateFactorySettings(String factoryId, MobileDTO.UpdateFactorySettingsRequest request, Long userId);

    /**
     * 提交用户反馈
     *
     * @param factoryId 工厂ID
     * @param request 反馈请求
     * @param userId 用户ID
     * @return 反馈响应
     */
    MobileDTO.FeedbackResponse submitFeedback(String factoryId, MobileDTO.SubmitFeedbackRequest request, Long userId);
}
