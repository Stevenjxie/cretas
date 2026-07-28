package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「无工厂前缀但必须登录」路径判定的回归。
 *
 * <p>2026-07-29 洞：{@code JwtAuthInterceptor} 的 401 检查只在能从 URL 解析出
 * factoryId 时才跑，而 {@code ai} / {@code upload} 这些顶层前缀被排除在 factoryId
 * 解析之外，于是它们既不校验租户也不校验登录。prod 实测匿名
 * {@code POST /api/mobile/ai/chat} 能拿到真实 LLM 回复。
 *
 * <p>这组断言盯两头：该关的关上，**匿名流程一个都不能误伤** ——
 * 登录/激活/健康检查/版本检查一旦被关，App 会直接进不去。
 */
@DisplayName("JwtAuthInterceptor — 无工厂前缀端点的登录校验")
class JwtAuthInterceptorGlobalAuthTest {

    private final JwtAuthInterceptor interceptor = new JwtAuthInterceptor();

    // ───── 该关上的 ─────

    @Test
    @DisplayName("通用 LLM 通道必须登录 —— 否则是对公网开放的计费模型出口")
    void aiChatRequiresAuth() {
        assertTrue(interceptor.requiresGlobalAuth("/api/mobile/ai/chat"));
        assertTrue(interceptor.requiresGlobalAuth("/api/mobile/ai/chat/stream"));
    }

    @Test
    @DisplayName("文件上传必须登录")
    void uploadRequiresAuth() {
        assertTrue(interceptor.requiresGlobalAuth("/api/mobile/upload"));
    }

    // ───── 绝对不能误伤的匿名流程 ─────

    @Test
    @DisplayName("AI 健康检查仍是公开端点 —— 被 isPublicEndpoint 放行")
    void aiHealthStaysPublic() {
        // 命中前缀, 但公开白名单优先 (拦截器里是 requiresGlobalAuth && !isPublicEndpoint)
        assertTrue(interceptor.requiresGlobalAuth("/api/mobile/ai/health"));
        assertTrue(interceptor.isPublicEndpoint("/api/mobile/ai/health"));
    }

    @Test
    @DisplayName("登录/激活/健康/版本检查不受影响 —— 关了 App 就进不去")
    void loginAndBootstrapPathsUntouched() {
        for (String uri : new String[]{
                "/api/mobile/auth/login",
                "/api/mobile/auth/unified-login",
                "/api/mobile/auth/demo-login",
                "/api/mobile/auth/refresh",
                "/api/mobile/activation/activate",
                "/api/mobile/health",
                "/api/mobile/version/check",
                "/api/mobile/voice/recognize",
        }) {
            assertFalse(interceptor.requiresGlobalAuth(uri), uri + " 不该被这条规则拦");
        }
    }

    @Test
    @DisplayName("工厂域端点不走这条规则 —— 它们由下面的 factoryId 分支管")
    void factoryScopedPathsNotAffected() {
        assertFalse(interceptor.requiresGlobalAuth("/api/mobile/F001/form-assistant/parse"));
        assertFalse(interceptor.requiresGlobalAuth("/api/mobile/F001/ai-intents/execute"));
    }

    @Test
    @DisplayName("本轮没动的低敏读端点仍在洞里 —— 留给「默认要求登录」的根治轮")
    void knownRemainingGapsAreExplicit() {
        // 故意断言为 false: 它们目前匿名可达 (workflow schema / 系统信息 / 秤协议主数据)。
        // 根治要把拦截器翻成「默认要求登录 + 白名单」, 需先证明白名单对启动期流程完整。
        assertFalse(interceptor.requiresGlobalAuth("/api/mobile/workflow/node-schemas"));
        assertFalse(interceptor.requiresGlobalAuth("/api/mobile/system/info"));
        assertFalse(interceptor.requiresGlobalAuth("/api/mobile/scale-protocols/brands"));
    }
}
