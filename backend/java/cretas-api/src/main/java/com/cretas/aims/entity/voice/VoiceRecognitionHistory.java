package com.cretas.aims.entity.voice;

import com.cretas.aims.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import jakarta.persistence.*;
import org.hibernate.annotations.Where;

/**
 * 语音识别历史记录实体
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Entity
@Table(name = "voice_recognition_history", indexes = {
    @Index(name = "idx_vrh_factory_id", columnList = "factory_id"),
    @Index(name = "idx_vrh_user_id", columnList = "user_id"),
    @Index(name = "idx_vrh_created_at", columnList = "created_at")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@Where(clause = "deleted_at IS NULL")
public class VoiceRecognitionHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 工厂ID
     */
    @Column(name = "factory_id", length = 50)
    private String factoryId;

    /**
     * 用户ID
     */
    @Column(name = "user_id")
    private Long userId;

    /**
     * 用户名
     */
    @Column(name = "username", length = 100)
    private String username;

    /**
     * 讯飞会话ID
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    /**
     * 识别结果文本
     */
    @Column(name = "recognized_text", columnDefinition = "TEXT")
    private String recognizedText;

    /**
     * 识别状态码: 0-成功, 其他-失败
     */
    @Column(name = "status_code")
    private Integer statusCode;

    /**
     * 状态消息
     */
    @Column(name = "status_message", length = 500)
    private String statusMessage;

    /**
     * 音频格式: raw, mp3, speex, speex-wb
     */
    @Column(name = "audio_format", length = 20)
    private String audioFormat;

    /**
     * 音频编码: raw, lame, speex, speex-wb
     */
    @Column(name = "audio_encoding", length = 20)
    private String audioEncoding;

    /**
     * 采样率: 8000, 16000
     */
    @Column(name = "sample_rate")
    private Integer sampleRate;

    /**
     * 语言: zh_cn, en_us
     */
    @Column(name = "language", length = 10)
    private String language;

    /**
     * 音频时长(毫秒)
     */
    @Column(name = "audio_duration_ms")
    private Integer audioDurationMs;

    /**
     * 音频文件大小(字节)
     */
    @Column(name = "audio_size_bytes")
    private Long audioSizeBytes;

    /**
     * OSS存储路径(可选，需要审计时保存)
     */
    @Column(name = "audio_oss_path", length = 500)
    private String audioOssPath;

    /**
     * 识别耗时(毫秒)
     */
    @Column(name = "recognition_duration_ms")
    private Integer recognitionDurationMs;

    /**
     * 业务场景: FORM_FILL-表单填写, BATCH_PROCESS-批量处理, GENERAL-通用
     */
    @Column(name = "business_scene", length = 50)
    private String businessScene;

    /**
     * 关联的业务ID(如: 加工批次ID)
     */
    @Column(name = "related_business_id", length = 100)
    private String relatedBusinessId;

    /**
     * 客户端IP
     */
    @Column(name = "client_ip", length = 50)
    private String clientIp;

    /**
     * 设备信息
     */
    @Column(name = "device_info", length = 200)
    private String deviceInfo;
}
