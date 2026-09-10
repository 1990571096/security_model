package me.zhengjie.modules.system.service.policy;

import me.zhengjie.exception.BadRequestException;
import me.zhengjie.modules.system.domain.PreDefense;
import me.zhengjie.modules.system.mapper.PreDefenseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskExecutionQuotaPolicyTest {

    @Mock
    private PreDefenseMapper preDefenseMapper;

    private ThreadPoolTaskExecutor taskExecutor;

    @BeforeEach
    void setUp() {
        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(1);
        taskExecutor.initialize();
        when(preDefenseMapper.findByStatus("running")).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        taskExecutor.shutdown();
    }

    @Test
    void buildsQuotaSnapshotWhenCapacityIsAvailable() {
        TaskExecutionQuotaPolicy policy =
                new TaskExecutionQuotaPolicy(preDefenseMapper, taskExecutor, 2, 1, 60);
        PreDefense task = task("alice");

        assertEquals(2, policy.validateAndBuildSnapshot(task).getIntValue("maxRunningTasks"));
        assertEquals(0, policy.getStatus().get("runningTasks"));
    }

    @Test
    void rejectsSystemQuota() {
        PreDefense first = task("alice");
        PreDefense second = task("bob");
        when(preDefenseMapper.findByStatus("running")).thenReturn(Arrays.asList(first, second));
        TaskExecutionQuotaPolicy policy =
                new TaskExecutionQuotaPolicy(preDefenseMapper, taskExecutor, 2, 2, 60);

        assertThrows(BadRequestException.class,
                () -> policy.validateAndBuildSnapshot(task("carol")));
    }

    @Test
    void rejectsPerUserQuota() {
        when(preDefenseMapper.findByStatus("running"))
                .thenReturn(Collections.singletonList(task("alice")));
        TaskExecutionQuotaPolicy policy =
                new TaskExecutionQuotaPolicy(preDefenseMapper, taskExecutor, 4, 1, 60);

        assertThrows(BadRequestException.class,
                () -> policy.validateAndBuildSnapshot(task("alice")));
    }

    private PreDefense task(String user) {
        PreDefense task = new PreDefense();
        task.setCreateBy(user);
        return task;
    }
}
