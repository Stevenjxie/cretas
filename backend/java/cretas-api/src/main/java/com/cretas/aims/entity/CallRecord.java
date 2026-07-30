package com.cretas.aims.entity;

import com.cretas.aims.entity.enums.CallType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;

/**
 * 通话记录 — Sprint 6 W2-C-2, 2026-05-19.
 *
 * <p>HJ Round 11 §F.2 finding — Customer 详情 "通话记录" tab. F006 卤制品销售场景:
 * 电话沟通后上传录音 + Whisper 转写关键信息. 跟 {@link WechatRecord} 互补:
 * Wechat = 文字会话流水, Call = 语音 + 转写文本.
 *
 * <p>OSS 录音存储: {@code audioOssUrl} 字段保存 OSS URL (由 {@link com.cretas.aims.service.OssService#uploadAudioStream}
 * 上传到 audio bucket). 转写文本由 Python {@code /api/efficiency/whisper-transcribe} 异步填充
 * 到 {@code transcriptText} 字段 (initial null, REST 不阻塞).
 *
 * <p>幂等性 (per {@code .claude/rules/fool-proof-design.md} Rule 4): 5 分钟内同
 * (factoryId, customerId, callTime ±5min) 二次创建 → 409 + existingId.
 *
 * <p>软删除: {@code deleted_at IS NULL} via @Where + @SQLDelete.
 *
 * @author Cretas Team — Sprint 6 W2-C-2
 * @since 2026-05-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "call_records",
        indexes = {
                @Index(name = "idx_call_record_customer", columnList = "customer_id"),
                @Index(name = "idx_call_record_factory", columnList = "factory_id"),
                @Index(name = "idx_call_record_call_time", columnList = "call_time")
        })
@SQLDelete(sql = "UPDATE call_records SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
public class CallRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tenant scope — 防租户串数据 (RLS). */
    @Column(name = "factory_id", nullable = false, length = 191)
    private String factoryId;

    /** Customer this call is associated with — FK to customers.id (string UUID). */
    @Column(name = "customer_id", nullable = false, length = 191)
    private String customerId;

    /** 通话时间 — 业务员手工填或默认上传时刻 (NOT 上传时间). */
    @Column(name = "call_time", nullable = false)
    private LocalDateTime callTime;

    /** 通话时长 (秒). 漏接 callType=MISSED 时通常 0; 可选字段. */
    @Column(name = "duration_sec")
    private Integer durationSec;

    /**
     * 通话类型 enum (R3 dropdown): INCOMING / OUTGOING / MISSED.
     * Controller 层也做白名单校验, entity 层用 EnumType.STRING 持久化便于 SQL grep.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 16)
    private CallType callType;

    /** 通话对端 (客户联系人姓名). 可选. */
    @Column(name = "contact_name", length = 100)
    private String contactName;

    /** 通话对端电话. 可选. */
    @Column(name = "contact_phone", length = 32)
    private String contactPhone;

    /**
     * 录音文件 OSS URL — by {@link com.cretas.aims.service.OssService#uploadAudioStream}.
     * Nullable: 允许"先创建记录后补传录音" (callType=MISSED 通常无 URL).
     */
    @Column(name = "audio_oss_url", length = 1000)
    private String audioOssUrl;

    /** 录音原文件名 (展示用 — OSS URL 含日期前缀难读). 可选. */
    @Column(name = "audio_filename", length = 255)
    private String audioFilename;

    /** 录音文件大小 (bytes) — 防呆 R1 显示用. 可选. */
    @Column(name = "audio_size_bytes")
    private Long audioSizeBytes;

    /** 录音 MIME type — audio/mpeg / audio/wav / audio/ogg / audio/webm 等. 可选. */
    @Column(name = "audio_mime_type", length = 64)
    private String audioMimeType;

    /**
     * Whisper 转写结果 — Python {@code /api/efficiency/whisper-transcribe} 异步填.
     * NULL = 转写未完成 / 失败 / 没有录音.
     */
    @Column(name = "transcript_text", columnDefinition = "TEXT")
    private String transcriptText;

    /**
     * 转写状态:
     * <ul>
     *   <li>{@code PENDING} = 上传后等待转写</li>
     *   <li>{@code SUCCESS} = 转写完成</li>
     *   <li>{@code FAILED} = 转写失败 (Python down / 格式不支持)</li>
     * </ul>
     * NULL = 没触发转写 (e.g. callType=MISSED 无音频).
     */
    @Column(name = "transcript_status", length = 16)
    private String transcriptStatus;

    /** 备注 / 通话摘要 (业务员手工填). */
    @Column(name = "remark", columnDefinition = "TEXT")
    private String remark;

    /** 创建人 ID (业务员). */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 创建人显示名 (冗余, 避免列表渲染 join users). */
    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    /** Transcript status constants — DB 列直接持 String, 便于 forward-compat. */
    public static final String TRANSCRIPT_PENDING = "PENDING";
    public static final String TRANSCRIPT_SUCCESS = "SUCCESS";
    public static final String TRANSCRIPT_FAILED = "FAILED";
}
