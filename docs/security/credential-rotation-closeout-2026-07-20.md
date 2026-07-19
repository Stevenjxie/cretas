# 凭证轮换收尾记录（2026-07-20）

## 结论

当前 tracked tree、生产 systemd 单元、生产主配置和服务器无消费者副本中的已知明文已清理；数据库密码、JWT、内部服务 secret、BaoTa API token 和 Aliyun OSS RAM key 已完成替换，生产消费者已重新加载并通过健康/业务验收。

本任务尚不能归档为 `merged`：阿里云主账号旧 AccessKey、旧模型供应商 API key 和 Mall 微信长期凭证仍需相应控制台管理员执行最终吊销或重置。本文不记录任何明文，只记录状态与短指纹。

## 已完成

- PR #1516 清理原 tracked 明文并新增 `Secret regression gate`；合并提交 `e12a6633f2b88d780751c0bdb9d346ffbfd854b9`。
- 后续 scanner 补丁支持 `KEY=value` 右值指纹，加入数据库/JWT/Mall 微信已暴露指纹，并为赋值形式补回归测试。
- 两套 PostgreSQL 生产角色密码已轮换；`.env.prod`、`.env.distill`、`.env.gold-etl` 新 DSN 认证通过。
- JWT 与内部服务 secret 已轮换；Java/Python systemd 单元不再保留 inline 明文。
- Aliyun OSS replacement RAM key 完成读、写、删除 canary；canary 与临时文件已移除。
- BaoTa token 通过官方 `panelApi.set_token` 轮换；新摘要匹配、旧 token 签名不再满足实际 `common.py` 校验公式；受控 token 文件权限为 `0600`。
- Mall 三个 LLM key 消费点已切到新 Aliyun LLM key；5 个微信敏感字段和 3 个 LLM 字段全部改为 systemd EnvironmentFile 占位符。`mall-backend.env` 与生产 `application.yml` 均为 `0600`，运行进程 6 个变量与受控源逐项一致，8 个 YAML 占位符验证通过。
- 139 的重复旧 `spring_logistics-admin.service` 已禁用；`mall-backend` 重启后 active/running，本机 8080 HTTP 200，微信小程序配置正常加载。
- 47 删除 44 个无消费者旧备份/脚本及本次精确轮换备份目录；139 删除 12 个无消费者旧脚本、历史配置和轮换备份。两台主机的任务临时脚本、tar 和单值 key 传输文件均已删除。
- 47 最终已知指纹扫描仅剩 3 份仓库策略禁止修改的 Superpowers 历史文档，包含的 internal secret 已撤销且无运行消费者；139 最终仅命中 `0600` root-only Mall 微信 EnvironmentFile。

## 生产验收

- 标准 backend release verification：当前真实 upstream 为 green/10020，`cretas-backend-green` 健康通过。
- Java、Python、Embedding 服务健康；公网 Cretas health/admin 为 HTTP 200。
- Restaurant Agent production smoke：fresh demo login 成功，run `26877cec-5a66-41fc-aeab-d7ffbfd5bd22` 收到 `RUN_COMPLETED`，replay 为 `PARTIAL`（有 evidence gap，但无运行失败）。
- Agent smoke 前后 ERP 计数完全一致：purchase orders 50、sales orders 127、work-process tasks 30、production reports 6417；Agent ledger 仅增加 1 run 和 12 events。
- Mall 重启日志无配置占位符、端口绑定或启动异常；root-only env 与 `/proc/<pid>/environ` 一致。

## 尚需供应商控制台动作

1. **Aliyun RAM**：删除旧主账号 AccessKey（短指纹：ID `edf614d04235`，secret `33b27390b2be`）。API 自删除返回 `Forbidden`，旧 key 的 STS 验证仍成功，不能声明已吊销。
2. **Alibaba Model Studio / DashScope**：在 API Key 页面禁用或删除旧 key；生产已使用新 key。官方文档说明长期 key 不自动过期，必须显式禁用、删除或重置。
3. **Zhipu / DeepSeek**：删除已暴露旧 key；DeepSeek 当前已禁用，Zhipu 生产消费者已使用 replacement key。
4. **微信公众平台 / 商户平台**：协调重置 MP secret、token、AES key、Mini App secret 与商户 key；将新值写入 `/root/.config/cretas/mall-backend.env` 后重启 `mall-backend`，再重复 runtime contract 与 8080 验收。

## Git 历史边界

- 当前 GitHub 仓库为 private，当前 tracked tree scanner 通过。
- 完整 Git 历史仍包含此前提交过的旧值；本轮未做 history rewrite 或 force push。只有在所有历史值完成供应商侧吊销后，历史保留才可视为可接受的审计残留；如决定清除对象，必须另开高风险任务评估 fork、clone、PR 和部署引用影响。

## 发布边界

本轮没有部署新的 Java/Python/Web 应用制品。生产变更仅包括凭证/配置原子替换、必要服务重启、真实 upstream 切换验证、Mall secret 外置与 stale 文件清理。
