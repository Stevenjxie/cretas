/**
 * 单据「下单日期」的统一取值 —— 本地日历日的 YYYY-MM-DD。
 *
 * 后端销售/采购两侧的 CreateXxxOrderRequest 都把 orderDate 标了
 * `@NotNull(message = "下单日期不能为空")`，不送就是 400。
 *
 * 2026-08-04: 采购建单整条链上都没有这个字段（界面不收集、RN 类型没声明、payload 不送），
 * 于是「不能新建采购订单，提示 request failed code 400」。销售建单一直有，只是那份
 * `todayIso` 是写在屏幕文件里的私有副本 —— 同一条口径两处实现，这次收敛成一处。
 */
export function todayIso(now: Date = new Date()): string {
  // ⚠️ 不用 toISOString(): 它按 UTC 截断, 东八区当天 08:00 之前会得到「昨天」。
  //    下单日期是业务上的本地日历日, 必须按本地时区取。
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}
