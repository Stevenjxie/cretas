package com.cretas.aims.service;

import com.cretas.aims.dto.WhitelistDTO;
import com.cretas.aims.dto.common.PageResponse;
import org.springframework.data.domain.Pageable;
import java.util.List;
/**
 * 白名单管理服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface WhitelistService {
    /**
     * 批量添加白名单
     *
     * @param factoryId 工厂ID
     * @param request 批量添加请求
     * @return 批量操作结果
     */
    WhitelistDTO.BatchResult batchAdd(String factoryId, Long userId, WhitelistDTO.BatchAddRequest request);
     /**
     * 获取白名单列表
     * @param queryRequest 查询条件
     * @param pageable 分页参数
     * @return 白名单分页列表
      */
    PageResponse<WhitelistDTO> getWhitelist(String factoryId, WhitelistDTO.QueryRequest queryRequest, Pageable pageable);
     /**
     * 获取白名单详情
     * @param id 白名单ID
     * @return 白名单详情
      */
    WhitelistDTO getWhitelistById(String factoryId, Integer id);
     /**
     * 更新白名单
     * @param request 更新请求
     * @return 更新后的白名单
      */
    WhitelistDTO updateWhitelist(String factoryId, Integer id, WhitelistDTO.UpdateRequest request);
     /**
     * 删除白名单
      */
    void deleteWhitelist(String factoryId, Integer id);
     /**
     * 批量删除白名单
     * @param ids ID列表
     * @return 删除数量
      */
    Integer batchDelete(String factoryId, List<Integer> ids);
     /**
     * 获取白名单统计信息
     * @return 统计信息
      */
    WhitelistDTO.WhitelistStats getStats(String factoryId);
     /**
     * 更新过期的白名单状态
     * @return 更新数量
      */
    Integer updateExpiredWhitelist(String factoryId);
     /**
     * 更新达到使用上限的白名单状态
      */
    Integer updateLimitReachedWhitelist();
     /**
     * 验证手机号是否在白名单中
     * @param phoneNumber 手机号
     * @return 验证结果
      */
    WhitelistDTO.ValidationResponse validatePhoneNumber(String factoryId, String phoneNumber);
     /**
     * 增加白名单使用次数
      */
    void incrementUsage(String factoryId, String phoneNumber);
     /**
     * 搜索白名单
     * @param keyword 关键词
     * @return 搜索结果
      */
    PageResponse<WhitelistDTO> searchWhitelist(String factoryId, String keyword, Pageable pageable);
     /**
     * 获取即将过期的白名单
     * @param days 天数（默认7天）
     * @return 即将过期的白名单列表
      */
    List<WhitelistDTO> getExpiringSoon(String factoryId, Integer days);
     /**
     * 获取最活跃的白名单用户
     * @param limit 限制数量
     * @return 最活跃用户列表
      */
    List<WhitelistDTO> getMostActiveUsers(String factoryId, Integer limit);
     /**
     * 获取最近使用的白名单
     * @return 最近使用列表
      */
    List<WhitelistDTO> getRecentlyUsed(String factoryId, Integer limit);
     /**
     * 导出白名单
     * @param status 状态筛选
     * @return 导出数据（CSV格式）
      */
    String exportWhitelist(String factoryId, String status);
     /**
     * 导入白名单
     * @param csvData CSV数据
     * @return 导入结果
      */
    WhitelistDTO.BatchResult importWhitelist(String factoryId, String csvData);
     /**
     * 清理已删除的记录
     * @param daysOld 多少天前的记录
     * @return 清理数量
      */
    Integer cleanupDeleted(Integer daysOld);
     /**
     * 重置使用次数
      */
    void resetUsageCount(String factoryId, Integer id);
     /**
     * 延长有效期
     * @param days 延长天数
      */
    WhitelistDTO extendExpiration(String factoryId, Integer id, Integer days);
}
