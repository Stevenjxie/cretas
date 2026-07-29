"""报告生成的 fail-closed 异常 —— 「禁止降级处理」在代码里的落点。

项目核心原则 1: 不返回假数据，明确显示错误。报告比问答更危险 —— 问答的错
答案下一句就被追问纠正，报告是**存成文件发出去**的，一个占位数会一直被当成
事实引用。所以这里的策略是**全有或全无**: 只要有一节没拿到可信数据，整份
报告不落盘，异常里把每一节的失败原因原样带出去。
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Sequence, Tuple


@dataclass(frozen=True)
class SectionFailure:
    """一节报告失败的结构化原因。

    ``reason_code`` 是给调用方分支用的机器码，``detail`` 是可以直接展示给
    用户的中文原因 (通常就是执行链自己给出的澄清/报错原文)。
    """

    section_key: str
    heading: str
    query: str
    reason_code: str
    detail: str

    def describe(self) -> str:
        return f"「{self.heading}」({self.reason_code}): {self.detail}"


class ReportGenerationError(RuntimeError):
    """报告无法在**不编数**的前提下生成。

    调用方应把它翻译成 ``{success: false, message}``，**不要**退化成
    「生成一份缺数的报告」。
    """

    def __init__(
        self,
        message: str,
        *,
        code: str = "REPORT_GENERATION_FAILED",
        failures: Sequence[SectionFailure] = (),
    ) -> None:
        super().__init__(message)
        self.code = code
        self.failures: Tuple[SectionFailure, ...] = tuple(failures)

    @property
    def message(self) -> str:
        return str(self)

    def to_dict(self) -> dict:
        return {
            "code": self.code,
            "message": self.message,
            "failures": [
                {
                    "section_key": f.section_key,
                    "heading": f.heading,
                    "query": f.query,
                    "reason_code": f.reason_code,
                    "detail": f.detail,
                }
                for f in self.failures
            ],
        }


class ReportDataUnavailableError(ReportGenerationError):
    """连「数据截至什么时候」都答不上来 —— 此时任何报告都不该出。

    诚实到毫米 (体验原则 ⑤) 的下限: 报告必须标注数据截至日期。查不到该日期
    说明这个租户根本没有物化好的经营数据，出报告等于凭空造数。
    """

    def __init__(self, message: str, *, code: str = "REPORT_DATA_UNAVAILABLE") -> None:
        super().__init__(message, code=code)
