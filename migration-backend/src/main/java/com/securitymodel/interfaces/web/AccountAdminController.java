package com.securitymodel.interfaces.web;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.application.audit.OperationAuditService;
import com.securitymodel.application.resource.ResourceConflictException;
import com.securitymodel.application.resource.ResourceNotFoundException;
import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/admin/accounts")
public class AccountAdminController {
    private final CurrentUserService currentUser;
    private final SysUserMapper users;
    private final OperationAuditService audit;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AccountAdminController(CurrentUserService currentUser, SysUserMapper users, OperationAuditService audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.audit = audit;
    }

    public record AccountView(String userId, String username, String nickName, boolean enabled, boolean admin) {
        static AccountView of(SysUser user) {
            return new AccountView(user.getUserId().toString(), user.getUsername(), user.getNickName(),
                    Boolean.TRUE.equals(user.getEnabled()), Integer.valueOf(1).equals(user.getAdmin()));
        }
    }

    public record CreateAccount(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{3,64}") String username,
                                @NotBlank @Size(max = 100) String nickName,
                                @NotBlank @Size(min = 8, max = 72) String password) {}

    public record SetEnabled(@NotNull Boolean enabled) {}

    @GetMapping
    public List<AccountView> list() {
        currentUser.requireAdmin();
        return users.listAccountStates().stream().map(AccountView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AccountView create(@Valid @RequestBody CreateAccount input) {
        currentUser.requireAdmin();
        if (input.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码不能超过 72 个 UTF-8 字节");
        }
        SysUser user = new SysUser();
        user.setUsername(input.username());
        user.setNickName(input.nickName().trim());
        user.setPassword(passwordEncoder.encode(input.password()));
        user.setEnabled(true);
        user.setAdmin(0);
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ResourceConflictException("用户名已存在", exception);
        }
        audit.recordResource("user_account", user.getUserId(), "CREATE", "enabled", null);
        return AccountView.of(user);
    }

    @PutMapping("/{userId}/enabled")
    @Transactional
    public AccountView setEnabled(@PathVariable Long userId, @Valid @RequestBody SetEnabled input) {
        currentUser.requireAdmin();
        SysUser user = users.findStateById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("账号不存在");
        }
        if (Integer.valueOf(1).equals(user.getAdmin())) {
            throw new IllegalArgumentException("此入口只管理实验员账号");
        }
        if (users.setExperimenterEnabled(userId, input.enabled(), currentUser.username()) != 1) {
            throw new IllegalArgumentException("账号状态变更失败，请刷新后重试");
        }
        user.setEnabled(input.enabled());
        audit.recordResource("user_account", userId, input.enabled() ? "ENABLE" : "DISABLE",
                input.enabled() ? "enabled" : "disabled", null);
        return AccountView.of(user);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable Long userId) {
        currentUser.requireAdmin();
        SysUser user = users.findStateById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("账号不存在");
        }
        if (Integer.valueOf(1).equals(user.getAdmin())) {
            throw new IllegalArgumentException("管理员账号不可删除");
        }
        if (users.deleteExperimenter(userId, currentUser.username()) != 1) {
            throw new ResourceConflictException("账号状态已变更，请刷新后重试", null);
        }
        audit.recordResource("user_account", userId, "DELETE", "deleted", null);
    }
}
