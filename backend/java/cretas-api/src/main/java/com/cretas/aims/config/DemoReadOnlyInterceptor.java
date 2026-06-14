package com.cretas.aims.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 演示租户只读锁.
 *
 * 对配置的 demo 工厂 (cretas.demo.factory-ids) 拦截所有写操作 (POST/PUT/PATCH/DELETE),
 * 但放行查询 / 分析 / AI 类 POST (本系统大量"读"是 POST). 用于路演公开扫码演示, 防数据被改坏.
 * 注册在 JwtAuthInterceptor (order 1) 之后, 此时 request 已带 factoryId 属性.
 *
 * @author Cretas Team
 * @since 2026-06-14
 */
@Slf4j
@Component
public class DemoReadOnlyInterceptor implements HandlerInterceptor {

    @Value("${cretas.demo.enabled:false}")
    private boolean demoEnabled;

    @Value("${cretas.demo.factory-ids:DEMO_REST,DEMO_FACTORY}")
    private String demoFactoryIdsCsv;

    // POST 路径含以下任一片段 = 读取 / 分析 / AI, 放行 (否则演示自身的查询会被误拦).
    private static final List<String> READ_POST_ALLOW = List.of(
            "/auth/",        // demo-login / refresh / logout / me
            "/smart-bi/",    // BI 查询 / 经营驾驶舱 / 分析
            "/ai-intents/",  // AI 意图 chat
            "/ai/",          // AI
            "/chat",         // chat
            "/analysis",     // 分析
            "/reports/",     // 报表查询
            "/dashboard",    // 仪表盘查询
            "/query",        // 查询
            "/statistics",   // 统计
            "/export",       // 导出
            "/preview",      // 预览
            "/search"        // 搜索
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!demoEnabled) {
            return true;
        }
        Object fid = request.getAttribute("factoryId");
        if (fid == null) {
            return true; // 公开端点 / 未带工厂 -> 不管
        }
        Set<String> demoIds = new HashSet<>(Arrays.asList(demoFactoryIdsCsv.split(",")));
        if (!demoIds.contains(fid.toString().trim())) {
            return true; // 非 demo 工厂 -> 正常 (不误伤真实租户)
        }

        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            return true;
        }

        // 写方法: PUT/PATCH/DELETE 一律拦; POST 放行读取/分析类.
        String uri = request.getRequestURI();
        if ("POST".equals(method)) {
            for (String allow : READ_POST_ALLOW) {
                if (uri.contains(allow)) {
                    return true;
                }
            }
        }

        log.info("演示只读锁拦截写操作: {} {} (factoryId={})", method, uri, fid);
        sendReadOnly(response);
        return false;
    }

    private void sendReadOnly(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", 403);
        body.put("message", "演示模式为只读，欢迎浏览全部数据 🙂");
        body.put("severity", "info");
        body.put("actionHint", "这是公开演示账号，仅供查看，无法修改数据");
        body.put("timestamp", java.time.LocalDateTime.now().toString());
        response.getWriter().write(new ObjectMapper().writeValueAsString(body));
    }
}
