package com.securitymodel.application.task;

import com.securitymodel.application.audit.AuthenticatedUser;
import com.securitymodel.application.audit.CurrentUserService;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskAccessGuardTest {

    private final PreDefenseTaskMapper mapper = mock(PreDefenseTaskMapper.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);

    @Test
    void ownerMayReadAndWrite() {
        TaskAccessGuard guard = guard(false);
        authenticate(7L, "alice", false);
        PreDefenseTask task = task(100L, 7L, "user");

        assertTrue(guard.canRead(task));
        assertDoesNotThrow(() -> guard.requireOwned(100L));
    }

    @Test
    void anotherUserCanNeitherReadNorWrite() {
        TaskAccessGuard guard = guard(false);
        authenticate(8L, "bob", false);
        PreDefenseTask task = task(100L, 7L, "user");

        assertFalse(guard.canRead(task));
        assertThrows(AccessDeniedException.class, () -> guard.requireReadable(100L));
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));
    }

    @Test
    void adminReadsOtherUsersOnlyWhenExplicitlyEnabled() {
        TaskAccessGuard disabled = guard(false);
        authenticate(1L, "admin", true);
        PreDefenseTask task = task(100L, 7L, "user");
        assertFalse(disabled.canRead(task));
        assertThrows(AccessDeniedException.class, () -> disabled.requireReadable(100L));

        TaskAccessGuard enabled = guard(true);
        assertTrue(enabled.canRead(task));
        assertDoesNotThrow(() -> enabled.requireReadable(100L));
        // Cross-user write stays forbidden even for an admin.
        assertThrows(AccessDeniedException.class, () -> enabled.requireOwned(100L));
    }

    @Test
    void legacyTokenWithoutUserIdIsRejected() {
        TaskAccessGuard guard = guard(false);
        when(currentUser.identity()).thenReturn(new AuthenticatedUser(null, "alice", false));
        PreDefenseTask task = task(100L, 7L, "user");

        assertFalse(guard.canRead(task));
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));
    }

    @Test
    void unattributedHistoricalRowIsNotReadableByUsers() {
        TaskAccessGuard guard = guard(true);
        authenticate(7L, "alice", false);
        PreDefenseTask task = task(100L, null, "legacy");

        assertFalse(guard.canRead(task));
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));

        authenticate(1L, "admin", true);
        assertTrue(guard.canRead(task));
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));
    }

    @Test
    void demoRowsAreHiddenFromTheirCreator() {
        TaskAccessGuard guard = guard(false);
        authenticate(7L, "alice", false);
        PreDefenseTask task = task(100L, 7L, "demo");

        assertFalse(guard.canRead(task));
        // The migration may fill owner_user_id on demo rows; that must not become a write right.
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));
    }

    @Test
    void adminCannotWriteDemoRowsEither() {
        TaskAccessGuard guard = guard(true);
        authenticate(1L, "admin", true);
        PreDefenseTask task = task(100L, 1L, "demo");

        assertTrue(guard.canRead(task));
        assertThrows(AccessDeniedException.class, () -> guard.requireOwned(100L));
    }

    @Test
    void missingTaskIsReportedAsCommandFailure() {
        TaskAccessGuard guard = guard(false);
        authenticate(7L, "alice", false);
        assertThrows(TaskCommandException.class, () -> guard.requireReadable(404L));
    }

    private TaskAccessGuard guard(boolean adminCrossUserRead) {
        return new TaskAccessGuard(mapper, currentUser, adminCrossUserRead);
    }

    private void authenticate(Long userId, String username, boolean admin) {
        when(currentUser.identity()).thenReturn(new AuthenticatedUser(userId, username, admin));
    }

    private PreDefenseTask task(Long taskId, Long ownerUserId, String origin) {
        PreDefenseTask task = new PreDefenseTask();
        task.setTaskId(taskId);
        task.setOwnerUserId(ownerUserId);
        task.setDataOrigin(origin);
        when(mapper.selectById(taskId)).thenReturn(task);
        return task;
    }
}
