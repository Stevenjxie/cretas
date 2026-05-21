package com.cretas.aims.service.impl;

import com.cretas.aims.service.OssService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * OSS 服务空实现 - 当 OSS 未启用时使用
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-27
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpOssServiceImpl implements OssService {

    public NoOpOssServiceImpl() {
        log.info("OSS 服务未启用，使用 NoOp 实现");
    }

    @Override
    public String uploadImage(MultipartFile file, String category, String factoryId) {
        log.warn("OSS 未启用，无法上传图片");
        throw new UnsupportedOperationException("OSS 服务未启用，请在 application.properties 中配置 aliyun.oss.enabled=true");
    }

    @Override
    public String uploadVideo(MultipartFile file, String factoryId) {
        log.warn("OSS 未启用，无法上传视频");
        throw new UnsupportedOperationException("OSS 服务未启用");
    }

    @Override
    public String uploadAudio(String audioData, String format, String factoryId, String sessionId) {
        log.warn("OSS 未启用，无法上传音频");
        throw new UnsupportedOperationException("OSS 服务未启用");
    }

    @Override
    public String uploadAudioStream(InputStream inputStream, String filename, String factoryId) {
        log.warn("OSS 未启用，无法上传音频流");
        throw new UnsupportedOperationException("OSS 服务未启用");
    }

    @Override
    public String uploadFile(MultipartFile file, String category, String factoryId) {
        log.warn("OSS 未启用，无法上传文件");
        throw new UnsupportedOperationException("OSS 服务未启用");
    }

    @Override
    public String uploadBytes(byte[] bytes, String category, String factoryId,
            String filename, String contentType) {
        log.warn("OSS 未启用，无法上传字节流: category={}, filename={}, size={}",
                category, filename, bytes != null ? bytes.length : 0);
        throw new UnsupportedOperationException("OSS 服务未启用");
    }

    @Override
    public boolean deleteFile(String ossUrl) {
        log.warn("OSS 未启用，无法删除文件: {}", ossUrl);
        return false;
    }

    @Override
    public int deleteFiles(List<String> ossUrls) {
        log.warn("OSS 未启用，无法批量删除文件");
        return 0;
    }

    @Override
    public String generatePresignedUrl(String ossPath, int expireSeconds) {
        log.warn("OSS 未启用，无法生成签名URL");
        return null;
    }

    @Override
    public Map<String, String> generateUploadCredentials(String category, String factoryId) {
        log.warn("OSS 未启用，无法生成上传凭证");
        return null;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Map<String, Object> getFileInfo(String ossUrl) {
        log.warn("OSS 未启用，无法获取文件信息");
        return null;
    }
}
