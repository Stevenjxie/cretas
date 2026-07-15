# PROCESS-SHEET-ROLLBACK-20260715

- 状态：`merged`
- Base SHA：`07128305c`
- 修复提交：`3f741d0ca`
- 主线合并：PR #1366，merge commit `164f282ef`
- 发布版本：`v20260715_111356`
- 生产槽位：green/10020 → blue/10010
- 问题：追踪码 `37312D8C`。逐道录入创建第二个计划关联批次后，Workflow 快照查询变得不唯一；被捕获的内层事务异常仍将外层事务标记为 rollback-only。
- 修复：成品道复用计划已有 Workflow 运行批次；任务进度改为主事务提交后通过独立事务回写，回写失败不再回滚逐道录入。
- 验证：旧逻辑反证失败；目标测试 16/16 通过；生产 Nginx 5/5 HTTP 200；blue/10010 active，green/10020 inactive；运行 JAR 含新增事件、监听器和 writer 类。
- Scope 锁：已释放。
