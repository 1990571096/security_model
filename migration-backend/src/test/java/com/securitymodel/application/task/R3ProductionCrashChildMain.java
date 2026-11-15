package com.securitymodel.application.task;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.executor.ExecutorArtifactPolicy;
import com.securitymodel.infrastructure.executor.ExecutorProcessGuard;
import com.securitymodel.infrastructure.executor.PythonLddTaskExecutor;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import com.securitymodel.infrastructure.persistence.mapper.GpuLeaseMapper;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * Test-only child JVM for the real dispatch crash window. It constructs the production
 * coordinator, real Python executor, real MyBatis task/intent/lease mappers, and blocks
 * inside {@code markSubmitted} after the executor has accepted the request. The parent
 * kills this JVM at that barrier; the Python child is left orphaned for the recovery JVM.
 */
public final class R3ProductionCrashChildMain {

    private R3ProductionCrashChildMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 10) {
            throw new IllegalArgumentException(
                    "url user password taskId runId attempt slot python workspace script");
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        long taskId = Long.parseLong(args[3]);
        String runId = args[4];
        int attempt = Integer.parseInt(args[5]);
        String slot = args[6];
        String python = args[7];
        String workspace = args[8];
        String script = args[9];
        ObjectMapper objectMapper = new ObjectMapper();

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, user, password);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment(
                "r3-production-crash", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                dataSource));
        configuration.addMapper(PreDefenseTaskMapper.class);
        configuration.addMapper(TaskDispatchIntentMapper.class);
        configuration.addMapper(GpuLeaseMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true)) {
            PreDefenseTaskMapper taskMapper = session.getMapper(PreDefenseTaskMapper.class);
            TaskDispatchIntentMapper intentMapper = session.getMapper(TaskDispatchIntentMapper.class);
            GpuLeaseMapper leaseMapper = session.getMapper(GpuLeaseMapper.class);

            Path marker = Path.of(workspace).resolve("crash-ready-" + taskId);
            Path taskDir = Path.of(workspace).resolve("pre_defense-" + taskId).resolve("attempt-" + attempt);
            TaskDispatchIntentService intents = new TaskDispatchIntentService(intentMapper, objectMapper) {
                @Override
                public void markSubmitted(Long taskId, String runId, Integer attempt, LocalDateTime now) {
                    try {
                        Path record = taskDir.resolve("process.json");
                        long deadline = System.currentTimeMillis() + 10_000L;
                        while (System.currentTimeMillis() < deadline) {
                            if (Files.isRegularFile(record)) {
                                break;
                          }
                            Thread.sleep(100);
                        }
                        Files.writeString(marker, "READY", StandardCharsets.UTF_8);
                        // The parent kills this JVM while the real Python child is still alive.
                        while (true) {
                            Thread.sleep(1000);
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException("crash barrier failed", exception);
                    }
                }
            };
            GpuLeaseService leases = new GpuLeaseService(leaseMapper, slot, 300,
                    new DataSourceTransactionManager(dataSource));
            AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace);
            ExecutionFingerprintService fingerprints = new ExecutionFingerprintService(
                    objectMapper, workspace, python, script, false, 1024) {
                @Override
                public ObjectNode buildRuntimeSnapshot(DefenseTaskRequest request) {
                    ObjectNode runtime = objectMapper.createObjectNode();
                    runtime.put("snapshotSchema", "security-model.execution.v2");
                    runtime.putObject("datasetFingerprint").put("available", true);
                    runtime.putObject("algorithmFingerprint").put("available", true);
                    return runtime;
                }
            };

            ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
            pool.setCorePoolSize(1);
            pool.setMaxPoolSize(1);
            pool.setQueueCapacity(4);
            pool.setThreadNamePrefix("r3-production-crash-");
            pool.initialize();
            PythonLddTaskExecutor realExecutor = new PythonLddTaskExecutor(
                    taskMapper, objectMapper, fingerprints, leases,
                    new ExecutorArtifactPolicy(objectMapper, 1024 * 1024),
                    registry, new ExecutorProcessGuard(objectMapper),
                    new TaskRuntimeMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                            taskMapper, pool, leases, 600),
                    python, script, 60);
            DefenseTaskExecutor executor = new DefenseTaskExecutor() {
                @Override
                public void runPreDefense(DefenseTaskRequest request) {
                    pool.execute(() -> realExecutor.runPreDefense(request));
                }

                @Override
                public boolean cancelPreDefense(Long taskId, String runId) {
                    return realExecutor.cancelPreDefense(taskId, runId);
                }
            };

            TaskDispatchCoordinator coordinator = new TaskDispatchCoordinator(
                    intents, executor, registry, leases, fingerprints, taskMapper,
                    proxy(SysUserMapper.class, method -> {
                        if ("selectById".equals(method)) {
                            SysUser owner = new SysUser();
                            owner.setUserId(2L);
                            owner.setEnabled(true);
                            return owner;
                        }
                        return null;
                    }),
                    proxy(DatasetMapper.class, method -> {
                        if ("selectById".equals(method)) {
                            Dataset dataset = new Dataset();
                            dataset.setDatasetId(1L);
                            dataset.setEnabled(true);
                            return dataset;
                        }
                        return null;
                    }),
                    proxy(AlgorithmMapper.class, method -> {
                        if ("selectById".equals(method)) {
                            Algorithm algorithm = new Algorithm();
                            algorithm.setAlgorithmId(1L);
                            algorithm.setEnabled(true);
                            return algorithm;
                        }
                        return null;
                    }),
                    objectMapper);

            TaskDispatchIntent intent = intents.findByRun(taskId, runId, attempt);
            PreDefenseTask task = taskMapper.selectById(taskId);
            coordinator.dispatchAfterCommit(intents.readRequest(intent));
            System.out.println("UNEXPECTED=dispatch-returned task=" + task.getTaskId());
            System.out.flush();
        }
    }

    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> answer) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> {
                    Object result = answer.apply(method.getName());
                    if (result != null) {
                        return result;
                    }
                    if ("toString".equals(method.getName())) {
                        return type.getSimpleName() + "-stub";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(instance);
                    }
                    return null;
                });
        return type.cast(proxy);
    }
}
