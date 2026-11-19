# 简历项目描述

## 项目名称

面向数据清洗的安全模型训练系统

## 技术栈

Java 17、Spring Boot 3、Spring Security 6、JWT、MyBatis-Plus、MySQL、Vue 3、Vite、Element Plus、Pinia、Python、PyTorch、Maven、npm

## 项目简介

将网络安全竞赛中的后门防御算法重构为可视化实验管理平台，当前围绕 LDD 训练前数据清洗，提供实验资源管理、异步任务编排、Python 算法执行、运行监控、失败重试、实验报告和清洗后 CIFAR-10 子集导出能力。

## 简历要点

- 负责将原竞赛项目中的 LDD 后门防御算法接入 Spring Boot 任务平台，设计数据集、算法、超参数模板和训练前清洗任务的业务模型，完成从任务创建到结果报告的可视化闭环。
- 设计 `DefenseTaskExecutor` 统一执行器契约，使用 JSON 请求/结果协议解耦 Java 业务服务与 Python/PyTorch 算法运行时，为后续算法扩展保留稳定接口。
- 基于数据库条件更新、`run_id`、`attempt` 和任务专属目录实现并发启动保护、失败重试、取消回收和迟到回调隔离，避免旧执行结果覆盖新任务状态。
- 实现启动参数快照、数据集 SHA-256 摘要、Python/PyTorch/CUDA/GPU 运行环境记录和资源引用保护，支持实验结果追溯、问题定位和历史报告复现。
- 实现 Python 进程超时终止、任务配额、执行池状态监控、stdout/stderr 增量读取，以及基于 LDD 保留索引异步重建和下载 CIFAR-10 清洗子集。

## 面试补充说明

- 当前真实演示只开放 LDD，VS、训练中防御和训练后防御暂未接入主流程。
- 当前默认执行模型是单机本地 Python 进程和 JVM 有界线程池；R4 已实现可选的 RabbitMQ/Outbox、独立 Worker 与 GPU 单槽租约，默认仍 `dispatch.mode=db`，未启用分布式多机调度。
- 当前运行过 1 epoch 与正式 5 epochs 的 LDD **工程验收**；正式 5 epochs 任务在 17:03–17:09 完成 5 个 epoch、80 项验收检查和 GPU 租约释放。该结果只支持工程链路、配置、产物和收尾结论，不能直接替代论文效果结论。
- 迁移版已经采用 Spring Boot 3、Java 17、Vue 3，并使用 Redis 缓存资源目录；R4 RabbitMQ/Outbox/独立 Worker 已接入，默认 `dispatch.mode=db`。2026-11-16 本机真实 RabbitMQ + 双 JVM 完成 R4-01..08 实现验收；2026-11-18 完成单机 `db`/`rabbit` 启停与端口释放演练；生产级多机部署未验收，不应表述为已上线。当前测试数字与边界见 `docs/sdd/26-current-delivery-facts.md`。

