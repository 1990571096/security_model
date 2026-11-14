package com.securitymodel.application.task;

/**
 * Stable, server-owned failure text. Raw exception messages, command lines and stderr
 * are never persisted as user-facing failure reasons because they may contain paths,
 * credentials or sample markers.
 */
public final class TaskFailureMessages {

    private TaskFailureMessages() {
    }

    public static String forCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return "执行失败，详情仅保留在服务端日志";
        }
        return switch (errorCode) {
            case "EXECUTOR_NOT_CONFIGURED" -> "执行器未配置";
            case "TASK_TIMEOUT" -> "执行超时";
            case "PYTHON_PROCESS_EXITED" -> "执行进程异常退出";
            case "RESULT_MISSING" -> "未生成结果文件";
            case "RESULT_INVALID_JSON" -> "结果文件格式无效";
            case "RESULT_PROTOCOL_ERROR" -> "结果协议不匹配";
            case "GPU_OOM" -> "GPU 显存不足";
            case "CUDA_UNAVAILABLE" -> "CUDA 不可用";
            case "GPU_LOCK_BUSY" -> "GPU 运行锁被占用";
            case "GPU_LEASE_LOST" -> "GPU 租约已失效";
            case "GPU_BUSY" -> "GPU 正被占用";
            case "PROCESS_RECORD_FAILED" -> "进程记录失败";
            case "PYTHON_EXECUTION_ERROR" -> "执行过程异常";
            case "TASK_CANCELED" -> "用户取消任务";
            case "EXECUTOR_RESTARTED" -> "应用重启导致任务中断";
            case "EXPORT_NOT_STARTED" -> "导出尚未开始";
            case "EXPORT_INTERRUPTED" -> "导出被中断";
            case "EXPORT_FAILED" -> "导出失败";
            case "DISPATCH_REPLAY_FAILED" -> "调度重放失败";
            case "DISPATCH_IDENTITY_MISMATCH" -> "调度身份不一致";
            case "DISPATCH_RESOURCE_INVALID" -> "调度资源无效";
            case "DISPATCH_RESOURCE_DISABLED" -> "调度资源已禁用";
            default -> "执行失败，详情仅保留在服务端日志";
        };
    }
}
