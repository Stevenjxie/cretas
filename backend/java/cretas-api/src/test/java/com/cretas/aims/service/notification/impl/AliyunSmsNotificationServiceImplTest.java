package com.cretas.aims.service.notification.impl;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AliyunSmsNotificationServiceImpl} (Sprint 9 P1.2).
 *
 * <p>Covers: success path (SMS sent), API error (returns false + DB still persisted),
 * backwards compat (placeholder config = no SMS, only DB persisted).
 */
@ExtendWith(MockitoExtension.class)
class AliyunSmsNotificationServiceImplTest {

    private static final String FACTORY_ID = "F006";
    private static final String ROLE_CODE = "WAREHOUSE_MANAGER";
    private static final String VALID_SIGN = "Cretas";
    private static final String VALID_TEMPLATE = "SMS_123456789";

    @Mock
    private DbNotificationServiceImpl dbDelegate;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Client smsClient;

    @Test
    @DisplayName("UT-SMS-01: notifyUser — SMS sent OK + DB persisted")
    void notifyUserSuccess() throws Exception {
        AliyunSmsNotificationServiceImpl svc = new AliyunSmsNotificationServiceImpl(
                dbDelegate, userRepository, smsClient, VALID_SIGN, VALID_TEMPLATE);
        assertTrue(svc.isSmsEnabled(), "smsEnabled should be true when client + sign + template are set");

        User user = newUser(42L, "13812345678", "warehouse_mgr1");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        SendSmsResponseBody body = new SendSmsResponseBody()
                .setCode("OK").setMessage("OK").setBizId("biz-001");
        SendSmsResponse resp = new SendSmsResponse().setBody(body);
        when(smsClient.sendSms(any(SendSmsRequest.class))).thenReturn(resp);

        svc.notifyUser(FACTORY_ID, 42L, "测试标题", "测试内容");

        // DB 持久化必发生
        verify(dbDelegate, times(1)).notifyUser(FACTORY_ID, 42L, "测试标题", "测试内容");
        // SMS 调用
        ArgumentCaptor<SendSmsRequest> reqCaptor = ArgumentCaptor.forClass(SendSmsRequest.class);
        verify(smsClient, times(1)).sendSms(reqCaptor.capture());
        SendSmsRequest sent = reqCaptor.getValue();
        assertEquals("13812345678", sent.getPhoneNumbers());
        assertEquals(VALID_SIGN, sent.getSignName());
        assertEquals(VALID_TEMPLATE, sent.getTemplateCode());
        assertTrue(sent.getTemplateParam().contains("测试标题"));
        assertTrue(sent.getTemplateParam().contains("测试内容"));
    }

    @Test
    @DisplayName("UT-SMS-02: notifyUser — Aliyun API returns non-OK → DB still persisted, no exception")
    void notifyUserApiError() throws Exception {
        AliyunSmsNotificationServiceImpl svc = new AliyunSmsNotificationServiceImpl(
                dbDelegate, userRepository, smsClient, VALID_SIGN, VALID_TEMPLATE);

        User user = newUser(42L, "13812345678", null);
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        SendSmsResponseBody body = new SendSmsResponseBody()
                .setCode("isv.BUSINESS_LIMIT_CONTROL").setMessage("触发短信频率限制");
        SendSmsResponse resp = new SendSmsResponse().setBody(body);
        when(smsClient.sendSms(any(SendSmsRequest.class))).thenReturn(resp);

        // 不应抛异常
        svc.notifyUser(FACTORY_ID, 42L, "Failed Title", "Failed body");

        // DB 持久化仍发生 (graceful degrade)
        verify(dbDelegate, times(1)).notifyUser(FACTORY_ID, 42L, "Failed Title", "Failed body");
        verify(smsClient, times(1)).sendSms(any(SendSmsRequest.class));
        // sendSms 直接返 false (验证 helper method)
        assertFalse(svc.sendSms("13812345678", "x", "y"));
    }

    @Test
    @DisplayName("UT-SMS-03: backwards compat — placeholder sign-name → smsEnabled=false, 仅 DB 持久化")
    void backwardsCompatNoSms() {
        // 配置不全 (sign-name 还是 placeholder)
        AliyunSmsNotificationServiceImpl svc = new AliyunSmsNotificationServiceImpl(
                dbDelegate, userRepository, smsClient, "placeholder", VALID_TEMPLATE);
        assertFalse(svc.isSmsEnabled(),
                "smsEnabled should be false when sign-name is placeholder");

        svc.notifyUser(FACTORY_ID, 42L, "any", "any");

        verify(dbDelegate, times(1)).notifyUser(FACTORY_ID, 42L, "any", "any");
        // SMS API 不应被调用
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("UT-SMS-04: notifyRole — multi-user SMS dispatch + skip users without phone")
    void notifyRoleSomeNoPhone() throws Exception {
        AliyunSmsNotificationServiceImpl svc = new AliyunSmsNotificationServiceImpl(
                dbDelegate, userRepository, smsClient, VALID_SIGN, VALID_TEMPLATE);

        User u1 = newUser(1L, "13800000001", null);
        User u2 = newUser(2L, null, null);      // 无手机
        User u3 = newUser(3L, "", null);        // 空手机
        User u4 = newUser(4L, "13800000004", null);
        when(userRepository.findByFactoryIdAndRoleCode(FACTORY_ID, ROLE_CODE))
                .thenReturn(List.of(u1, u2, u3, u4));

        SendSmsResponseBody body = new SendSmsResponseBody().setCode("OK").setBizId("b");
        when(smsClient.sendSms(any(SendSmsRequest.class)))
                .thenReturn(new SendSmsResponse().setBody(body));

        svc.notifyRole(FACTORY_ID, ROLE_CODE, "T", "B");

        verify(dbDelegate, times(1)).notifyRole(FACTORY_ID, ROLE_CODE, "T", "B");
        // 仅 u1 + u4 收到 SMS (u2/u3 无手机号 skip)
        verify(smsClient, times(2)).sendSms(any(SendSmsRequest.class));
    }

    @Test
    @DisplayName("UT-SMS-05: broadcastFactory — 政策仅 DB 不发 SMS")
    void broadcastNoSms() {
        AliyunSmsNotificationServiceImpl svc = new AliyunSmsNotificationServiceImpl(
                dbDelegate, userRepository, smsClient, VALID_SIGN, VALID_TEMPLATE);

        svc.broadcastFactory(FACTORY_ID, "重要公告", "内容");

        verify(dbDelegate, times(1)).broadcastFactory(FACTORY_ID, "重要公告", "内容");
        try {
            verify(smsClient, never()).sendSms(any(SendSmsRequest.class));
        } catch (Exception e) {
            throw new AssertionError("Verification of smsClient.sendSms 不应抛异常", e);
        }
    }

    private static User newUser(Long id, String phone, String username) {
        User u = new User();
        u.setId(id);
        u.setPhone(phone);
        if (username != null) u.setUsername(username);
        return u;
    }
}
