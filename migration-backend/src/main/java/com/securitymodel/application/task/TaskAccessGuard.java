package com.securitymodel.application.task;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Single authorization point for experiment data (tasks, reports, logs, artifacts, exports, audits).
 *
 * <p>Rules (see docs/sdd/05-decisions.md ADR-013):</p>
 * <ul>
 *   <li>Owner is {@code owner_user_id}, never {@code create_by}.</li>
 *   <li>Writes require the owner. No role may write another user's task.</li>
 *   <li>Reads require the owner; an admin may read other users' tasks only when
 *       {@code security-model.ownership.admin-cross-user-read} is enabled.</li>
 *   <li>Demo rows ({@code data_origin='demo'}) are never readable by non-admins.</li>
 *   <li>Rows without an owner (legacy {@code unattributed}) are readable only by an admin
 *       with cross-user read enabled, and writable by nobody.</li>
 * </ul>
 */
@Component
public class TaskAccessGuard {

    private static final String DEMO_ORIGIN = "demo";
    private final PreDefenseTaskMapper taskMapper;
    private final CurrentUserService currentUserService;
    private final boolean adminCrossUserRead;

    public TaskAccessGuard(
            PreDefenseTaskMapper taskMapper,
            CurrentUserService currentUserService,
            @Value("${security-model.ownership.admin-cross-user-read:false}") boolean adminCrossUserRead) {
        this.taskMapper = taskMapper;
        this.currentUserService = currentUserService;
        this.adminCrossUserRead = adminCrossUserRead;
    }

    /** Loads the task and requires owner-level write permission. */
    public PreDefenseTask requireOwned(Long taskId) {
        PreDefenseTask task = requirePresent(taskId);
        requireOwner(task);
        return task;
    }

    /** Loads the task and requires read permission. */
    public PreDefenseTask requireReadable(Long taskId) {
        PreDefenseTask task = requirePresent(taskId);
        if (!canRead(task)) {
            throw denied();
        }
        return task;
    }

    public void requireOwner(PreDefenseTask task) {
        // Demo rows are display fixtures that were never executed by the platform. Their owner
        // column is filled by the migration for traceability, but that must not become a write
        // right: otherwise a user could edit/start/delete a row they cannot even read.
        if (DEMO_ORIGIN.equals(task.getDataOrigin())) {
            throw denied();
        }
        AuthenticatedUser identity = currentUserService.identity();
        if (!identity.hasStableIdentity()) {
            throw denied();
        }
        if (task.getOwnerUserId() == null) {
            throw denied();
        }
        if (!task.getOwnerUserId().equals(identity.userId())) {
            throw denied();
        }
    }

    public boolean canRead(PreDefenseTask task) {
        if (task == null) {
            return false;
        }
        AuthenticatedUser identity = currentUserService.identity();
        boolean demo = DEMO_ORIGIN.equals(task.getDataOrigin());
        if (identity.admin() && adminCrossUserRead) {
            return true;
        }
        if (demo || !identity.hasStableIdentity()) {
            return false;
        }
        return identity.userId().equals(task.getOwnerUserId());
    }

    /** True when the current request may see rows owned by other users (admin cross-user read). */
    public boolean canReadAcrossUsers() {
        return currentUserService.identity().admin() && adminCrossUserRead;
    }

    public boolean canWrite() {
        return currentUserService.identity().hasStableIdentity();
    }

    private PreDefenseTask requirePresent(Long taskId) {
        PreDefenseTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new TaskCommandException("任务不存在");
        }
        return task;
    }

    private AccessDeniedException denied() {
        // Deliberately generic: the response must not reveal whether the row exists for another user.
        return new AccessDeniedException("无权访问该任务");
    }
}
