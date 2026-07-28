"""餐饮域包 (domain package)。

spec 2026-07-28 §7「平台化通用准备」的落地第一步: 把餐饮专属的规划器 /
契约 / ETL / 晋升逻辑收进一个自洽的包, 让 `smartbi/gold/` 顶层只剩**真正
域无关**的东西 (materializer / pipeline / dual_write / queries …)。

⛔ 为什么 answer_contract 和 pos_name_resolver 也在这里:
   它们名字像共享件, 实际是餐饮件 —— answer_contract 的每个函数签名都挂在
   `RestaurantQuerySpec` 上, 还硬编码了 "restaurant-query-plan-v2" 与
   RESTAURANT_OPS_* 意图码。留在顶层会让"共享层"名不副实, 边界第一天就破。
   spec §7.3 把「planner 框架 + 契约封章」列为可复用核心, 那是**目标态**;
   要变成真的共享件需要先抽出域无关基类 (泛化重构), 不在本次范围。

将来拆独立服务时, 整个包可以成建制搬走。新增餐饮专属模块请放这里,
不要再放回 `smartbi/gold/` 顶层。
"""
