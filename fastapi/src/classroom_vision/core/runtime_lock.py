"""同一结果卷只允许一个视觉服务写入，启动时回收崩溃留下的暂存目录。"""

import os
import shutil
from contextlib import contextmanager


@contextmanager
def owned_results(root):
    root = root.resolve()
    root.mkdir(parents=True, exist_ok=True)
    with (root / ".vision.lock").open("a+b") as handle:
        handle.seek(0, 2)
        if handle.tell() == 0:
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError("结果卷已有视觉服务，禁止并发清理或写入") from error
        try:
            for path in root.glob("video-*"):
                if (
                    path.is_symlink()
                    or path.is_junction()
                    or not path.resolve().is_relative_to(root)
                ):
                    raise ValueError("视觉暂存目录越界")
                if path.is_dir():
                    shutil.rmtree(path)
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
