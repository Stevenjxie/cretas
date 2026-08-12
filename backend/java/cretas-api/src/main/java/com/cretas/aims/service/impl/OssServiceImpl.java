package com.cretas.aims.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.DeleteObjectsRequest;
import com.aliyun.oss.model.DeleteObjectsResult;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.OssService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 阿里云 OSS 存储服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
// 🔴 2026-08-12 实测事故: 这里原本是 @ConditionalOnBean(OSS.class)。
//
// @ConditionalOnBean 只能匹配「到目前为止已经处理过的」bean 定义 —— Spring 自己的
// javadoc 因此写明它**只应该用在自动配置类上**。用在被组件扫描的 @Service 上时,
// 它的成立与否取决于扫描顺序, 而扫描顺序就是 **jar 里 class 条目的物理顺序**。
//
// 提供 OSS 的是 OssConfig#ossClient()(@Bean, 见下方同款条件), 要等 OssConfig 作为
// 配置类被解析后才注册。所以:
//   OssConfig 排在本类前面 → OSS 定义已在 → 条件成立 → 一切正常
//   OssConfig 排在本类后面 → OSS 定义还没有 → 条件不成立 → 本 bean 被静默跳过
//                          → InvoiceServiceImpl 构造器第 4 个参数找不到 OssService
//                          → APPLICATION FAILED TO START
//
// 实测两份 jar(仅差一个无关类的方法体, 9361 个条目里只有 4 个不同):
//   能起来的:  OssConfig #1620, 本类 #6765   ✅
//   起不来的:  本类 #1139,      OssConfig #6406  ❌ 确定性复现 3 次
// 也就是说**任何一次重新构建都可能把顺序翻过来**, 与改了什么代码无关 —— 之前一直没炸
// 是运气。
//
// 修法: 换成与 ossClient() 完全相同的属性条件。语义等价(本 bean 存在 ⟺ OSS 存在),
// 但属性条件与注册顺序无关。守卫见 ConditionalOnBeanOnScannedComponentTest。
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OssServiceImpl implements OssService {

    private final OSS ossClient;
    private final OssConfig ossConfig;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/mpeg", "video/quicktime", "video/x-msvideo", "video/webm");
    private static final Set<String> AUDIO_TYPES = Set.of("audio/mpeg", "audio/wav", "audio/ogg", "audio/webm", "audio/x-raw");

    // ==================== 文件上传 ====================

    @Override
    public String uploadImage(MultipartFile file, String category, String factoryId) {
        validateImageType(file);
        String objectKey = buildObjectKey("images/" + category, factoryId, getExtension(file.getOriginalFilename()));
        return uploadToOss(ossConfig.getMediaBucket(), objectKey, file);
    }

    @Override
    public String uploadVideo(MultipartFile file, String factoryId) {
        validateVideoType(file);
        String objectKey = buildObjectKey("videos", factoryId, getExtension(file.getOriginalFilename()));
        return uploadToOss(ossConfig.getMediaBucket(), objectKey, file);
    }

    @Override
    public String uploadAudio(String audioData, String format, String factoryId, String sessionId) {
        try {
            byte[] audioBytes = Base64.getDecoder().decode(audioData);
            String extension = getAudioExtension(format);
            String filename = sessionId + "_" + System.currentTimeMillis() + extension;
            String objectKey = buildObjectKey("voice-recognition", factoryId, filename);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(audioBytes);
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(audioBytes.length);
            metadata.setContentType(getAudioContentType(format));

            ossClient.putObject(ossConfig.getAudioBucket(), objectKey, inputStream, metadata);

            String url = ossConfig.getAudioUrlPrefix() + "/" + objectKey;
            log.info("音频上传成功: {}", url);
            return url;
        } catch (Exception e) {
            log.error("音频上传失败: factoryId={}, sessionId={}", factoryId, sessionId, e);
            throw new RuntimeException("音频上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadAudioStream(InputStream inputStream, String filename, String factoryId) {
        try {
            String objectKey = buildObjectKey("voice-recognition", factoryId, filename);

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType("audio/mpeg");

            ossClient.putObject(ossConfig.getAudioBucket(), objectKey, inputStream, metadata);

            String url = ossConfig.getAudioUrlPrefix() + "/" + objectKey;
            log.info("音频流上传成功: {}", url);
            return url;
        } catch (Exception e) {
            log.error("音频流上传失败", e);
            throw new RuntimeException("音频流上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadBytes(byte[] bytes, String category, String factoryId,
            String filename, String contentType) {
        try {
            String objectKey = buildObjectKey(category, factoryId,
                    filename != null && !filename.isBlank() ? filename : "doc");

            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(bytes.length);
            metadata.setContentType(contentType != null ? contentType : "application/octet-stream");

            ossClient.putObject(ossConfig.getMediaBucket(), objectKey,
                    new ByteArrayInputStream(bytes), metadata);

            String url = ossConfig.getMediaUrlPrefix() + "/" + objectKey;
            log.info("字节流上传成功: bucket={}, key={}, size={}, contentType={}",
                    ossConfig.getMediaBucket(), objectKey, bytes.length, contentType);
            return url;
        } catch (Exception e) {
            log.error("字节流上传失败: category={}, factoryId={}, filename={}",
                    category, factoryId, filename, e);
            throw new RuntimeException("字节流上传失败: " + e.getMessage());
        }
    }

    @Override
    public String uploadFile(MultipartFile file, String category, String factoryId) {
        String contentType = file.getContentType();

        if (contentType != null) {
            if (IMAGE_TYPES.contains(contentType)) {
                return uploadImage(file, category, factoryId);
            } else if (VIDEO_TYPES.contains(contentType)) {
                return uploadVideo(file, factoryId);
            } else if (AUDIO_TYPES.contains(contentType)) {
                String objectKey = buildObjectKey("voice-recognition", factoryId, getExtension(file.getOriginalFilename()));
                return uploadToOss(ossConfig.getAudioBucket(), objectKey, file);
            }
        }

        // 默认上传到 media bucket
        String objectKey = buildObjectKey("files/" + category, factoryId, getExtension(file.getOriginalFilename()));
        return uploadToOss(ossConfig.getMediaBucket(), objectKey, file);
    }

    // ==================== 文件删除 ====================

    @Override
    public boolean deleteFile(String ossUrl) {
        try {
            String[] parts = parseOssUrl(ossUrl);
            if (parts == null) {
                return false;
            }
            ossClient.deleteObject(parts[0], parts[1]);
            log.info("文件删除成功: {}", ossUrl);
            return true;
        } catch (Exception e) {
            log.error("文件删除失败: {}", ossUrl, e);
            return false;
        }
    }

    @Override
    public int deleteFiles(List<String> ossUrls) {
        int successCount = 0;
        Map<String, List<String>> bucketKeys = new HashMap<>();

        // 按 bucket 分组
        for (String url : ossUrls) {
            String[] parts = parseOssUrl(url);
            if (parts != null) {
                bucketKeys.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
            }
        }

        // 批量删除
        for (Map.Entry<String, List<String>> entry : bucketKeys.entrySet()) {
            try {
                DeleteObjectsRequest request = new DeleteObjectsRequest(entry.getKey());
                request.setKeys(entry.getValue());
                DeleteObjectsResult result = ossClient.deleteObjects(request);
                successCount += result.getDeletedObjects().size();
            } catch (Exception e) {
                log.error("批量删除失败: bucket={}", entry.getKey(), e);
            }
        }

        return successCount;
    }

    // ==================== 文件访问 ====================

    @Override
    public String generatePresignedUrl(String ossPath, int expireSeconds) {
        try {
            String[] parts = parseOssUrl(ossPath);
            if (parts == null) {
                // 假设是 media bucket 的相对路径
                parts = new String[]{ossConfig.getMediaBucket(), ossPath};
            }

            Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
            URL url = ossClient.generatePresignedUrl(parts[0], parts[1], expiration);
            return url.toString();
        } catch (Exception e) {
            log.error("生成签名URL失败: {}", ossPath, e);
            return null;
        }
    }

    @Override
    public Map<String, String> generateUploadCredentials(String category, String factoryId) {
        // 生成前端直传凭证 (STS Token)
        // 注：完整实现需要集成 STS 服务，这里返回基本信息
        String bucket = category.contains("audio") ? ossConfig.getAudioBucket() : ossConfig.getMediaBucket();
        String dir = buildObjectKey(category, factoryId, "");

        Map<String, String> credentials = new HashMap<>();
        credentials.put("bucket", bucket);
        credentials.put("region", "cn-shanghai");
        credentials.put("dir", dir);
        credentials.put("endpoint", ossConfig.getEndpoint());
        credentials.put("expireTime", String.valueOf(System.currentTimeMillis() + 3600000));

        log.info("生成上传凭证: bucket={}, dir={}", bucket, dir);
        return credentials;
    }

    // ==================== 工具方法 ====================

    @Override
    public boolean isAvailable() {
        try {
            ossClient.listBuckets();
            return true;
        } catch (Exception e) {
            log.warn("OSS 服务不可用: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getFileInfo(String ossUrl) {
        try {
            String[] parts = parseOssUrl(ossUrl);
            if (parts == null) {
                return null;
            }

            ObjectMetadata metadata = ossClient.getObjectMetadata(parts[0], parts[1]);
            Map<String, Object> info = new HashMap<>();
            info.put("size", metadata.getContentLength());
            info.put("contentType", metadata.getContentType());
            info.put("lastModified", metadata.getLastModified());
            info.put("etag", metadata.getETag());
            return info;
        } catch (Exception e) {
            log.error("获取文件信息失败: {}", ossUrl, e);
            return null;
        }
    }

    // ==================== 私有方法 ====================

    private String uploadToOss(String bucket, String objectKey, MultipartFile file) {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(file.getContentType());

            PutObjectResult result = ossClient.putObject(bucket, objectKey, file.getInputStream(), metadata);

            String urlPrefix = bucket.equals(ossConfig.getAudioBucket())
                    ? ossConfig.getAudioUrlPrefix()
                    : ossConfig.getMediaUrlPrefix();

            String url = urlPrefix + "/" + objectKey;
            log.info("文件上传成功: bucket={}, key={}, url={}", bucket, objectKey, url);
            return url;
        } catch (Exception e) {
            log.error("文件上传失败: bucket={}, key={}", bucket, objectKey, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage());
        }
    }

    private String buildObjectKey(String category, String factoryId, String filename) {
        String dateDir = LocalDate.now().format(DATE_FORMAT);
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        if (filename == null || filename.isEmpty()) {
            return String.format("%s/%s/%s/%s", factoryId, category, dateDir, uuid);
        }

        return String.format("%s/%s/%s/%s_%s", factoryId, category, dateDir, uuid, filename);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    private String getAudioExtension(String format) {
        switch (format.toLowerCase()) {
            case "mp3":
            case "lame":
                return ".mp3";
            case "speex":
            case "speex-wb":
                return ".spx";
            case "wav":
                return ".wav";
            default:
                return ".raw";
        }
    }

    private String getAudioContentType(String format) {
        switch (format.toLowerCase()) {
            case "mp3":
            case "lame":
                return "audio/mpeg";
            case "wav":
                return "audio/wav";
            default:
                return "audio/x-raw";
        }
    }

    private void validateImageType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !IMAGE_TYPES.contains(contentType)) {
            throw new BusinessException(400, "不支持的图片格式: " + contentType)
                    .withHint("支持的格式: " + String.join(", ", IMAGE_TYPES))
                    .withHintTarget("file");
        }
    }

    private void validateVideoType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !VIDEO_TYPES.contains(contentType)) {
            throw new BusinessException(400, "不支持的视频格式: " + contentType)
                    .withHint("支持的格式: " + String.join(", ", VIDEO_TYPES))
                    .withHintTarget("file");
        }
    }

    private String[] parseOssUrl(String ossUrl) {
        try {
            // 格式: https://bucket.oss-cn-shanghai.aliyuncs.com/key
            if (ossUrl.startsWith("https://")) {
                String withoutProtocol = ossUrl.substring(8);
                int slashIndex = withoutProtocol.indexOf('/');
                String hostPart = withoutProtocol.substring(0, slashIndex);
                String key = withoutProtocol.substring(slashIndex + 1);

                // 从 host 提取 bucket 名
                String bucket = hostPart.substring(0, hostPart.indexOf('.'));
                return new String[]{bucket, key};
            }
            return null;
        } catch (Exception e) {
            log.warn("解析 OSS URL 失败: {}", ossUrl);
            return null;
        }
    }
}
