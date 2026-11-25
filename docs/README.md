# 安全模型训练平台文档

本文档按 2026 年 11 月 23 日的当前工作区核对（`HEAD=a674a04`，包含未提交的源码更新）。初次学习先读完整案例，再按课程顺序动手。掌握主线后回到这里选择技术主题和面试练习。

| 我想做什么 | 从哪里开始 |
| --- | --- |
| 读完一次正常实验 | [从一份数据走到一份报告](learning/00-one-experiment.md) |
| 按顺序读源码和做练习 | [学习计划与实操课程](learning/README.md) |
| 用项目学开发基础和八股 | [技术栈学习地图](learning/technology-map.md) |
| 看懂系统怎么分工 | [设计思路](project-design-overview.md) |
| 顺着接口读代码 | [实现指南](project-implementation-guide.md) |
| 练自我介绍和白板 | [项目讲解与演示](project-introduction-and-interview-guide.md) |
| 准备技术追问 | [面试题与模拟面试](project-interview-preparation.md) |
| 写简历 | [简历参考](resume-project-entry.md) |
| 学习知识检索和受控助手 | [Agent / RAG 实操](agent-rag-demo.md) |
| 查设计演进、契约和验收证据 | [SDD 索引](sdd/README.md) |

当前真实算法链路是训练前 LDD 数据清洗。课程按默认 `db/api` 走一遍创建、确认启动、执行、报告和导出。环境暂缺时可从纯源码和离线短桩开始。正式运行环境和证据见 [交付事实](sdd/26-current-delivery-facts.md)。

改代码时，先更新接口、参数、状态或运行步骤所属的主文档，再检查课程练习和面试答案。验收结果要带提交、环境、运行范围和执行/跳过结果。旧版本证据保存在原 SDD 页的历史块中。
