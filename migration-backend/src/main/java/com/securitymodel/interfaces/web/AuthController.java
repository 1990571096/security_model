package com.securitymodel.interfaces.web;

import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.security.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final SysUserMapper userMapper;
    private final JwtTokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(SysUserMapper userMapper, JwtTokenService tokenService) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request) {
        SysUser user = userMapper.findByUsername(request.username());
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return Map.of(
                "token", "Bearer " + tokenService.issue(user.getUserId(), user.getUsername(),
                        user.getAdmin() != null && user.getAdmin() == 1),
                "user", userView(user));
    }

    @GetMapping("/info")
    public Map<String, Object> info(java.security.Principal principal) {
        if (principal == null) {
            throw new IllegalArgumentException("登录状态已失效");
        }
        SysUser user = userMapper.findByUsername(principal.getName());
        if (user == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return userView(user);
    }

    @DeleteMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private Map<String, Object> userView(SysUser user) {
        return Map.of("userId", user.getUserId(), "username", user.getUsername(),
                "nickName", user.getNickName() == null ? user.getUsername() : user.getNickName(),
                "admin", user.getAdmin() != null && user.getAdmin() == 1);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}
