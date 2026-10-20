"""只读 backend 包装。

知识问答与知识生成都只需要**读**源码。给它们一个只读 backend，
写文件、改文件、执行命令这类调用会在执行侧直接失败 ——
而不是靠提示词请求模型「不要改」，那只是建议，不是约束。

实现上刻意把每个方法**显式定义在类上**，而不是只靠 `__getattr__` 拦截：
框架会做类级的能力探测（`hasattr(type(backend), 'delete')` 这类），
`__getattr__` 只对实例查找生效，类上查不到方法会让探测失败、
整轮任务直接报 `type object 'ReadOnlyBackend' has no attribute 'delete'`。
显式定义之后，探测得到「有这个能力」，调用时被拒绝并给出清楚的原因。
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


class ReadOnlyViolation(RuntimeError):
    """只读任务试图产生副作用。"""


def _refuse(name: str):
    """生成一个「定义存在但一律拒绝」的方法。"""

    def _blocked(self, *args, **kwargs):
        logger.warning("只读任务尝试调用 %s，已拒绝", name)
        raise ReadOnlyViolation(
            f"当前是只读任务，不允许调用 {name}；"
            f"如需修改代码或执行命令，请在生成对话里提出。"
        )

    _blocked.__name__ = name
    _blocked.__doc__ = f"只读任务禁用 {name}。"
    return _blocked


def _refuse_async(name: str):
    """异步版的拒绝方法。

    必须是 async：调用方写的是 `await backend.awrite(...)`，
    返回一个普通函数会在 await 处报 TypeError，错误信息变成「不能 await」，
    而不是我们想告诉模型的「只读任务不允许写」。
    """
    refuse = _refuse(name)

    async def _blocked(self, *args, **kwargs):
        return refuse(self, *args, **kwargs)

    _blocked.__name__ = name
    _blocked.__doc__ = f"只读任务禁用 {name}。"
    return _blocked


# 会产生副作用的同步方法名。
_WRITE_METHODS = (
    "write",
    "edit",
    "delete",
    "upload_files",
    "download_files",
    # execute 也算：命令能改文件、能启动进程，「只读」不能只在文件工具上成立
    "execute",
)


def _forward(name: str):
    """生成一个原样转发给内层的读方法。"""

    def _fwd(self, *args, **kwargs):
        return getattr(self._inner, name)(*args, **kwargs)

    _fwd.__name__ = name
    _fwd.__doc__ = f"读操作 {name}：转发给内层 backend。"
    return _fwd


def _forward_async(name: str):
    """异步版的转发方法。"""

    async def _fwd(self, *args, **kwargs):
        return await getattr(self._inner, name)(*args, **kwargs)

    _fwd.__name__ = name
    _fwd.__doc__ = f"读操作 {name}：转发给内层 backend。"
    return _fwd


class ReadOnlyBackend:
    """只读包装：读操作转发给内层 backend，写操作一律拒绝。"""

    # 会产生副作用的方法名，同步与异步都列。
    #
    # 异步那套必须显式列出，否则会走 __getattr__ 透传给内层：
    # 框架的 backend protocol 同时定义了 write 和 awrite，类上找不到 awrite
    # 就转发给内层真实 backend —— 上面所有限制被整个绕过去。
    # 实测里知识任务就是用 awrite / adelete 建了又删了文件。
    BLOCKED_METHODS = _WRITE_METHODS + tuple("a" + m for m in _WRITE_METHODS)

    def __init__(self, inner):
        self._inner = inner

    # ── 读操作：原样转发（同步 + 异步）────────────────────────
    ls = _forward("ls")
    read = _forward("read")
    grep = _forward("grep")
    glob = _forward("glob")

    als = _forward_async("als")
    aread = _forward_async("aread")
    agrep = _forward_async("agrep")
    aglob = _forward_async("aglob")

    # ── 写操作：定义存在但一律拒绝（同步 + 异步）───────────────
    write = _refuse("write")
    edit = _refuse("edit")
    delete = _refuse("delete")
    upload_files = _refuse("upload_files")
    download_files = _refuse("download_files")
    execute = _refuse("execute")

    awrite = _refuse_async("awrite")
    aedit = _refuse_async("aedit")
    adelete = _refuse_async("adelete")
    aupload_files = _refuse_async("aupload_files")
    adownload_files = _refuse_async("adownload_files")
    aexecute = _refuse_async("aexecute")

    def __getattr__(self, name):
        # 兜底：框架以后再加 aXXX 形式的写操作时，这里还能挡一层。
        # 去掉前导 a 能对上一个已拦的同步名，就按写操作处理。
        if name.startswith("a") and name[1:] in _WRITE_METHODS:
            return _refuse_async(name)
        # 其余成员（close、id 等）转给内层。
        # 注意：只有类上找不到的名字才会走到这里。
        return getattr(self._inner, name)

    def __repr__(self) -> str:  # pragma: no cover - 仅用于日志
        return f"ReadOnlyBackend({self._inner!r})"
