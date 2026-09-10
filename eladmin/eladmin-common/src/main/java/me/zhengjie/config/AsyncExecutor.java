/*
 *  Copyright 2019-2025 Zheng Jie
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package me.zhengjie.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建自定义的线程池
 * @author Zheng Jie
 * @description
 * @date 2023-06-08
 **/
@EnableAsync
@Configuration
public class AsyncExecutor implements AsyncConfigurer {

    public static int corePoolSize;

    public static int maxPoolSize;

    public static int keepAliveSeconds;

    public static int queueCapacity;

    private final AtomicInteger asyncThreadNumber = new AtomicInteger(1);

    @Value("${security-model.executor.task-pool.core-size:2}")
    private int taskCorePoolSize;

    @Value("${security-model.executor.task-pool.max-size:4}")
    private int taskMaxPoolSize;

    @Value("${security-model.executor.task-pool.keep-alive-seconds:60}")
    private int taskKeepAliveSeconds;

    @Value("${security-model.executor.task-pool.queue-capacity:20}")
    private int taskQueueCapacity;

    @Value("${security-model.executor.task-pool.wait-for-tasks-on-shutdown:true}")
    private boolean taskWaitForTasksOnShutdown;

    @Value("${security-model.executor.task-pool.await-termination-seconds:30}")
    private int taskAwaitTerminationSeconds;

    @Value("${task.pool.core-pool-size}")
    public void setCorePoolSize(int corePoolSize) {
        AsyncExecutor.corePoolSize = corePoolSize;
    }

    @Value("${task.pool.max-pool-size}")
    public void setMaxPoolSize(int maxPoolSize) {
        AsyncExecutor.maxPoolSize = maxPoolSize;
    }

    @Value("${task.pool.keep-alive-seconds}")
    public void setKeepAliveSeconds(int keepAliveSeconds) {
        AsyncExecutor.keepAliveSeconds = keepAliveSeconds;
    }

    @Value("${task.pool.queue-capacity}")
    public void setQueueCapacity(int queueCapacity) {
        AsyncExecutor.queueCapacity = queueCapacity;
    }

    /**
     * 自定义线程池，用法 @Async
     * @return Executor
     */
    @Override
    public Executor getAsyncExecutor() {
        // 自定义工厂
        ThreadFactory factory = r -> new Thread(r, "el-async-" + asyncThreadNumber.getAndIncrement());
        // 自定义线程池
        return new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveSeconds,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity), factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 自定义线程池，用法，注入到类中使用
     * private ThreadPoolTaskExecutor taskExecutor;
     * @return ThreadPoolTaskExecutor
     */
    @Bean("taskAsync")
    public ThreadPoolTaskExecutor taskAsync() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(taskCorePoolSize);
        executor.setMaxPoolSize(taskMaxPoolSize);
        executor.setQueueCapacity(taskQueueCapacity);
        executor.setKeepAliveSeconds(taskKeepAliveSeconds);
        executor.setThreadNamePrefix("el-task-");
        // Long-running Python jobs must never fall back to the HTTP request
        // thread when the bounded task pool is full.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(taskWaitForTasksOnShutdown);
        executor.setAwaitTerminationSeconds(taskAwaitTerminationSeconds);
        return executor;
    }
}
