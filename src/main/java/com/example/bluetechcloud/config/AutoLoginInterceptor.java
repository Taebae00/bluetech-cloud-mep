package com.example.bluetechcloud.config;

import com.example.bluetechcloud.entity.UserEntity;
import com.example.bluetechcloud.model.UserDTO;
import com.example.bluetechcloud.repository.UserRepo;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class AutoLoginInterceptor implements HandlerInterceptor {

    private final UserRepo userRepo;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {

        HttpSession session = request.getSession();
        Object loginUser = session.getAttribute("user");

        if (loginUser != null) {
            return true;
        }

        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return true;
        }

        String token = null;

        for (Cookie cookie : cookies) {
            if ("remember-me".equals(cookie.getName())) {
                token = cookie.getValue();
                break;
            }
        }

        if (token == null || token.isBlank()) {
            return true;
        }

        UserEntity user = userRepo.findByRememberToken(token);

        if (user == null) {
            return true;
        }

        if (user.getRememberTokenExpiry() == null ||
                user.getRememberTokenExpiry().isBefore(LocalDateTime.now())) {
            return true;
        }

        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setPassword(user.getPassword());
        dto.setName(user.getName());
        dto.setRole(user.getRole());
        dto.setCreated_at(user.getCreatedAt());

        session.setAttribute("user", dto);

        return true;
    }
}