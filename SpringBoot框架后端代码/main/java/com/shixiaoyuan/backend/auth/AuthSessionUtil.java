package com.shixiaoyuan.backend.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class AuthSessionUtil {

    private AuthSessionUtil() {}

    public static final String KEY_USER_ID = "AUTH_USER_ID";
    public static final String KEY_USERNAME = "AUTH_USERNAME";

    public static void login(HttpSession session, Long userId, String username) {
        session.setAttribute(KEY_USER_ID, userId);
        session.setAttribute(KEY_USERNAME, username);
    }

    public static Long requireUserId(HttpSession session) {
        Object value = session.getAttribute(KEY_USER_ID);
        if (value instanceof Long id) {
            return id;
        }
        if (value instanceof Integer id) {
            return id.longValue();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "NOT_LOGGED_IN");
    }

    public static String currentUsername(HttpSession session) {
        Object value = session.getAttribute(KEY_USERNAME);
        return value == null ? null : value.toString();
    }
}

