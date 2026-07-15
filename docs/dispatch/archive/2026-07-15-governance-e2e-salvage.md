# GOVERNANCE-E2E-SALVAGE-20260715

- 状态：`merged`（本文件随 PR #1373 合并生效）
- Base SHA：`190167767`
- 实现提交：`0accf06e4`
- PR：#1373
- 规则：把执行效率、开发去浪费和子代理协作约束同步进 tracked `AGENTS.md`。
- 发布验证：新增只读 `verify-release.sh`，从真实 Nginx upstream 严格解析 10010/10020，并检查实际部署文件 `aims-0.0.1-SNAPSHOT.jar`。
- Worktree 审计：新增 `worktree-report.sh`，按祖先关系、merged PR、补丁等价三类证据识别清理候选；dirty worktree 保守保留。
- E2E：Element Plus BOM 下拉改为真实点击；F006 大批次下拉改为输入过滤后选择。
- 验证：两份 Node 脚本 `node --check` 通过；两份 Bash 语法检查通过；发布验证与 worktree 分类目标测试通过；`git diff --check` 与编码 hook 通过。
- 清理：合并后删除本任务分支/worktree，并清理已确认过期的历史 worktree；不删除未审计业务分支。
- Scope 锁：已释放。
