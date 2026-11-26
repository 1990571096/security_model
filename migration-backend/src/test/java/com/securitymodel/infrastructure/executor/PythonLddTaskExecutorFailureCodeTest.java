package com.securitymodel.infrastructure.executor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PythonLddTaskExecutorFailureCodeTest {

    @Test
    void cudaOutOfMemoryIsGpuOom() {
        assertEquals("GPU_OOM", PythonLddTaskExecutor.classifyProcessFailure(
                "RuntimeError: CUDA out of memory. Tried to allocate 2.00 GiB"));
    }

    @Test
    void cublasAllocFailureIsGpuOom() {
        assertEquals("GPU_OOM", PythonLddTaskExecutor.classifyProcessFailure(
                "RuntimeError: CUBLAS_STATUS_ALLOC_FAILED when calling `cublasCreate`"));
    }

    @Test
    void driverMismatchIsCudaUnavailable() {
        assertEquals("CUDA_UNAVAILABLE", PythonLddTaskExecutor.classifyProcessFailure(
                "RuntimeError: CUDA driver version is insufficient for CUDA runtime version"));
    }

    @Test
    void missingDeviceIsCudaUnavailable() {
        assertEquals("CUDA_UNAVAILABLE", PythonLddTaskExecutor.classifyProcessFailure(
                "RuntimeError: No CUDA-capable device is detected"));
    }

    @Test
    void adapterLockConflictIsGpuLockBusy() {
        assertEquals("GPU_LOCK_BUSY", PythonLddTaskExecutor.classifyProcessFailure(
                "GPU_LOCK_BUSY: another VaB task is using the shared GPU/runtime lock"));
    }

    @Test
    void unrelatedCrashStaysPythonProcessExited() {
        assertEquals("PYTHON_PROCESS_EXITED", PythonLddTaskExecutor.classifyProcessFailure(
                "Traceback (most recent call last): FileNotFoundError: LDD.py"));
    }

    @Test
    void nullOrEmptyStaysPythonProcessExited() {
        assertEquals("PYTHON_PROCESS_EXITED", PythonLddTaskExecutor.classifyProcessFailure(null));
        assertEquals("PYTHON_PROCESS_EXITED", PythonLddTaskExecutor.classifyProcessFailure(""));
    }
}
