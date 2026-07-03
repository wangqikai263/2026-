package com.shixiaoyuan.backend.controller.api;

import com.shixiaoyuan.backend.auth.AuthSessionUtil;
import com.shixiaoyuan.backend.dto.request.AuthRequest;
import com.shixiaoyuan.backend.entity.UserAccountEntity;
import com.shixiaoyuan.backend.service.auth.AuthService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "账号接口", description = "登录、注册、登出与当前会话查询")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody AuthRequest req, HttpSession session) {
        String username = normalizeUsername(req == null ? null : req.getUsername());
        String password = req == null ? null : req.getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_input", "用户名和密码不能为空。");
        }
        if (password != null && password.length() < 4) {
            return error(HttpStatus.BAD_REQUEST, "invalid_password", "密码长度至少 4 位。");
        }

        try {
            UserAccountEntity user = authService.register(username, password);
            AuthSessionUtil.login(session, user.getId(), user.getUsername());
            return ResponseEntity.ok(success(user));
        } catch (IllegalStateException e) {
            return error(HttpStatus.CONFLICT, "username_exists", "用户名已存在。");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody AuthRequest req, HttpSession session) {
        String username = normalizeUsername(req == null ? null : req.getUsername());
        String password = req == null ? null : req.getPassword();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_input", "用户名和密码不能为空。");
        }

        UserAccountEntity user = authService.findByUsername(username).orElse(null);
        if (user == null) {
            return error(HttpStatus.NOT_FOUND, "user_not_found", "用户不存在。");
        }
        if (!authService.verifyPassword(user, password)) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_credentials", "用户名或密码错误。");
        }

        AuthSessionUtil.login(session, user.getId(), user.getUsername());
        return ResponseEntity.ok(success(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        Object userId = session.getAttribute(AuthSessionUtil.KEY_USER_ID);
        Object username = session.getAttribute(AuthSessionUtil.KEY_USERNAME);
        if (userId == null || username == null) {
            return error(HttpStatus.UNAUTHORIZED, "not_logged_in", "未登录。");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("userId", userId);
        body.put("username", username.toString());
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> success(UserAccountEntity user) {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", true);
        body.put("userId", user.getId());
        body.put("username", user.getUsername());
        return body;
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("ok", false);
        body.put("error", error);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String normalizeUsername(String raw) {
        return raw == null ? null : raw.trim();
    }
}

