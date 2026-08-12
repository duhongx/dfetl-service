package com.dfygt.dfetl.server.controller;

import com.dfygt.dfetl.server.common.ApiResponse;
import com.dfygt.dfetl.server.dto.LoginRequest;
import com.dfygt.dfetl.server.dto.LoginResponse;
import com.dfygt.dfetl.server.entity.AppUser;
import com.dfygt.dfetl.server.repository.AppUserRepository;
import com.dfygt.dfetl.server.security.JwtUtil;
import com.dfygt.dfetl.server.security.TokenBlacklistService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenBlacklistService blacklistService;
    private final UserDetailsService userDetailsService;

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.username(), req.password())
        );
        AppUser user = appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new NoSuchElementException("用户不存在"));
        String token        = jwtUtil.generateToken(auth.getName());
        String refreshToken = jwtUtil.generateRefreshToken(auth.getName(), user.getRefreshTokenVersion());
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN")
                .replace("ROLE_", "");
        return ApiResponse.ok(new LoginResponse(token, refreshToken, auth.getName(), role, 28800));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(Authentication auth) {
        String role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN")
                .replace("ROLE_", "");
        return ApiResponse.ok(new MeResponse(auth.getName(), role));
    }

    /**
     * 登出：将当前 Access Token 加入黑名单，并提升用户 refresh token 版本，
     * 使所有已签发 refresh token 立即失效。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, Authentication auth) {
        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
            try {
                blacklistService.add(token, jwtUtil.extractExpiration(token));
            } catch (Exception ignored) {
                // 已过期 token 直接忽略
            }
        }
        String username = auth != null ? auth.getName() : null;
        if ((username == null || username.isBlank()) && token != null) {
            try {
                username = jwtUtil.extractUsername(token);
            } catch (Exception ignored) {
                // token 无效时无法进一步撤销 refresh 版本
            }
        }
        if (username != null && !username.isBlank()) {
            appUserRepository.findByUsername(username).ifPresent(user -> {
                user.setRefreshTokenVersion(user.getRefreshTokenVersion() + 1);
                appUserRepository.save(user);
            });
        }
        return ApiResponse.ok();
    }

    record RefreshRequest(String refreshToken) {}

    /** Spec 051：用 Refresh Token 换取新的 Access Token。 */
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody RefreshRequest req) {
        String rt = req.refreshToken();
        if (rt == null || rt.isBlank()) {
            throw new IllegalArgumentException("refreshToken 不能为空");
        }
        if (blacklistService.isBlacklisted(rt)) {
            throw new IllegalArgumentException("refreshToken 已失效");
        }
        String username;
        try {
            username = jwtUtil.extractUsername(rt);
            if (!jwtUtil.isRefreshToken(rt)) {
                throw new IllegalArgumentException("不是有效的 refreshToken");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("refreshToken 无效或已过期");
        }
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("refreshToken 已失效"));
        int tokenVersion = jwtUtil.extractRefreshTokenVersion(rt);
        if (tokenVersion != user.getRefreshTokenVersion()) {
            throw new IllegalArgumentException("refreshToken 已失效");
        }
        // 旧 refresh token 加黑名单（rotation）
        blacklistService.add(rt, jwtUtil.extractExpiration(rt));
        String newToken        = jwtUtil.generateToken(username);
        String newRefreshToken = jwtUtil.generateRefreshToken(username, user.getRefreshTokenVersion());
        var ud = userDetailsService.loadUserByUsername(username);
        String role = ud.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .findFirst()
                .orElse("ROLE_ADMIN")
                .replace("ROLE_", "");
        return ApiResponse.ok(new LoginResponse(newToken, newRefreshToken, username, role, 28800));
    }

    record ChangePasswordRequest(String oldPassword, String newPassword) {}

    record MeResponse(String username, String role) {}

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(
            @RequestBody ChangePasswordRequest req,
            Authentication auth) {
        if (req.newPassword() == null || req.newPassword().length() < 8) {
            throw new IllegalArgumentException("新密码长度不得少于 8 位");
        }
        AppUser user = appUserRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new NoSuchElementException("用户不存在"));
        if (!passwordEncoder.matches(req.oldPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        appUserRepository.save(user);
        return ApiResponse.ok();
    }
}
