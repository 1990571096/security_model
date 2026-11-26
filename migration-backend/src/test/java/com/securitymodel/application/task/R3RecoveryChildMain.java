package com.securitymodel.application.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.securitymodel.domain.task.PreDefenseTask;
import com.securitymodel.domain.task.TaskDispatchIntent;
import com.securitymodel.domain.algorithm.Algorithm;
import com.securitymodel.domain.dataset.Dataset;
import com.securitymodel.domain.user.SysUser;
import com.securitymodel.infrastructure.executor.AttemptProcessRegistry;
import com.securitymodel.infrastructure.executor.DefenseTaskExecutor;
import com.securitymodel.infrastructure.observability.TaskRuntimeMetrics;
import com.securitymodel.infrastructure.persistence.mapper.AlgorithmMapper;
import com.securitymodel.infrastructure.persistence.mapper.DatasetMapper;
import com.securitymodel.infrastructure.persistence.mapper.GpuLeaseMapper;
import com.securitymodel.infrastructure.persistence.mapper.PreDefenseTaskMapper;
import com.securitymodel.infrastructure.persistence.mapper.SysUserMapper;
import com.securitymodel.infrastructure.persistence.mapper.TaskDispatchIntentMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;

/**
 * Test-only new JVM recovery. It starts a fresh MyBatis session (no parent JVM memory),
 * performs the production {@link TaskDispatchCoordinator#replayPending} path against real
 * MySQL lease/intent rows, and executes a short Python protocol stub instead of LDD.
 */
public final class R3RecoveryChildMain {

    private R3RecoveryChildMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 10) {
            throw new IllegalArgumentException(
                    "url user password taskId runId attempt slotId pythonExecutable workspace mode");
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        long taskId = Long.parseLong(args[3]);
        String runId = args[4];
        int attempt = Integer.parseInt(args[5]);
        String slotId = args[6];
        String python = args[7];
        String workspace = args[8];
        String mode = args[9];
        ObjectMapper objectMapper = new ObjectMapper();

        UnpooledDataSource dataSource = new UnpooledDataSource(
                "com.mysql.cj.jdbc.Driver", url, user, password);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment(
                "r3-recovery", new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(),
                dataSource));
        configuration.addMapper(PreDefenseTaskMapper.class);
        configuration.addMapper(TaskDispatchIntentMapper.class);
        configuration.addMapper(GpuLeaseMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder()
                .build(configuration);
        try (SqlSession session = factory.openSession(true)) {
            PreDefenseTaskMapper taskMapper = session.getMapper(PreDefenseTaskMapper.class);
            TaskDispatchIntentMapper intentMapper = session.getMapper(TaskDispatchIntentMapper.class);
            GpuLeaseMapper leaseMapper = session.getMapper(GpuLeaseMapper.class);

            TaskDispatchIntentService intents = new TaskDispatchIntentService(intentMapper, objectMapper);
            GpuLeaseService leases = new GpuLeaseService(leaseMapper, slotId, 300,
                    new DataSourceTransactionManager(dataSource));
            AttemptProcessRegistry registry = new AttemptProcessRegistry(objectMapper, workspace);
            ExecutionFingerprintService fingerprints = new ExecutionFingerprintService(
                    objectMapper, "", python, "", false, 1024) {
                @Override
                public ObjectNode buildRuntimeSnapshot(DefenseTaskRequest request) {
                    ObjectNode runtime = objectMapper.createObjectNode();
                    runtime.put("snapshotSchema", "security-model.execution.v2");
                    runtime.putObject("datasetFingerprint").put("available", true);
                    runtime.putObject("algorithmFingerprint").put("available", true);
                    return runtime;
                }
            };

            DefenseTaskExecutor executor = new DefenseTaskExecutor() {
                @Override
                public void runPreDefense(DefenseTaskRequest request) {
                    try {
                        Process process = new ProcessBuilder(python, "-c",
                                "import os; print('R3_STUB_OK'); print(os.getpid())")
                                .redirectErrorStream(true).start();
                        String output = new String(process.getInputStream().readAllBytes());
                        int exit = process.waitFor();
                        String pid = output.lines()
                                .filter(line -> line.matches("[0-9]+"))
                                .findFirst().orElse("0");
                        try (Connection evidence = DriverManager.getConnection(url, user, password);
                             PreparedStatement insert = evidence.prepareStatement("""
                                     INSERT INTO sys_r3_execution_evidence
                                       (task_id, run_id, attempt, java_pid, python_pid, output, exit_code)
                                     VALUES (?, ?, ?, ?, ?, ?, ?)
                                     """)) {
                            insert.setLong(1, request.getTaskId());
                            insert.setString(2, request.getRunId());
                            insert.setInt(3, request.getAttempt());
                            insert.setLong(4, ProcessHandle.current().pid());
                            insert.setLong(5, Long.parseLong(pid));
                            insert.setString(6, output.trim());
                            insert.setInt(7, exit);
                            insert.executeUpdate();
                        }
                        if (exit != 0) {
                            throw new IllegalStateException("python stub failed: " + output);
                        }
                    } catch (Exception exception) {
                        throw new IllegalStateException("python stub failed", exception);
                    }
                }

                @Override
                public boolean cancelPreDefense(Long taskId, String runId) {
                    return true;
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

            if ("startup".equals(mode)) {
                SchedulingReadinessGate gate = new SchedulingReadinessGate();
                io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
                org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor pool =
                        new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
                pool.initialize();
                TaskRuntimeMetrics metrics = new TaskRuntimeMetrics(
                        meterRegistry, taskMapper, pool, leases, 600);
                DispatchRecoveryCoordinator recovery = new DispatchRecoveryCoordinator(
                        taskMapper, intents, registry, leases, coordinator, gate, metrics,
                        30, 300, 20);
                recovery.recoverAtStartup();
                System.out.println("RECOVERED=startup intent=" + intents.findByRun(
                        taskId, runId, attempt).getStatus());
            } else {
                TaskDispatchIntent intent = intents.findByRun(taskId, runId, attempt);
                PreDefenseTask task = taskMapper.selectById(taskId);
                boolean recovered = coordinator.replayPending(intent, task);
                System.out.println("RECOVERED=" + recovered + " intent=" + intents.findByRun(
                        taskId, runId, attempt).getStatus());
            }
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
