package com.cretas.aims.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

/**
 * 阿里云 OSS 存储服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
public interface OssService {

    // ==================== 文件上传 ====================

    /**
     * 上传图片到 media bucket
     *
     * @param file     图片文件
     * @param category 分类: products, inspections, batches
     * @param factoryId 工厂ID
     * @return OSS URL
     */
    String uploadImage(MultipartFile file, String category, String factoryId);

    /**
     * 上传视频到 media bucket
     *
     * @param file     视频文件
     * @param factoryId 工厂ID
     * @return OSS URL
     */
    String uploadVideo(MultipartFile file, String factoryId);

    /**
     * 上传音频到 audio bucket
     *
     * @param audioData Base64 编码的音频数据
     * @param format    音频格式
     * @param factoryId 工厂ID
     * @param sessionId 会话ID
     * @return OSS URL
     */
    String uploadAudio(String audioData, String format, String factoryId, String sessionId);

    /**
     * 上传音频流到 audio bucket
     *
     * @param inputStream 音频输入流
     * @param filename    文件名
     * @param factoryId   工厂ID
     * @return OSS URL
     */
    String uploadAudioStream(InputStream inputStream, String filename, String factoryId);

    /**
     * 通用文件上传 (自动判断 bucket)
     *
     * @param file      文件
     * @param category  分类
     * @param factoryId 工厂ID
     * @return OSS URL
     */
    String uploadFile(MultipartFile file, String category, String factoryId);

    /**
     * 上传任意字节数组到 media bucket (主要场景: PDF/Excel 等服务端生成的文档).
     *
     * <p>Sprint 9 P1.2 监管上报 PDF 用. caller 自行决定 contentType (e.g. "application/pdf").
     *
     * @param bytes       原始字节
     * @param category    分类 (e.g. "regulatory-reports", "purchase-orders-pdf")
     * @param factoryId   工厂ID
     * @param filename    原始文件名 (e.g. "RECALL-20260521-001.pdf") — 仅用于 OSS object key 后缀
     * @param contentType MIME type (e.g. "application/pdf")
     * @return OSS URL; null if OSS service is unavailable (NoOp impl 直接抛
     *         {@link UnsupportedOperationException}, caller 应 try/catch)
     */
    String uploadBytes(byte[] bytes, String category, String factoryId,
            String filename, String contentType);

    // ==================== 文件删除 ====================

    /**
     * 删除文件
     *
     * @param ossUrl OSS URL
     * @return 是否成功
     */
    boolean deleteFile(String ossUrl);

    /**
     * 批量删除文件
     *
     * @param ossUrls OSS URL 列表
     * @return 成功删除的数量
     */
    int deleteFiles(java.util.List<String> ossUrls);

    // ==================== 文件访问 ====================

    /**
     * 生成临时访问 URL (私有文件)
     *
     * @param ossPath OSS 路径
     * @param expireSeconds 过期时间(秒)
     * @return 签名 URL
     */
    String generatePresignedUrl(String ossPath, int expireSeconds);

    /**
     * 生成上传凭证 (前端直传)
     *
     * @param category  分类
     * @param factoryId 工厂ID
     * @return 上传凭证信息
     */
    Map<String, String> generateUploadCredentials(String category, String factoryId);

    // ==================== 工具方法 ====================

    /**
     * 检查 OSS 服务是否可用
     *
     * @return 是否可用
     */
    boolean isAvailable();

    /**
     * 获取文件信息
     *
     * @param ossUrl OSS URL
     * @return 文件元信息
     */
    Map<String, Object> getFileInfo(String ossUrl);
}
