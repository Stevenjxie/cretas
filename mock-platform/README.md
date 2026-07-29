# 餐饮外部平台模拟器

模拟二维火/客如云/美团/抖音风格的餐饮开放平台，部署在 139，与 Cretas 系统物理隔离。

- 存储：SQLite（不碰 PostgreSQL）
- 唯一出口：HTTP
- 设计：`docs/superpowers/specs/2026-07-29-restaurant-mock-platform-api-design.md`

## 本地跑

    export MOCK_KERUYUN_APP_KEY=... MOCK_KERUYUN_APP_SECRET=... MOCK_CALLBACK_SECRET=...
    python -m mock_platform.cli serve
