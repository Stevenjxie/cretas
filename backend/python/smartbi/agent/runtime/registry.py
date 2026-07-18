"""Side-effect-free registry for explicitly approved Read Tools."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Mapping

from .contracts import EvidenceDraft, TrustedExecutionContext
from .descriptors import ReadToolDescriptor


ReadAdapter = Callable[
    [Any, TrustedExecutionContext, Mapping[str, Any], ReadToolDescriptor],
    Awaitable[EvidenceDraft],
]


@dataclass(frozen=True)
class RegisteredReadTool:
    descriptor: ReadToolDescriptor
    adapter: ReadAdapter


class ReadonlyToolRegistry:
    def __init__(self) -> None:
        self._tools: dict[str, RegisteredReadTool] = {}

    def register(self, descriptor: ReadToolDescriptor, adapter: ReadAdapter) -> None:
        if not isinstance(descriptor, ReadToolDescriptor):
            raise TypeError("registry requires an explicit ReadToolDescriptor")
        if descriptor.access_mode != "READ_ONLY":
            raise ValueError("write-capable descriptors are forbidden")
        if not callable(adapter):
            raise TypeError("read adapter must be callable")
        if descriptor.name in self._tools:
            raise ValueError(f"duplicate Read Tool: {descriptor.name}")
        self._tools[descriptor.name] = RegisteredReadTool(descriptor, adapter)

    def require(self, name: str) -> RegisteredReadTool:
        try:
            return self._tools[name]
        except KeyError as exc:
            raise KeyError(f"unknown or unapproved Read Tool: {name}") from exc

    def descriptors(self) -> tuple[ReadToolDescriptor, ...]:
        return tuple(tool.descriptor for tool in self._tools.values())

    def names(self) -> tuple[str, ...]:
        return tuple(self._tools)
