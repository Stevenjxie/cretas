# 餐饮 AI 演示手册发布说明（2026-08-03）

## 目标

将餐饮数字指挥屏、预测排班、部门协作、数据图表、AI 防黑盒和演示话术整理成一份不需要进入代码仓库、可从任何电脑直接访问的白话 HTML 手册。

## 发布资源

- 源文件：`web-admin/public/restaurant-ai-demo/index.html`
- 生产 URL：`https://admin.cretaceousfuture.com/restaurant-ai-demo/`
- 访问边界：手册本身公开可读，不包含用户名、密码、Token、租户 ID 或生产实时数字；进入真实系统仍需受控餐饮账号登录。

## 内容真值

- 数字大屏为 60 秒只读轮询的分钟级准实时，不描述为 WebSocket 秒级推送。
- 预测排班数字来自预测 FactBook；大模型只负责理解、解释和建议。
- 历史实际人效与目标人效只作证据，不能直接把 `actual < target` 推导为缺人。
- 通用餐饮问答、预测排班和受限毛利归因 Agent 分开讲；当前不能把受限 Agent 描述成覆盖所有部门的自主 Agent。
- 生产演示默认只读。任何调整只展示预览，不执行最终确认。

## 维护方式

当餐饮角色、入口、支持意图、刷新机制或写入边界变化时，同步更新 HTML 和 `scripts/tests/restaurant-ai-demo-guide.test.js`，并重新完成 Web manifest 构建、桌面/移动视觉检查和公网验收。
