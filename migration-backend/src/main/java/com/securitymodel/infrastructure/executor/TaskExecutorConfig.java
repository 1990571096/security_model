package com.securitymodel.infrastructure.executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class TaskExecutorConfig {

    @Bean("cancelAsync")
    public ThreadPoolTaskExecutor cancelAsync(
            @Value("${security-model.executor.cancel-pool.core-size:1}") int coreSize,
            @Value("${security-model.executor.cancel-pool.max-size:1}") int maxSize,
            @Value("${security-model.executor.cancel-pool.queue-capacity:32}") int queueCapacity,
            @Value("${security-model.executor.cancel-pool.keep-alive-seconds:60}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("security-model-cancel-");
        // A full queue must not drop cancellation. CallerRuns keeps the signal bounded by
        // the cancel request thread instead of sharing the training pool.
        executor.setRejectedExecutionHandler((task, pool) -> task.run());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    @Bean("taskAsync")
    public ThreadPoolTaskExecutor taskAsync(
            @Value("${security-model.executor.task-pool.core-size:2}") int coreSize,
            @Value("${security-model.executor.task-pool.max-size:4}") int maxSize,
            @Value("${security-model.executor.task-pool.queue-capacity:20}") int queueCapacity,
            @Value("${security-model.executor.task-pool.keep-alive-seconds:60}") int keepAliveSeconds) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("security-model-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
