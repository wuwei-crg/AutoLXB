"""
Benchmark task definitions.
"""

from dataclasses import dataclass
from pathlib import Path


@dataclass
class BenchmarkTask:
    task_id: str
    app_name: str
    package: str
    map_path: str
    user_task: str
    target_page: str  # used as log identifier only
    depth: str        # "shallow" | "medium" | "deep"


_PROJECT_ROOT = Path(__file__).resolve().parents[1]


def _latest_map_for(app_dir: str, fallback: str) -> str:
    map_dir = _PROJECT_ROOT / "maps" / app_dir
    candidates = list(map_dir.glob("nav_map_*.json"))
    if not candidates:
        return fallback
    newest = max(candidates, key=lambda p: (p.stat().st_mtime, p.name))
    return newest.relative_to(_PROJECT_ROOT).as_posix()


# 淘宝使用最新建图结果
_TB_MAP = _latest_map_for(
    app_dir="com_taobao_taobao",
    fallback="maps/com_taobao_taobao/nav_map_20260225_144219.json",
)
_TB_PKG = "com.taobao.taobao"

_BILI_MAP = _latest_map_for(
    app_dir="tv_danmaku_bili",
    fallback="maps/tv_danmaku_bili/nav_map_20260225_151412.json",
)
_BILI_PKG = "tv.danmaku.bili"


TASKS: list[BenchmarkTask] = [
    # 淘宝：3浅层 + 3深层
    BenchmarkTask(
        task_id="tb_s1",
        app_name="淘宝",
        package=_TB_PKG,
        map_path=_TB_MAP,
        user_task="查看消息页面",
        target_page="message",
        depth="shallow",
    ),
    BenchmarkTask(
        task_id="tb_s3",
        app_name="淘宝",
        package=_TB_PKG,
        map_path=_TB_MAP,
        user_task="进入关注页面",
        target_page="follow",
        depth="shallow",
    ),
    BenchmarkTask(
        task_id="tb_d1",
        app_name="淘宝",
        package=_TB_PKG,
        map_path=_TB_MAP,
        user_task="查看我的订单列表",
        target_page="order",
        depth="deep",
    ),
    BenchmarkTask(
        task_id="tb_d2",
        app_name="淘宝",
        package=_TB_PKG,
        map_path=_TB_MAP,
        user_task="进入淘宝APP设置页面",
        target_page="settings",
        depth="deep",
    ),
    # B站：先保留原规模
    BenchmarkTask(
        task_id="bili_s1",
        app_name="B站",
        package=_BILI_PKG,
        map_path=_BILI_MAP,
        user_task="查看消息页面",
        target_page="message",
        depth="shallow",
    ),
    BenchmarkTask(
        task_id="bili_m1",
        app_name="B站",
        package=_BILI_PKG,
        map_path=_BILI_MAP,
        user_task="查看我收到的点赞",
        target_page="likes",
        depth="medium",
    ),
    BenchmarkTask(
        task_id="bili_m3",
        app_name="B站",
        package=_BILI_PKG,
        map_path=_BILI_MAP,
        user_task="查看每周必看视频列表",
        target_page="weekly_videos",
        depth="medium",
    ),
    BenchmarkTask(
        task_id="bili_d2",
        app_name="B站",
        package=_BILI_PKG,
        map_path=_BILI_MAP,
        user_task="进入鬼畜分区",
        target_page="music",
        depth="deep",
    ),
]
