package com.securitymodel.interfaces.web;

import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.security.JwtTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Captcha> CAPTCHA_STORE = new ConcurrentHashMap<>();
    private final SysUserMapper userMapper;
    private final JwtTokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(SysUserMapper userMapper, JwtTokenService tokenService) {
        this.userMapper = userMapper;
        this.tokenService = tokenService;
    }

    @GetMapping("/code")
    public Map<String, String> code() {
        String uuid = "captcha_code:" + UUID.randomUUID().toString().replace("-", "");
        String value = String.format("%04d", RANDOM.nextInt(10_000));
        CAPTCHA_STORE.put(uuid, new Captcha(value, Instant.now().plusSeconds(300)));
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="120" height="40">
                  <rect width="120" height="40" fill="#f3f6f8"/>
                  <text x="12" y="28" font-family="Arial" font-size="22" fill="#17324d">%s</text>
                </svg>
                """.formatted(value);
        String image = "data:image/svg+xml;base64," + Base64.getEncoder()
                .encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return Map.of("uuid", uuid, "img", image);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        Captcha captcha = CAPTCHA_STORE.remove(request.uuid());
        if (captcha == null || captcha.expiresAt().isBefore(Instant.now())
                || !captcha.value().equalsIgnoreCase(request.code())) {
            throw new IllegalArgumentException("验证码错误或已过期");
        }
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
                "nickName", user.getNickName(), "admin", user.getAdmin() != null && user.getAdmin() == 1);
    }

    public record LoginRequest(String username, String password, String code, String uuid) {
    }

    private record Captcha(String value, Instant expiresAt) {
    }
}
