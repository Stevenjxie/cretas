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
            sendCanvasError(response, "Request path is missing factoryId; cannot validate module state.");
            return false;
        }

        if (!configService.isModuleEnabled(factoryId, moduleCode)) {
            log.info("Module {} is disabled for factory {}; blocking {}", moduleCode, factoryId, path);
            sendModuleDisabled(response, moduleCode);
            return false;
        }

        User user = getCurrentUser(request);
        if (user == null) {
            sendUnauthorized(response, "User is not authenticated; cannot validate module permission.");
            return false;
        }
        if (!factoryId.equals(user.getFactoryId())) {
            sendUserModuleDenied(
                    response,
                    user,
                    moduleCode,
                    "Account " + userLabel(user) + " does not belong to factory " + factoryId,
                    "read");
            return false;
        }

        String requiredLevel = isWriteMethod(request) ? "write" : "read";
        boolean allowed = isWriteMethod(request)
                ? userModuleAccessService.canWriteModule(
                        factoryId,
                        String.valueOf(user.getId()),
                        user.getRoleCode(),
                        moduleCode)
                : userModuleAccessService.canAccessModule(user, moduleCode);
        if (!allowed) {
            sendUserModuleDenied(
                    response,
                    user,
                    moduleCode,
                    "Account " + userLabel(user) + " lacks " + requiredLevel
                            + " permission for module " + moduleCode,
                    requiredLevel);
            return false;
        }
        return true;
    }

    private boolean isWriteMethod(HttpServletRequest request) {
        String method = request.getMethod();
        return "POST".equals(method)
                || "PUT".equals(method)
                || "PATCH".equals(method)
                || "DELETE".equals(method);
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
        body.put("message", "Module " + moduleCode + " is disabled.");
        body.put("data", null);
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("actionHint", "Enable module " + moduleCode + " in Canvas configuration first.");
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
        body.put("actionHint", "Login again and retry.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private void sendUserModuleDenied(
            HttpServletResponse response,
            User user,
            String moduleCode,
            String message,
            String requiredLevel) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("success", false);
        body.put("message", message);
        body.put("severity", "error");
        body.put("actionHint", "Ask an administrator to grant module permission.");
        body.put("hintTarget", "module_permissions");
        body.put("moduleCode", moduleCode);
        body.put("requiredLevel", requiredLevel);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("userId", user.getId());
        meta.put("username", user.getUsername());
        meta.put("factoryId", user.getFactoryId());
        meta.put("module", moduleCode);
        meta.put("moduleCode", moduleCode);
        meta.put("requiredLevel", requiredLevel);
        body.put("meta", meta);
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    private String userLabel(User user) {
        String username = user.getUsername() == null ? "unknown" : user.getUsername();
        return username + "(" + user.getId() + ")";
    }
}
