"""外部餐饮平台增量接入。

各平台 adapter 把自己的报文归一化成 models.NormalizedOrder, 框架负责
游标推进 / 幂等 / 退避 / 失败隔离, writer 负责落 Silver。
"""
