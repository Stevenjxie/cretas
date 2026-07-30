package com.cretas.aims.config;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class ModuleEnabledInterceptor implements HandlerInterceptor {

    private final FactoryConfigService configService;
    private final UserModuleAccessService userModuleAccessService;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public ModuleEnabledInterceptor(
            FactoryConfigService configService,
            UserModuleAccessService userModuleAccessService,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.configService = configService;
        this.userModuleAccessService = userModuleAccessService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod hm)) {
            return true;
        }

        RequireModule annotation = hm.getMethodAnnotation(RequireModule.class);
        if (annotation == null) {
            annotation = hm.getBeanType().getAnnotation(RequireModule.class);
        }
        if (annotation == null) {
            return true;
        }

        String moduleCode = annotation.value();
        String path = request.getRequestURI();
        String factoryId = extractFactoryId(path);
        if (factoryId == null) {
            log.warn("@RequireModule({}) on {} but no factoryId extractable; blocking", moduleCode, path);
            sendCanvasError(response, "请求路径缺少 factoryId，无法校验模块状态");
            return false;
        }

        if (!configService.isModuleEnabled(factoryId, moduleCode)) {
            log.info("Module {} is disabled for factory {}; blocking {}", moduleCode, factoryId, path);
            sendModuleDisabled(response, moduleCode);
            return false;
        }

        User user = getCurrentUser(request);
        if (user == null) {
            sendUnauthorized(response, "用户未登录，无法校验账号模块访问权限");
            return false;
        }
        if (!factoryId.equals(user.getFactoryId())) {
            sendUserModuleDenied(response, user, moduleCode,
                    "账号 " + userLabel(user) + " 不属于工厂 " + factoryId + "，无法访问模块 " + moduleCode);
            return false;
        }
        if (!userModuleAccessService.canAccessModule(user, moduleCode)) {
            sendUserModuleDenied(response, user, moduleCode,
                    "账号 " + userLabel(user) + " 缺少模块 " + moduleCode + " 的访问权限");
            return false;
        }
        return true;
    }

    private String extractFactoryId(String path) {
        if (path == null) return null;
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("mobile".equals(parts[i]) && i + 1 < parts.length) {
                return parts[i + 1];
            }
        }
        return null;
    }

    private User getCurrentUser(HttpServletRequest request) {
        Object userIdObj = request.getAttribute("userId");
        if (userIdObj == null) {
            return null;
        }
        Long userId;
        try {
            userId = Long.parseLong(String.valueOf(userIdObj));
        } catch (NumberFormatException e) {
            return null;
        }
        Optional<User> user = userRepository.findById(userId);
        return user.orElse(null);
    }

    private void sendModuleDisabled(HttpServletResponse response, String moduleCode) throws Exception {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 400);
        body.put("success", false);
        body.put("message", "模块 " + moduleCode + " 未启用");
        body.put("data", null);
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("actionHint", "请先在 Canvas 配置中启用 " + moduleCode + " 模块");
        body.put("severity", "warning");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private void sendCanvasError(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 400);
        body.put("success", false);
        body.put("message", message);
        body.put("data", null);
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("severity", "error");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("success", false);
        body.put("message", message);
        body.put("severity", "error");
        body.put("actionHint", "请重新登录后再访问该模块");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private void sendUserModuleDenied(
            HttpServletResponse response,
            User user,
            String moduleCode,
            String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("success", false);
        body.put("message", message + "。请联系管理员开通后重试。");
        body.put("severity", "error");
        body.put("actionHint", "请联系管理员在 Canvas -> 账号模块权限 中为账号 "
                + userLabel(user) + " 开通 " + moduleCode + " 模块。");
        body.put("hintTarget", "账号模块权限");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userId", user.getId());
        meta.put("username", user.getUsername());
        meta.put("factoryId", user.getFactoryId());
        meta.put("module", moduleCode);
        body.put("meta", meta);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private String userLabel(User user) {
        String username = user.getUsername() == null ? "unknown" : user.getUsername();
        return username + "(" + user.getId() + ")";
    }
}
