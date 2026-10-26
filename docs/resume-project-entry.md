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
- 当前执行模型是单机本地 Python 进程和 JVM 有界线程池，尚未引入消息队列、分布式 worker 和 GPU 调度。
- 当前运行过小批次、小轮次工程验证，不能直接替代论文正式实验结论。
- 迁移版已经采用 Spring Boot 3、Java 17、Vue 3，并使用 Redis 缓存资源目录；RabbitMQ/Kafka 和分布式 Worker 尚未接入，不应列为当前已实现技术。

