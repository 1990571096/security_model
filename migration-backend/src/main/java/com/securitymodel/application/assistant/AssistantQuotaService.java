package com.securitymodel.application.assistant;

import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.assistant.AssistantUsageDaily;
import com.securitymodel.infrastructure.persistence.mapper.AssistantUsageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily assistant request quota. Increment is conditional on {@code count < limit}, so
 * concurrent requests cannot exceed the configured limit without a global lock.
 */
@Service
public class AssistantQuotaService {

    private final AssistantUsageMapper usageMapper;
    private final CurrentUserService currentUserService;
    private final int dailyLimit;

    public AssistantQuotaService(
            AssistantUsageMapper usageMapper,
            CurrentUserService currentUserService,
            @Value("${security-model.assistant.daily-request-limit:50}") int dailyLimit) {
        this.usageMapper = usageMapper;
        this.currentUserService = currentUserService;
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    public void acquire() {
        Long userId = currentUserService.requireStableIdentity().userId();
        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        if (usageMapper.incrementIfBelow(userId, today, dailyLimit, now) == 1) {
            return;
        }
        try {
            usageMapper.insertFirst(userId, today, now);
            return;
        } catch (DuplicateKeyException duplicate) {
            // Another request created today's row first; retry the conditional increment.
        }
        if (usageMapper.incrementIfBelow(userId, today, dailyLimit, now) != 1) {
            throw new AssistantException("ASSISTANT_DAILY_QUOTA_EXCEEDED", HttpStatus.TOO_MANY_REQUESTS,
                    "今日助手请求次数已达上限 " + dailyLimit + " 次，核心任务接口不受影响");
        }
    }

    public int remaining() {
        Long userId = currentUserService.requireStableIdentity().userId();
        AssistantUsageDaily usage = usageMapper.find(userId, LocalDate.now());
        return Math.max(0, dailyLimit - (usage == null ? 0 : usage.getRequestCount()));
    }
}
