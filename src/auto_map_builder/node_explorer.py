"""
LXB Auto Map Builder v5 - Node 驱动探索

核心思路：
1. 以 Node 为单位探索，不以页面为单位
2. 每次从首页开始，按路径到达目标节点
3. 不需要"返回"逻辑，不需要页面去重
4. 记录：node → 目的地语义描述

数据结构：
- NodeLocator: 节点定位器（resource_id, text, bounds）
- NodeTransition: 节点跳转记录（node → target_description）
- NavigationMap: 导航地图（所有节点的跳转关系）
"""

import json
import time
import base64
import hashlib
import threading
from concurrent.futures import ThreadPoolExecutor
from collections import deque
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple, Set, Callable
from enum import Enum
from io import BytesIO

try:
    from PIL import Image, ImageDraw, ImageFont
    HAS_PIL = True
except ImportError:
    HAS_PIL = False

from .vlm_engine import VLMEngine
from .models import ExplorationConfig


class ExplorationMode(Enum):
    """探索模式"""
    SERIAL = "serial"      # 串行：等待 VLM 返回再继续
    PARALLEL = "parallel"  # 并行：VLM 后台推理，继续探索下一个


class ExplorationStatus(Enum):
    IDLE = "idle"
    RUNNING = "running"
    PAUSED = "paused"
    STOPPING = "stopping"
    STOPPED = "stopped"
    COMPLETED = "completed"


@dataclass
class NodeLocator:
    """节点定位器 - 用于定位和标识 UI 元素"""
    resource_id: Optional[str] = None
    text: Optional[str] = None
    content_desc: Optional[str] = None
    class_name: Optional[str] = None
    parent_resource_id: Optional[str] = None
    bounds: Optional[Tuple[int, int, int, int]] = None

    def to_dict(self) -> dict:
        """转换为精简字典（只保留有值的字段）"""
        d = {}
        if self.resource_id:
            # 只保留 ID 部分
            rid = self.resource_id.split("/")[-1] if "/" in self.resource_id else self.resource_id
            d["resource_id"] = rid
        if self.text:
            d["text"] = self.text
        if self.content_desc:
            d["content_desc"] = self.content_desc
        if self.parent_resource_id:
            prid = self.parent_resource_id.split("/")[-1] if "/" in self.parent_resource_id else self.parent_resource_id
            d["parent_rid"] = prid
        # bounds 作为 hint 预留（用于自学习，不用于定位）
        if self.bounds and len(self.bounds) >= 4:
            d["bounds_hint"] = list(self.bounds)
        # class_name 不保存到 map
        return d

    def unique_key(self) -> str:
        """生成唯一标识"""
        if self.resource_id:
            rid = self.resource_id.split("/")[-1] if "/" in self.resource_id else self.resource_id
            return f"id:{rid}"
        if self.text and len(self.text) <= 20:
            return f"text:{self.text}"
        if self.content_desc and len(self.content_desc) <= 20:
            return f"desc:{self.content_desc}"
        if self.bounds:
            return f"bounds:{self.bounds}"
        return f"unknown:{id(self)}"

    def click_point(self) -> Optional[Tuple[int, int]]:
        """获取点击坐标（bounds 中心），仅作为 fallback"""
        if self.bounds and len(self.bounds) >= 4:
            return ((self.bounds[0] + self.bounds[2]) // 2,
                    (self.bounds[1] + self.bounds[3]) // 2)
        return None

    def find_queries(self) -> List[Tuple[int, str]]:
        """
        生成 find_node 查询参数列表（按优先级排序）

        Returns:
            [(match_type, query_string), ...] 优先级从高到低
            match_type 对应 constants.py 中的 MATCH_* 常量:
              0=MATCH_EXACT_TEXT, 1=MATCH_CONTAINS_TEXT, 2=MATCH_REGEX,
              3=MATCH_RESOURCE_ID, 4=MATCH_CLASS, 5=MATCH_DESCRIPTION
        """
        queries = []
        # 优先级1: resource_id（最稳定）
        if self.resource_id:
            queries.append((3, self.resource_id))  # MATCH_RESOURCE_ID
        # 优先级2: text（精确匹配）
        if self.text and len(self.text) <= 30:
            queries.append((0, self.text))  # MATCH_EXACT_TEXT
        # 优先级3: content_desc
        if self.content_desc and len(self.content_desc) <= 30:
            queries.append((5, self.content_desc))  # MATCH_DESCRIPTION
        return queries

    def compound_conditions(self, activity: str = None) -> List[Tuple[int, int, str]]:
        """
        生成复合查询条件列表 [(field, op, value), ...]

        field/op 常量对应 constants.py 中的 COMPOUND_FIELD_* / COMPOUND_OP_*:
          field: 0=TEXT, 1=RESOURCE_ID, 2=CONTENT_DESC, 3=CLASS_NAME,
                 4=PARENT_RESOURCE_ID, 5=ACTIVITY
          op:    0=EQUALS, 1=CONTAINS, 2=STARTS_WITH, 3=ENDS_WITH
        """
        conditions = []
        if self.resource_id:
            conditions.append((1, 0, self.resource_id))   # RESOURCE_ID, EQUALS
        if self.text:
            conditions.append((0, 0, self.text))           # TEXT, EQUALS
        if self.content_desc:
            conditions.append((2, 0, self.content_desc))   # CONTENT_DESC, EQUALS
        if self.class_name:
            conditions.append((3, 3, self.class_name.split(".")[-1]))  # CLASS, ENDS_WITH
        if self.parent_resource_id:
            conditions.append((4, 0, self.parent_resource_id))  # PARENT_RESOURCE_ID, EQUALS
        if activity:
            conditions.append((5, 0, activity))            # ACTIVITY, EQUALS
        return conditions

    @staticmethod
    def from_dict(d: dict) -> "NodeLocator":
        bounds = None
        if d.get("bounds"):
            bounds = tuple(d["bounds"])
        elif d.get("bounds_hint"):
            bounds = tuple(d["bounds_hint"])
        return NodeLocator(
            resource_id=d.get("resource_id"),
            text=d.get("text"),
            content_desc=d.get("content_desc"),
            class_name=d.get("class_name"),
            parent_resource_id=d.get("parent_rid"),
            bounds=bounds
        )

    def __hash__(self):
        return hash(self.unique_key())

    def __eq__(self, other):
        if not isinstance(other, NodeLocator):
            return False
        return self.unique_key() == other.unique_key()


@dataclass
class PageInfo:
    """页面信息"""
    page_id: str                    # 页面ID（如 home, search, profile）
    name: str                       # 页面名称（如 首页, 搜索页）
    description: str                # 功能描述
    features: List[str] = field(default_factory=list)  # 页面内功能列表

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "description": self.description,
            "features": self.features
        }


@dataclass
class Transition:
    """页面跳转"""
    from_page: str                  # 源页面ID
    to_page: str                    # 目标页面ID
    node_name: str                  # 节点名称（如 "搜索"）
    node_type: str                  # 节点类型（tab, jump, back, input）
    locator: NodeLocator            # 定位器

    def to_dict(self) -> dict:
        return {
            "from": self.from_page,
            "to": self.to_page,
            "action": {
                "type": "tap",
                "locator": self.locator.to_dict()
            },
            "description": f"点击{self.node_name}"
        }


@dataclass
class NavNode:
    """导航节点 - VLM 识别的可点击元素"""
    locator: NodeLocator
    name: str                       # 节点名称
    node_type: str = "jump"         # tab, jump, back, input
    target_page: str = ""           # 目标页面ID

    def to_dict(self) -> dict:
        return {
            "locator": self.locator.to_dict(),
            "name": self.name,
            "type": self.node_type,
            "target_page": self.target_page
        }

    @staticmethod
    def from_dict(d: dict) -> "NavNode":
        return NavNode(
            locator=NodeLocator.from_dict(d["locator"]),
            name=d.get("name", ""),
            node_type=d.get("type", "jump"),
            target_page=d.get("target_page", "")
        )


@dataclass
class PopupInfo:
    """
    弹窗/广告信息 - VLM 识别的干扰元素

    用于记录广告、弹窗、更新提示等需要关闭的元素。
    建图时发现后记录到 map，执行时自动检测并关闭。
    """
    popup_id: str                   # 唯一标识 (基于 locator 生成)
    popup_type: str                 # 类型: splash_ad, update, teen_mode, permission, rating, other
    description: str                # 描述 (如 "开屏广告-跳过按钮")
    close_locator: NodeLocator      # 关闭按钮的定位器
    trigger_locator: Optional[NodeLocator] = None  # 触发弹窗的元素 (可选)
    first_seen_page: str = ""       # 首次发现的页面
    hit_count: int = 0              # 命中次数

    def to_dict(self) -> dict:
        d = {
            "id": self.popup_id,
            "type": self.popup_type,
            "description": self.description,
            "close_locator": self.close_locator.to_dict()
        }
        if self.trigger_locator:
            d["trigger_locator"] = self.trigger_locator.to_dict()
        if self.first_seen_page:
            d["first_seen_page"] = self.first_seen_page
        return d

    @staticmethod
    def from_dict(d: dict) -> "PopupInfo":
        return PopupInfo(
            popup_id=d.get("id", ""),
            popup_type=d.get("type", "other"),
            description=d.get("description", ""),
            close_locator=NodeLocator.from_dict(d.get("close_locator", {})),
            trigger_locator=NodeLocator.from_dict(d["trigger_locator"]) if d.get("trigger_locator") else None,
            first_seen_page=d.get("first_seen_page", ""),
            hit_count=d.get("hit_count", 0)
        )

    def unique_key(self) -> str:
        """生成唯一标识"""
        return self.close_locator.unique_key()


@dataclass
class BlockInfo:
    """
    异常/拦截页面信息 - VLM 识别的阻断页面

    用于记录人机验证、风控拦截等需要特殊处理的页面。
    遇到这类页面时需要重启应用并重新探索触发该页面的节点。
    """
    block_type: str                 # 类型: captcha, risk_control, login_required, network_error, crash
    description: str                # 描述 (如 "滑块验证-向右滑动完成验证")
    activity: Optional[str] = None  # Activity 名称（用于快速识别）
    identifiers: List[str] = field(default_factory=list)  # 页面特征 resource_id 列表
    trigger_node: Optional[str] = None  # 触发该页面的节点名称
    trigger_locator: Optional[NodeLocator] = None  # 触发该页面的节点定位器
    hit_count: int = 0              # 命中次数

    def to_dict(self) -> dict:
        d = {
            "type": self.block_type,
            "description": self.description,
            "hit_count": self.hit_count
        }
        if self.activity:
            d["activity"] = self.activity
        if self.identifiers:
            d["identifiers"] = self.identifiers
        if self.trigger_node:
            d["trigger_node"] = self.trigger_node
        if self.trigger_locator:
            d["trigger_locator"] = self.trigger_locator.to_dict()
        return d

    @staticmethod
    def from_dict(d: dict) -> "BlockInfo":
        return BlockInfo(
            block_type=d.get("type", ""),
            description=d.get("description", ""),
            activity=d.get("activity"),
            identifiers=d.get("identifiers", []),
            trigger_node=d.get("trigger_node"),
            trigger_locator=NodeLocator.from_dict(d["trigger_locator"]) if d.get("trigger_locator") else None,
            hit_count=d.get("hit_count", 0)
        )

    def matches_xml(self, xml_nodes: List[Dict], activity: Optional[str] = None) -> bool:
        """
        检查当前 XML 是否匹配此 Block 页面

        匹配策略：
        1. 如果有 activity，先匹配 activity
        2. 检查 identifiers 中的 resource_id 是否存在
        """
        # Activity 匹配
        if self.activity and activity:
            if self.activity not in activity:
                return False

        # 如果没有 identifiers，只靠 activity 匹配
        if not self.identifiers:
            return bool(self.activity and activity and self.activity in activity)

        # 检查 identifiers（至少匹配一半）
        matched = 0
        xml_ids = set()
        for node in xml_nodes:
            rid = node.get("resource_id", "")
            if rid:
                # 只取 id 部分
                short_id = rid.split("/")[-1] if "/" in rid else rid
                xml_ids.add(short_id)

        for identifier in self.identifiers:
            if identifier in xml_ids:
                matched += 1

        # 至少匹配一半的 identifiers
        return matched >= len(self.identifiers) / 2


@dataclass
class ExploreTask:
    """探索任务"""
    locator: NodeLocator
    path: List[NodeLocator]
    name: str                       # 节点名称
    node_type: str = "jump"
    target_page: str = ""           # 预期目标页面
    from_page: str = ""             # 来源页面
    depth: int = 0


@dataclass
class PendingVLMTask:
    """等待 VLM 返回的任务"""
    node_key: str
    screenshot: bytes
    xml_nodes: List[Dict]
    path: List[NodeLocator]
    depth: int
    from_page: str = ""             # 来源页面
    node_name: str = ""             # 节点名称
    node_type: str = ""             # 节点类型
    expected_target: str = ""       # 预期目标页面
    future: Optional[object] = None


class NavigationMap:
    """
    导航地图 - 精简结构

    用于路径规划和指令生成
    """

    def __init__(self):
        self.package: str = ""
        self.pages: Dict[str, PageInfo] = {}          # page_id → PageInfo
        self.transitions: List[Transition] = []       # 跳转列表
        self.popups: Dict[str, PopupInfo] = {}        # popup_id → PopupInfo (弹窗/广告)
        self.blocks: List[BlockInfo] = []             # 异常页面记录
        self._explored_edges: Set[Tuple[str, str]] = set()  # 已探索的边 (from, to)

    def add_page(self, page: PageInfo):
        """添加页面"""
        if page.page_id not in self.pages:
            self.pages[page.page_id] = page

    def add_transition(self, trans: Transition):
        """添加跳转（去重）"""
        edge = (trans.from_page, trans.to_page)
        if edge not in self._explored_edges:
            self._explored_edges.add(edge)
            self.transitions.append(trans)

    def add_popup(self, popup: PopupInfo):
        """添加弹窗记录（去重）"""
        key = popup.unique_key()
        if key not in self.popups:
            self.popups[key] = popup
        else:
            # 已存在，增加命中计数
            self.popups[key].hit_count += 1

    def add_block(self, block: BlockInfo):
        """添加异常页面记录"""
        # 检查是否已存在相同类型和触发节点的记录
        for existing in self.blocks:
            if existing.block_type == block.block_type and existing.trigger_node == block.trigger_node:
                existing.hit_count += 1
                return
        self.blocks.append(block)

    def get_page(self, page_id: str) -> Optional[PageInfo]:
        return self.pages.get(page_id)

    def get_transitions_from(self, page_id: str) -> List[Transition]:
        """获取从某页面出发的所有跳转"""
        return [t for t in self.transitions if t.from_page == page_id]

    def get_stats(self) -> dict:
        return {
            "total_pages": len(self.pages),
            "total_transitions": len(self.transitions),
            "total_popups": len(self.popups),
            "total_blocks": len(self.blocks),
            "explored_nodes": len(self._explored_edges)
        }

    def to_dict(self) -> dict:
        return {
            "package": self.package,
            "pages": {pid: p.to_dict() for pid, p in self.pages.items()},
            "transitions": [t.to_dict() for t in self.transitions],
            "popups": [p.to_dict() for p in self.popups.values()],
            "blocks": [b.to_dict() for b in self.blocks]
        }

    def save(self, filepath: str):
        """保存到文件"""
        import os
        os.makedirs(os.path.dirname(filepath) or ".", exist_ok=True)
        with open(filepath, "w", encoding="utf-8") as f:
            json.dump(self.to_dict(), f, ensure_ascii=False, indent=2)


class NodeExplorer:
    """
    Node 驱动探索器

    核心逻辑：
    1. 分析首页，获取导航节点
    2. 每个节点作为独立任务
    3. 每次从首页开始，按路径到达，点击目标节点
    4. 记录目的地描述，发现新节点加入队列
    """

    _PROMPT_ANALYZE = '''分析这个 Android App 页面截图。

**屏幕分辨率: {width} x {height} 像素**

## 任务
1. **首先判断**：当前页面是否是异常页面（人机验证、风控拦截等）
2. **其次判断**：当前页面是否有**遮挡型弹窗**需要关闭
3. 识别当前页面类型，给出页面ID
4. 描述页面功能
5. 找出**页面跳转入口**和**输入框**

## 输出格式
```
BLOCK|类型|描述
POPUP|x|y|类型|描述
PAGE|页面ID|页面名称|功能描述|页面内功能列表
NAV|x|y|节点名称|类型|目标页面ID
```

## BLOCK 类型（异常/拦截页面，必须最先输出）
如果检测到以下情况，输出 BLOCK 行，**不要输出 PAGE 和 NAV**：
- `captcha`: 人机验证（滑块验证、图片验证、点选验证、拼图验证）
- `risk_control`: 风控拦截（账号异常、操作频繁、安全验证）
- `login_required`: 强制登录（必须登录才能继续）
- `network_error`: 网络错误页面
- `crash`: 应用崩溃/ANR 页面

**识别特征**：
- 滑块验证：有滑块、"向右滑动"、"拖动滑块"
- 图片验证：有多张图片、"点击包含XX的图片"
- 风控拦截：有"操作频繁"、"账号异常"、"安全验证"
- 强制登录：整个页面只有登录入口，无法跳过

## POPUP 类型（只识别遮挡型弹窗！）

**只识别以下弹窗**：
- 在屏幕**中央**弹出的对话框/弹窗
- **遮挡了主要内容**，必须关闭才能继续操作
- 有明显的关闭按钮（×、关闭、跳过、取消等）

**严格忽略以下内容（不要输出 POPUP）**：
- 屏幕边缘的小广告横幅（顶部/底部的条状广告）
- 悬浮的小图标、小按钮
- 不影响正常操作的角标、红点
- 页面内嵌的广告卡片

**弹窗类型**：
- `splash_ad`: 开屏广告（全屏，有"跳过"倒计时）
- `update`: 更新提示弹窗（中央弹出，有"暂不更新"）
- `teen_mode`: 青少年模式弹窗（中央弹出，有"我知道了"）
- `permission`: 权限请求弹窗（系统弹窗，有"拒绝"）
- `rating`: 评价提示弹窗（中央弹出，有"以后再说"）
- `dialog`: 其他中央弹出的对话框

**POPUP 坐标必须是关闭/跳过按钮的位置！**

## 页面ID规则
用小写英文，如：home, search, profile, settings, detail, list, login, chat, cart

## NAV 类型
- `tab`: 底部/顶部导航Tab
- `jump`: 跳转入口（搜索、设置等）
- `back`: 返回按钮
- `input`: 输入框

## 严格排除（不要输出为 NAV）
- 页面内操作（排序、筛选、收藏、分享）
- 列表项、卡片、商品

## 示例1：人机验证页面
```
BLOCK|captcha|滑块验证-向右滑动完成验证
```

## 示例2：有开屏广告（全屏遮挡）
```
POPUP|980|120|splash_ad|开屏广告-跳过按钮
```

## 示例3：有更新弹窗（中央弹出）
```
POPUP|540|1200|update|更新提示-以后再说按钮
PAGE|home|首页|浏览推荐内容|下拉刷新,内容卡片
NAV|{ex1_x}|{ex1_y}|首页|tab|home
```

## 示例4：正常页面（边缘有小广告但不输出 POPUP）
```
PAGE|home|首页|浏览推荐内容|下拉刷新,内容卡片,排序筛选
NAV|{ex1_x}|{ex1_y}|首页|tab|home
NAV|{ex2_x}|{ex2_y}|消息|tab|message
NAV|{ex3_x}|{ex3_y}|我的|tab|profile
NAV|540|80|搜索|jump|search
```

现在分析：'''

    def __init__(
        self,
        client,
        config: Optional[ExplorationConfig] = None,
        log_callback: Optional[Callable] = None
    ):
        self.client = client
        self.config = config or ExplorationConfig()
        self.log = log_callback or (lambda l, m, d=None: print(f"[{l}] {m}"))

        from .vlm_engine import get_config
        vlm_config = get_config()
        self.vlm = VLMEngine(vlm_config)

        self.nav_map = NavigationMap()
        self.pending_tasks: deque = deque()
        self.explored_keys: Set[str] = set()

        # 并行模式相关
        self._explore_mode = ExplorationMode.SERIAL
        self._vlm_executor: Optional[ThreadPoolExecutor] = None
        self._pending_vlm_tasks: List[PendingVLMTask] = []
        self._vlm_tasks_lock = threading.Lock()
        self._click_delay = 1.5  # 点击后等待时间（秒），让页面稳定

        self._status = ExplorationStatus.IDLE
        self._status_lock = threading.Lock()
        self._pause_event = threading.Event()
        self._pause_event.set()

        self._screen_width = 1080
        self._screen_height = 2400

        self._stats = {
            "total_actions": 0,
            "start_time": 0.0
        }

        self._realtime = {
            "current_node": None,
            "current_screenshot": None,
            "last_action": None
        }
        self._realtime_lock = threading.Lock()

    @property
    def status(self) -> ExplorationStatus:
        with self._status_lock:
            return self._status

    @status.setter
    def status(self, value: ExplorationStatus):
        with self._status_lock:
            self._status = value

    def pause(self):
        if self._status == ExplorationStatus.RUNNING:
            self._pause_event.clear()
            self.status = ExplorationStatus.PAUSED

    def resume(self):
        if self._status == ExplorationStatus.PAUSED:
            self.status = ExplorationStatus.RUNNING
            self._pause_event.set()

    def stop(self):
        if self._status in (ExplorationStatus.RUNNING, ExplorationStatus.PAUSED):
            self.status = ExplorationStatus.STOPPING
            self._pause_event.set()

    def set_mode(self, mode: str):
        """设置探索模式: 'serial' 或 'parallel'"""
        if mode == "parallel":
            self._explore_mode = ExplorationMode.PARALLEL
            self.log("info", "切换到并行探索模式")
        else:
            self._explore_mode = ExplorationMode.SERIAL
            self.log("info", "切换到串行探索模式")

    def set_click_delay(self, delay: float):
        """设置点击后等待时间（秒）"""
        self._click_delay = max(0.5, min(5.0, delay))
        self.log("info", f"点击延迟设置为 {self._click_delay}s")

    def _check_control(self) -> bool:
        if self._status == ExplorationStatus.STOPPING:
            return False
        if self._status == ExplorationStatus.PAUSED:
            self._pause_event.wait()
        return self._status != ExplorationStatus.STOPPING

    def get_realtime_state(self) -> dict:
        with self._realtime_lock:
            state = self._realtime.copy()
            state["status"] = self._status.value
            state["mode"] = self._explore_mode.value
            stats = self.nav_map.get_stats()

            # 并行模式：统计后台 VLM 任务数
            pending_vlm = 0
            with self._vlm_tasks_lock:
                pending_vlm = len(self._pending_vlm_tasks)

            state["stats"] = {
                **stats,
                "total_actions": self._stats["total_actions"],
                "queue_size": len(self.pending_tasks),
                "pending_vlm": pending_vlm,
                "elapsed": time.time() - self._stats["start_time"] if self._stats["start_time"] else 0
            }

            # 构建拓扑图数据
            graph_nodes = []
            edges = []

            # 页面作为节点
            for page_id, page in self.nav_map.pages.items():
                graph_nodes.append({
                    "id": page_id,
                    "type": page.name,
                    "desc": page.description[:50]
                })

            # 跳转作为边
            for trans in self.nav_map.transitions:
                edges.append({
                    "from": trans.from_page,
                    "to": trans.to_page,
                    "label": trans.node_name
                })

            state["graph"] = {
                "nodes": graph_nodes,
                "edges": edges
            }

            # Node 列表（显示待探索的任务）
            node_list = []
            for task in list(self.pending_tasks)[:20]:  # 只显示前20个
                node_list.append({
                    "node_key": task.locator.unique_key(),
                    "name": task.name,
                    "explored": False,
                    "from_page": task.from_page,
                    "target_page": task.target_page,
                    "depth": task.depth,
                    "type": task.node_type
                })

            # 已探索的跳转
            for trans in self.nav_map.transitions[-10:]:  # 最近10个
                node_list.append({
                    "node_key": f"{trans.from_page}→{trans.to_page}",
                    "name": trans.node_name,
                    "explored": True,
                    "from_page": trans.from_page,
                    "target_page": trans.to_page,
                    "depth": 0,
                    "type": trans.node_type
                })

            state["node_list"] = node_list

            return state

    # === 设备操作 ===

    def _get_screen_size(self):
        try:
            ok, w, h, _ = self.client.get_screen_size()
            if ok:
                self._screen_width, self._screen_height = w, h
                self.log("debug", f"获取屏幕尺寸成功: {w}x{h}")
            else:
                self.log("warn", "获取屏幕尺寸失败")
        except Exception as e:
            self.log("error", f"获取屏幕尺寸异常: {e}")

    def _screenshot(self) -> Optional[bytes]:
        try:
            data = self.client.request_screenshot()
            # 检查截图实际尺寸
            if data and HAS_PIL:
                from PIL import Image
                img = Image.open(BytesIO(data))
                img_w, img_h = img.size
                if img_w != self._screen_width or img_h != self._screen_height:
                    self.log("warn", f"截图尺寸 ({img_w}x{img_h}) != 屏幕尺寸 ({self._screen_width}x{self._screen_height})")
                    # 更新为截图的实际尺寸（VLM 看到的是截图）
                    self._screen_width, self._screen_height = img_w, img_h
            return data
        except Exception as e:
            self.log("error", f"截图失败: {e}")
            return None

    def _dump_actions(self) -> List[Dict]:
        try:
            return self.client.dump_actions().get("nodes", [])
        except:
            return []

    def _tap(self, x: int, y: int):
        self.client.tap(x, y)
        self._stats["total_actions"] += 1
        time.sleep(self.config.action_delay_ms / 1000)

    def _tap_node(self, locator: NodeLocator, label: str = "", activity: str = None) -> bool:
        """
        通过 find_node 定位并点击节点

        优先使用 find_node_compound（复合条件），fallback 到 find_node（单字段），
        最后 fallback 到 bounds 坐标。

        Args:
            locator: 节点定位器
            label: 日志标签
            activity: 当前 Activity 名称（可选，用于复合查询）

        Returns:
            True 如果成功点击，False 如果失败
        """
        desc = label or locator.unique_key()

        # 1. 尝试 find_node_compound（复合条件 >= 2 时使用）
        conditions = locator.compound_conditions(activity=activity)
        if len(conditions) >= 2:
            try:
                status, coords = self.client.find_node_compound(
                    conditions, return_mode=0, multi_match=False
                )
                if status == 1 and coords:
                    x, y = coords[0]
                    self.log("debug", f"    find_node_compound 成功: {desc} → ({x}, {y}) [{len(conditions)} 条件]")
                    self._tap(x, y)
                    return True
            except Exception as e:
                self.log("debug", f"    find_node_compound 异常: {desc} → {e}")

        # 2. fallback: find_node 逐个尝试（单字段）
        queries = locator.find_queries()
        for match_type, query in queries:
            try:
                status, coords = self.client.find_node(
                    query, match_type=match_type, return_mode=0, timeout_ms=3000
                )
                if status == 1 and coords:
                    x, y = coords[0]
                    self.log("debug", f"    find_node 成功: {desc} → ({x}, {y}) [query={query}]")
                    self._tap(x, y)
                    return True
            except Exception as e:
                self.log("debug", f"    find_node 异常: {query} → {e}")
                continue

        # 3. fallback: 使用 bounds 坐标
        click_point = locator.click_point()
        if click_point:
            self.log("warn", f"    find_node 失败，使用 bounds fallback: {desc} → {click_point}")
            self._tap(*click_point)
            return True

        self.log("warn", f"    无法定位节点: {desc}")
        return False

    def _back(self):
        """按返回键"""
        self.client.key_event(4)
        time.sleep(0.5)

    def _launch_app(self, package: str):
        """启动应用"""
        try:
            self.log("debug", f"launch_app: {package}")
            self.client.launch_app(package, clear_task=True)
            time.sleep(2)
        except Exception as e:
            self.log("error", f"启动应用异常: {e}")

    def _go_home(self, package: str):
        """
        回到首页

        策略：先尝试 Back 键，如果退出了 App 再 launch
        """
        # 检查当前是否在目标 App
        try:
            ok, current_pkg, _ = self.client.get_activity()
            if ok and current_pkg == package:
                # 在目标 App 内，尝试用 Back 键回首页
                for _ in range(5):
                    self._back()
                    ok, pkg, _ = self.client.get_activity()
                    if not ok or pkg != package:
                        # 退出了 App，重新 launch
                        break
                else:
                    # Back 了 5 次还在 App 内，可能已经在首页了
                    return
        except:
            pass

        # launch App
        self.client.launch_app(package, clear_task=True)
        time.sleep(2)

    def _is_nav_anchor(self, xml_node: Dict) -> bool:
        """
        判断节点是否是导航锚点（而不是列表项）

        导航锚点特征：
        - 位置在顶部或底部（导航栏区域）
        - 不包含列表项关键词
        """
        bounds = xml_node.get("bounds", [0, 0, 0, 0])
        if len(bounds) < 4:
            return False

        y_center = (bounds[1] + bounds[3]) // 2
        h = self._screen_height

        # 1. 位置检查：在顶部 20% 或底部 20%（放宽范围）
        is_top = y_center < h * 0.20
        is_bottom = y_center > h * 0.80

        # 2. resource_id 检查：排除列表项
        res_id = xml_node.get("resource_id", "").lower()
        class_name = xml_node.get("class_name", "").lower()

        # 列表项关键词（排除）
        list_keywords = ["item", "cell", "row", "entry", "holder"]
        is_list_item = any(kw in res_id or kw in class_name for kw in list_keywords)

        # 如果在导航区域，且不是列表项，就是锚点
        if (is_top or is_bottom) and not is_list_item:
            return True

        # 如果有明确的导航关键词，也认为是锚点
        nav_keywords = ["tab", "nav", "menu", "bar", "bottom", "home", "search"]
        is_nav = any(kw in res_id for kw in nav_keywords)
        if is_nav and not is_list_item:
            return True

        return False

    def _is_input_field(self, xml_node: Dict) -> bool:
        """判断是否是输入框"""
        class_name = xml_node.get("class_name", "").lower()
        res_id = xml_node.get("resource_id", "").lower()

        # EditText 类
        if "edittext" in class_name or "edit" in class_name:
            return True

        # 搜索框
        if "search" in res_id and ("input" in res_id or "edit" in res_id or "box" in res_id):
            return True

        # focusable + editable
        if xml_node.get("editable", False):
            return True

        return False

    # === VLM 分析 ===

    def _analyze_page(self, screenshot: bytes) -> Tuple[Optional[PageInfo], List[NavNode], List[PopupInfo], Optional[BlockInfo]]:
        """
        分析页面（支持并发推理）

        Returns:
            (页面信息, 导航节点列表, 弹窗列表, 异常页面信息)
        """
        try:
            w, h = self._screen_width, self._screen_height
            prompt = self._PROMPT_ANALYZE.format(
                width=w,
                height=h,
                bottom_y=int(h * 0.85),
                top_y=int(h * 0.15),
                # 示例坐标（基于实际分辨率）
                ex1_x=int(w * 0.125),  # 底部导航第1个
                ex1_y=int(h * 0.95),
                ex2_x=int(w * 0.375),  # 底部导航第2个
                ex2_y=int(h * 0.95),
                ex3_x=int(w * 0.2),    # 顶部Tab
                ex3_y=int(h * 0.06)
            )

            self.log("info", f"VLM 请求中 ({w}x{h}, {len(screenshot)} bytes)...")

            # 检查是否启用并发推理
            if self.vlm.config.concurrent_enabled:
                return self._analyze_page_concurrent(screenshot, prompt)
            else:
                response = self.vlm._call_api(screenshot, prompt)
                self.log("info", f"VLM 响应 ({len(response)} 字符)")
                self.log("debug", f"VLM 原始响应:\n{response[:800]}")
                page_info, nodes, popups, block_info = self._parse_response(response)
                self.log("info", f"解析结果: page={page_info.page_id if page_info else 'None'}, nodes={len(nodes)}, popups={len(popups)}, block={block_info.block_type if block_info else 'None'}")
                return page_info, nodes, popups, block_info
        except Exception as e:
            import traceback
            self.log("error", f"VLM 分析失败: {e}")
            self.log("error", traceback.format_exc())
            return None, [], [], None

    def _analyze_page_concurrent(self, screenshot: bytes, prompt: str) -> Tuple[Optional[PageInfo], List[NavNode], List[PopupInfo], Optional[BlockInfo]]:
        """
        并发推理分析页面

        多次调用 VLM，聚合结果，提高准确性
        """
        from concurrent.futures import ThreadPoolExecutor, as_completed
        import threading

        num_requests = self.vlm.config.concurrent_requests
        threshold = self.vlm.config.occurrence_threshold

        self.log("info", f"  并发推理: {num_requests} 次, 阈值 {threshold}")

        results = []
        lock = threading.Lock()

        def single_call(idx: int):
            try:
                response = self.vlm._call_api(screenshot, prompt)
                page_info, nodes, popups, block_info = self._parse_response(response)
                with lock:
                    results.append((page_info, nodes, popups, block_info))
                return True
            except Exception as e:
                self.log("debug", f"  并发推理 #{idx+1} 失败: {e}")
                return False

        # 并发执行
        with ThreadPoolExecutor(max_workers=min(num_requests, 10)) as executor:
            futures = [executor.submit(single_call, i) for i in range(num_requests)]
            for future in as_completed(futures):
                future.result()

        if not results:
            return None, [], [], None

        # 聚合异常页面信息（如果有任何一个检测到 BLOCK，就认为是异常页面）
        final_block = None
        block_counts = {}
        for _, _, _, block_info in results:
            if block_info:
                key = block_info.block_type
                if key not in block_counts:
                    block_counts[key] = (0, block_info)
                block_counts[key] = (block_counts[key][0] + 1, block_info)

        # 如果超过半数检测到同一类型的 BLOCK，就认为是异常页面
        for block_type, (count, block_info) in block_counts.items():
            if count >= num_requests // 2:
                final_block = block_info
                self.log("warn", f"  检测到异常页面: {block_type} ({count}/{num_requests})")
                break

        # 如果检测到异常页面，不需要聚合其他信息
        if final_block:
            # 但仍然聚合弹窗，因为可能需要先关闭弹窗
            all_popups = []
            for _, _, popups, _ in results:
                all_popups.extend(popups)
            aggregated_popups = self._aggregate_popups(all_popups, 1)
            return None, [], aggregated_popups, final_block

        # 聚合页面信息（取第一个有效的）
        final_page = None
        for page_info, _, _, _ in results:
            if page_info:
                final_page = page_info
                break

        # 聚合导航节点（按坐标分组，出现次数 >= threshold 的保留）
        all_nodes = []
        for _, nodes, _, _ in results:
            all_nodes.extend(nodes)

        aggregated_nodes = self._aggregate_nav_nodes(all_nodes, threshold)

        # 聚合弹窗（按坐标分组，出现次数 >= 1 就保留，因为弹窗很重要）
        all_popups = []
        for _, _, popups, _ in results:
            all_popups.extend(popups)

        aggregated_popups = self._aggregate_popups(all_popups, max(1, threshold - 1))

        self.log("info", f"  并发结果: {len(results)}/{num_requests} 成功, 聚合 {len(aggregated_nodes)} 节点, {len(aggregated_popups)} 弹窗")

        return final_page, aggregated_nodes, aggregated_popups, None

    def _aggregate_popups(self, popups: List[PopupInfo], threshold: int) -> List[PopupInfo]:
        """聚合多次推理的弹窗信息"""
        if not popups:
            return []

        # 按坐标分组（允许 100 像素误差，弹窗按钮位置可能有偏差）
        groups = []
        used = set()

        for i, popup in enumerate(popups):
            if i in used:
                continue

            if not popup.close_locator.bounds:
                continue

            x1, y1 = popup.close_locator.bounds[0], popup.close_locator.bounds[1]
            group = [popup]
            used.add(i)

            for j, other in enumerate(popups):
                if j in used or not other.close_locator.bounds:
                    continue

                x2, y2 = other.close_locator.bounds[0], other.close_locator.bounds[1]
                if abs(x1 - x2) < 100 and abs(y1 - y2) < 100:
                    group.append(other)
                    used.add(j)

            groups.append(group)

        # 过滤并聚合
        aggregated = []
        for group in groups:
            if len(group) < threshold:
                continue

            # 取平均坐标
            avg_x = sum(p.close_locator.bounds[0] for p in group) // len(group)
            avg_y = sum(p.close_locator.bounds[1] for p in group) // len(group)

            # 取最常见的类型
            types = [p.popup_type for p in group]
            most_common_type = max(set(types), key=types.count)

            # 取最常见的描述
            descs = [p.description for p in group]
            most_common_desc = max(set(descs), key=descs.count)

            aggregated.append(PopupInfo(
                popup_id=f"popup_{avg_x}_{avg_y}",
                popup_type=most_common_type,
                description=most_common_desc,
                close_locator=NodeLocator(bounds=(avg_x, avg_y, avg_x, avg_y))
            ))

        return aggregated

    def _aggregate_nav_nodes(self, nodes: List[NavNode], threshold: int) -> List[NavNode]:
        """
        聚合多次推理的导航节点

        按坐标分组，出现次数 >= threshold 的保留
        """
        if not nodes:
            return []

        # 按坐标分组（允许 50 像素误差）
        groups = []
        used = set()

        for i, node in enumerate(nodes):
            if i in used:
                continue

            if not node.locator.bounds:
                continue

            x1, y1 = node.locator.bounds[0], node.locator.bounds[1]
            group = [node]
            used.add(i)

            for j, other in enumerate(nodes):
                if j in used or not other.locator.bounds:
                    continue

                x2, y2 = other.locator.bounds[0], other.locator.bounds[1]
                # 距离小于 50 像素认为是同一个节点
                if abs(x1 - x2) < 50 and abs(y1 - y2) < 50:
                    group.append(other)
                    used.add(j)

            groups.append(group)

        # 过滤并聚合
        aggregated = []
        for group in groups:
            if len(group) < threshold:
                continue  # 出现次数不足，认为是噪声

            # 取平均坐标
            avg_x = sum(n.locator.bounds[0] for n in group) // len(group)
            avg_y = sum(n.locator.bounds[1] for n in group) // len(group)

            # 取最常见的名称
            names = [n.name for n in group]
            most_common_name = max(set(names), key=names.count)

            # 取最常见的类型
            types = [n.node_type for n in group]
            most_common_type = max(set(types), key=types.count)

            # 取最常见的目标页面
            targets = [n.target_page for n in group if n.target_page]
            most_common_target = max(set(targets), key=targets.count) if targets else ""

            aggregated.append(NavNode(
                locator=NodeLocator(bounds=(avg_x, avg_y, avg_x, avg_y)),
                name=most_common_name,
                node_type=most_common_type,
                target_page=most_common_target
            ))

        return aggregated

    def _parse_response(self, response: str) -> Tuple[Optional[PageInfo], List[NavNode], List[PopupInfo], Optional[BlockInfo]]:
        """解析 VLM 响应，返回 (页面信息, 导航节点列表, 弹窗列表, 异常页面信息)"""
        page_info = None
        nav_nodes = []
        popups = []
        block_info = None

        for line in response.strip().split("\n"):
            line = line.strip()
            if not line or line.startswith("```"):
                continue

            # BLOCK|类型|描述 (异常页面，最高优先级)
            if line.startswith("BLOCK|"):
                parts = line.split("|")
                if len(parts) >= 3:
                    block_type = parts[1].strip().lower()
                    description = parts[2].strip()
                    block_info = BlockInfo(
                        block_type=block_type,
                        description=description
                    )
                    # 遇到 BLOCK 后，后续的 PAGE 和 NAV 应该被忽略
                    # 但我们继续解析以防 VLM 输出了 POPUP（可能需要先关闭弹窗）

            # POPUP|x|y|类型|描述
            elif line.startswith("POPUP|"):
                parts = line.split("|")
                if len(parts) >= 4:
                    try:
                        x = int(parts[1].strip())
                        y = int(parts[2].strip())
                        popup_type = parts[3].strip().lower()
                        description = parts[4].strip() if len(parts) >= 5 else popup_type

                        # 创建 PopupInfo
                        close_locator = NodeLocator(bounds=(x, y, x, y))
                        popup = PopupInfo(
                            popup_id=f"popup_{x}_{y}",
                            popup_type=popup_type,
                            description=description,
                            close_locator=close_locator
                        )
                        popups.append(popup)
                    except ValueError:
                        continue

            # 新格式: PAGE|页面ID|页面名称|功能描述|功能列表
            elif line.startswith("PAGE|"):
                # 如果已经检测到 BLOCK，忽略 PAGE
                if block_info:
                    continue
                parts = line.split("|")
                if len(parts) >= 4:
                    page_id = parts[1].strip().lower()
                    name = parts[2].strip()
                    description = parts[3].strip()
                    features = parts[4].strip().split(",") if len(parts) >= 5 else []
                    features = [f.strip() for f in features if f.strip()]
                    page_info = PageInfo(
                        page_id=page_id,
                        name=name,
                        description=description,
                        features=features
                    )

            # NAV|x|y|节点名称|类型|目标页面ID
            elif line.startswith("NAV|"):
                # 如果已经检测到 BLOCK，忽略 NAV
                if block_info:
                    continue
                parts = line.split("|")
                if len(parts) >= 5:
                    try:
                        x = int(parts[1].strip())
                        y = int(parts[2].strip())
                        node_name = parts[3].strip()
                        node_type = parts[4].strip().lower()
                        target_page = parts[5].strip().lower() if len(parts) >= 6 else ""

                        # 创建 locator（暂时只有坐标，后面会匹配 XML）
                        locator = NodeLocator(bounds=(x, y, x, y))
                        nav_nodes.append(NavNode(
                            locator=locator,
                            name=node_name,
                            node_type=node_type,
                            target_page=target_page
                        ))
                    except ValueError:
                        continue

        return page_info, nav_nodes, popups, block_info

    def _match_xml_node(self, vlm_x: int, vlm_y: int, vlm_desc: str, xml_nodes: List[Dict]) -> Optional[Dict]:
        """
        用 VLM 坐标和描述匹配 XML 节点

        策略（优先级从高到低）：
        1. 文本完全匹配 + 在同一行（y 坐标接近）
        2. 文本包含匹配 + 在同一行
        3. 坐标在 bounds 内
        4. 距离最近
        """
        # 筛选候选节点：导航锚点 + 输入框
        candidates_pool = []
        for node in xml_nodes:
            bounds = node.get("bounds", [0, 0, 0, 0])
            if len(bounds) < 4:
                continue

            # 导航锚点（需要 clickable）
            if node.get("clickable", False) and self._is_nav_anchor(node):
                candidates_pool.append(node)
            # 输入框
            elif self._is_input_field(node):
                candidates_pool.append(node)

        if not candidates_pool:
            return None

        vlm_desc_lower = vlm_desc.lower().strip()

        # 策略1: 文本完全匹配 + y 坐标接近（同一行）
        for node in candidates_pool:
            text = (node.get("text") or node.get("content_desc") or "").lower().strip()
            if text and text == vlm_desc_lower:
                bounds = node.get("bounds")
                center_y = (bounds[1] + bounds[3]) // 2
                if abs(center_y - vlm_y) < 150:  # 同一行
                    self.log("debug", f"      匹配策略1: 文本完全匹配「{text}」")
                    return node

        # 策略2: 文本包含匹配 + y 坐标接近
        for node in candidates_pool:
            text = (node.get("text") or node.get("content_desc") or "").lower().strip()
            if text and (vlm_desc_lower in text or text in vlm_desc_lower):
                bounds = node.get("bounds")
                center_y = (bounds[1] + bounds[3]) // 2
                if abs(center_y - vlm_y) < 150:
                    self.log("debug", f"      匹配策略2: 文本包含匹配「{text}」")
                    return node

        # 策略3: 坐标在 bounds 内
        for node in candidates_pool:
            bounds = node.get("bounds")
            x1, y1, x2, y2 = bounds
            if x1 <= vlm_x <= x2 and y1 <= vlm_y <= y2:
                text = node.get("text") or node.get("content_desc") or ""
                self.log("debug", f"      匹配策略3: 坐标在bounds内「{text}」")
                return node

        # 策略4: 距离最近（限制在 200 像素内）
        candidates = []
        for node in candidates_pool:
            bounds = node.get("bounds")
            center_x = (bounds[0] + bounds[2]) // 2
            center_y = (bounds[1] + bounds[3]) // 2
            dist = ((vlm_x - center_x) ** 2 + (vlm_y - center_y) ** 2) ** 0.5
            if dist < 200:
                candidates.append((dist, node))

        if candidates:
            candidates.sort(key=lambda x: x[0])
            best_dist, best_node = candidates[0]
            text = best_node.get("text") or best_node.get("content_desc") or ""
            self.log("debug", f"      匹配策略4: 距离最近 {best_dist:.0f}px「{text}」")
            return best_node

        return None

    def _create_locator_from_xml(self, xml_node: Dict, xml_nodes: List[Dict] = None) -> NodeLocator:
        """从 XML 节点创建 Locator，可选从 xml_nodes 中查找父节点 resource_id"""
        parent_rid = None
        if xml_nodes:
            bounds = xml_node.get("bounds", [0, 0, 0, 0])
            if len(bounds) >= 4 and any(b > 0 for b in bounds):
                parent_rid = self._find_parent_resource_id(xml_node, xml_nodes)
        return NodeLocator(
            resource_id=xml_node.get("resource_id"),
            text=xml_node.get("text"),
            content_desc=xml_node.get("content_desc"),
            class_name=xml_node.get("class_name") or xml_node.get("class"),
            parent_resource_id=parent_rid,
            bounds=tuple(xml_node.get("bounds", [0, 0, 0, 0]))
        )

    def _find_parent_resource_id(self, child_node: Dict, xml_nodes: List[Dict]) -> Optional[str]:
        """从 xml_nodes 中找到包含 child_node 的最小父节点的 resource_id"""
        child_bounds = child_node.get("bounds", [0, 0, 0, 0])
        if len(child_bounds) < 4:
            return None
        cl, ct, cr, cb = child_bounds

        best_rid = None
        best_area = float('inf')

        for node in xml_nodes:
            if node is child_node:
                continue
            nb = node.get("bounds", [0, 0, 0, 0])
            if len(nb) < 4:
                continue
            nl, nt, nr, nb_ = nb
            # 父节点必须严格包含子节点
            if nl <= cl and nt <= ct and nr >= cr and nb_ >= cb and (nl < cl or nt < ct or nr > cr or nb_ > cb):
                area = (nr - nl) * (nb_ - nt)
                rid = node.get("resource_id") or node.get("resource-id", "")
                if rid and area < best_area:
                    best_area = area
                    best_rid = rid

        return best_rid

    # 垃圾功能关键词（运营塞进来的无用入口）
    _JUNK_KEYWORDS = [
        # 活动运营
        "活动", "福利", "红包", "抽奖", "签到", "任务", "积分", "金币",
        "领取", "免费", "特惠", "优惠", "促销", "限时", "新人", "首单",
        # 会员相关
        "vip", "会员", "开通", "升级", "特权", "尊享",
        # 游戏/娱乐
        "游戏", "小游戏", "游戏中心", "直播", "短视频",
        # 第三方服务
        "小程序", "服务", "生活服务", "本地服务",
        # 广告
        "广告", "推广", "赞助", "热门活动",
        # 其他垃圾
        "皮肤", "主题", "装扮", "表情", "贴纸",
    ]

    def _is_junk_entry(self, description: str) -> bool:
        """判断是否是垃圾功能入口"""
        desc_lower = description.lower()
        for kw in self._JUNK_KEYWORDS:
            if kw in desc_lower:
                return True
        return False

    def _enrich_nav_nodes(self, nav_nodes: List[NavNode], xml_nodes: List[Dict]) -> List[NavNode]:
        """
        用 XML 信息丰富导航节点

        只保留能匹配到 clickable 且是导航锚点的 XML 节点
        """
        enriched = []

        # 先过滤掉垃圾功能
        filtered_nodes = []
        for nav in nav_nodes:
            if self._is_junk_entry(nav.name):
                self.log("debug", f"    ✗ 过滤垃圾功能: {nav.name}")
            else:
                filtered_nodes.append(nav)

        self.log("debug", f"  VLM 节点: {len(nav_nodes)} 个, 过滤后: {len(filtered_nodes)} 个")

        # 统计并打印导航锚点
        nav_anchors = [n for n in xml_nodes if n.get("clickable", False) and self._is_nav_anchor(n)]
        self.log("debug", f"  XML 中有 {len(nav_anchors)} 个导航锚点:")
        for anchor in nav_anchors:
            bounds = anchor.get("bounds", [0, 0, 0, 0])
            center_x = (bounds[0] + bounds[2]) // 2
            center_y = (bounds[1] + bounds[3]) // 2
            text = anchor.get("text") or anchor.get("content_desc") or anchor.get("resource_id", "")[:20]
            self.log("debug", f"    - [{text}] 中心({center_x},{center_y})")

        for nav in filtered_nodes:
            # 获取 VLM 坐标
            if nav.locator.bounds:
                vlm_x, vlm_y = nav.locator.bounds[0], nav.locator.bounds[1]
            else:
                continue

            # 匹配 XML 节点（优先文本匹配，其次坐标匹配）
            xml_node = self._match_xml_node(vlm_x, vlm_y, nav.name, xml_nodes)
            if xml_node:
                xml_text = xml_node.get("text") or xml_node.get("content_desc") or ""
                # 用 XML 信息更新 locator
                nav.locator = self._create_locator_from_xml(xml_node, xml_nodes)
                new_center = nav.locator.click_point()
                self.log("info", f"    ✓ VLM「{nav.name}」({vlm_x},{vlm_y}) → XML「{xml_text}」{new_center}")
                enriched.append(nav)
            else:
                # 没匹配到，打印原因
                self.log("debug", f"    ✗ {nav.name}: VLM({vlm_x},{vlm_y}) 未匹配到导航锚点")

        self.log("info", f"  匹配结果: {len(enriched)}/{len(nav_nodes)} 个节点")
        return enriched

    def _enrich_popup_locators(self, popups: List[PopupInfo], xml_nodes: List[Dict]) -> List[PopupInfo]:
        """
        用 XML 信息丰富弹窗定位器

        弹窗关闭按钮的匹配策略：
        1. 坐标在 bounds 内的 clickable 节点
        2. 距离最近的 clickable 节点
        3. 文本匹配（跳过、关闭、×等）
        """
        enriched = []

        # 弹窗关闭按钮关键词
        close_keywords = [
            "跳过", "跳過", "skip", "关闭", "關閉", "close",
            "×", "✕", "✖", "x", "X",
            "以后再说", "暂不", "取消", "拒绝", "不允许",
            "我知道了", "知道了", "残忍拒绝"
        ]

        for popup in popups:
            if not popup.close_locator.bounds:
                continue

            vlm_x, vlm_y = popup.close_locator.bounds[0], popup.close_locator.bounds[1]
            self.log("debug", f"  弹窗「{popup.description}」VLM坐标: ({vlm_x}, {vlm_y})")

            # 获取所有 clickable 节点
            clickable_nodes = [n for n in xml_nodes if n.get("clickable", False)]

            best_match = None
            best_score = -1

            for node in clickable_nodes:
                bounds = node.get("bounds", [0, 0, 0, 0])
                if len(bounds) < 4:
                    continue

                x1, y1, x2, y2 = bounds
                center_x = (x1 + x2) // 2
                center_y = (y1 + y2) // 2

                score = 0

                # 策略1: 坐标在 bounds 内 (+100分)
                if x1 <= vlm_x <= x2 and y1 <= vlm_y <= y2:
                    score += 100

                # 策略2: 距离近 (+50分，距离越近分越高)
                dist = ((vlm_x - center_x) ** 2 + (vlm_y - center_y) ** 2) ** 0.5
                if dist < 150:
                    score += max(0, 50 - dist / 3)

                # 策略3: 文本匹配 (+80分)
                text = (node.get("text") or node.get("content_desc") or "").lower()
                for kw in close_keywords:
                    if kw.lower() in text:
                        score += 80
                        break

                # 策略4: resource_id 包含 close/skip (+60分)
                res_id = (node.get("resource_id") or "").lower()
                if "close" in res_id or "skip" in res_id or "dismiss" in res_id:
                    score += 60

                if score > best_score:
                    best_score = score
                    best_match = node

            if best_match and best_score >= 50:
                # 用 XML 信息更新 locator
                popup.close_locator = self._create_locator_from_xml(best_match, xml_nodes)
                xml_text = best_match.get("text") or best_match.get("content_desc") or best_match.get("resource_id", "")[:20]
                self.log("info", f"    ✓ 弹窗「{popup.description}」→ XML「{xml_text}」(score={best_score})")
                enriched.append(popup)
            else:
                # 没匹配到 XML，保留原始坐标
                self.log("debug", f"    ✗ 弹窗「{popup.description}」未匹配到XML，使用VLM坐标")
                enriched.append(popup)

        return enriched

    def _handle_popups(self, popups: List[PopupInfo], xml_nodes: List[Dict], current_page: str):
        """
        处理弹窗：点击关闭并记录到 map

        Args:
            popups: VLM 识别的弹窗列表
            xml_nodes: 当前页面的 XML 节点
            current_page: 当前页面ID
        """
        if not popups:
            return

        self.log("info", f"  检测到 {len(popups)} 个弹窗/广告")

        # 用 XML 丰富弹窗定位器
        enriched_popups = self._enrich_popup_locators(popups, xml_nodes)

        for popup in enriched_popups:
            popup.first_seen_page = current_page

            self.log("info", f"    关闭弹窗: {popup.popup_type} - {popup.description}")

            # 通过 find_node 定位并点击关闭按钮
            if not self._tap_node(popup.close_locator, label=f"弹窗关闭:{popup.description}"):
                self.log("warn", f"    弹窗「{popup.description}」无法定位关闭按钮")
                continue

            time.sleep(0.3)

            # 记录到 map
            self.nav_map.add_popup(popup)
            self.log("info", f"      已记录到 map (共 {len(self.nav_map.popups)} 个弹窗)")

    def _check_and_dismiss_popups(self, xml_nodes: List[Dict]) -> bool:
        """
        检查当前页面是否有已知弹窗，如果有则关闭

        用于执行操作前的弹窗检测（基于已记录的弹窗）

        Returns:
            True 如果关闭了弹窗，False 如果没有弹窗
        """
        if not self.nav_map.popups:
            return False

        dismissed = False

        for popup_key, popup in self.nav_map.popups.items():
            # 检查弹窗的 locator 是否在当前 XML 中存在
            locator = popup.close_locator

            for node in xml_nodes:
                matched = False

                # 匹配 resource_id
                if locator.resource_id:
                    node_rid = node.get("resource_id", "")
                    if locator.resource_id in node_rid or node_rid.endswith(locator.resource_id):
                        matched = True

                # 匹配 text
                if not matched and locator.text:
                    if node.get("text") == locator.text:
                        matched = True

                # 匹配 content_desc
                if not matched and locator.content_desc:
                    if node.get("content_desc") == locator.content_desc:
                        matched = True

                if matched and node.get("clickable", False):
                    # 找到匹配的弹窗，通过 find_node 点击关闭
                    self.log("info", f"  检测到已知弹窗「{popup.description}」，自动关闭")
                    if self._tap_node(locator, label=f"已知弹窗:{popup.description}"):
                        time.sleep(0.3)
                        popup.hit_count += 1
                        dismissed = True
                        break

            if dismissed:
                break

        return dismissed

    def _check_for_known_blocks(self, xml_nodes: List[Dict]) -> Optional[BlockInfo]:
        """
        检查当前页面是否是已知的 Block 页面

        Returns:
            匹配的 BlockInfo，或 None
        """
        if not self.nav_map.blocks:
            return None

        # 获取当前 activity
        current_activity = None
        try:
            ok, _, activity = self.client.get_activity()
            if ok:
                current_activity = activity
        except:
            pass

        for block in self.nav_map.blocks:
            if block.matches_xml(xml_nodes, current_activity):
                block.hit_count += 1
                return block

        return None

    def _extract_block_identifiers(self, xml_nodes: List[Dict]) -> List[str]:
        """
        从 XML 中提取 Block 页面的特征 identifiers

        提取策略：
        1. 优先提取包含关键词的 resource_id
        2. 提取所有非空的 resource_id（去重，最多 10 个）
        """
        # Block 页面关键词
        block_keywords = [
            "captcha", "verify", "slider", "puzzle", "code",
            "security", "risk", "check", "validate", "confirm",
            "login", "sign", "auth", "error", "retry"
        ]

        identifiers = []
        all_ids = []

        for node in xml_nodes:
            rid = node.get("resource_id", "")
            if not rid:
                continue

            # 只取 id 部分
            short_id = rid.split("/")[-1] if "/" in rid else rid
            if not short_id or short_id in all_ids:
                continue

            all_ids.append(short_id)

            # 优先添加包含关键词的
            rid_lower = short_id.lower()
            for kw in block_keywords:
                if kw in rid_lower:
                    identifiers.append(short_id)
                    break

        # 如果关键词匹配的不够，补充其他 id
        if len(identifiers) < 5:
            for rid in all_ids:
                if rid not in identifiers:
                    identifiers.append(rid)
                if len(identifiers) >= 10:
                    break

        return identifiers[:10]  # 最多 10 个

    # === 并行 VLM 推理 ===

    def _submit_vlm_task(self, node_key: str, screenshot: bytes, xml_nodes: List[Dict],
                         path: List[NodeLocator], depth: int,
                         from_page: str = "", node_name: str = "",
                         node_type: str = "", expected_target: str = ""):
        """提交 VLM 推理任务到后台"""
        if self._vlm_executor is None:
            return

        def vlm_work():
            try:
                page_info, nav_nodes, popups, block_info = self._analyze_page(screenshot)
                # 处理弹窗
                if popups:
                    enriched_popups = self._enrich_popup_locators(popups, xml_nodes)
                    for popup in enriched_popups:
                        popup.first_seen_page = from_page
                        self.nav_map.add_popup(popup)
                nav_nodes = self._enrich_nav_nodes(nav_nodes, xml_nodes)
                return (page_info, nav_nodes, popups, block_info)
            except Exception as e:
                self.log("error", f"VLM 后台推理失败: {e}")
                return (None, [], [], None)

        future = self._vlm_executor.submit(vlm_work)
        task = PendingVLMTask(
            node_key=node_key,
            screenshot=screenshot,
            xml_nodes=xml_nodes,
            path=path,
            depth=depth,
            from_page=from_page,
            node_name=node_name,
            node_type=node_type,
            expected_target=expected_target,
            future=future
        )

        with self._vlm_tasks_lock:
            self._pending_vlm_tasks.append(task)

        self.log("debug", f"  VLM 任务已提交后台: {node_key}")

    def _process_completed_vlm_tasks(self):
        """处理已完成的 VLM 任务"""
        completed = []

        with self._vlm_tasks_lock:
            remaining = []
            for task in self._pending_vlm_tasks:
                if task.future and task.future.done():
                    completed.append(task)
                else:
                    remaining.append(task)
            self._pending_vlm_tasks = remaining

        for task in completed:
            try:
                result = task.future.result(timeout=0.1)
                new_page, new_nav_nodes, new_popups, block_info = result

                # 检测到异常页面（人机验证等）
                if block_info:
                    self.log("warn", f"  [VLM完成] {task.node_name} → 异常页面: {block_info.block_type}")
                    block_info.trigger_node = task.node_name
                    self.nav_map.add_block(block_info)
                    # 并行模式下无法重新探索，只记录
                    continue

                # 确定目标页面ID
                if new_page:
                    target_page_id = new_page.page_id
                    self.nav_map.add_page(new_page)
                    self.log("info", f"  [VLM完成] {task.node_name} → {new_page.name} ({target_page_id})")
                else:
                    target_page_id = task.expected_target or "unknown"
                    self.log("info", f"  [VLM完成] {task.node_name} → {target_page_id}")

                self.log("info", f"    发现 {len(new_nav_nodes)} 个导航节点, {len(new_popups)} 个弹窗")

                # 添加跳转记录
                trans = Transition(
                    from_page=task.from_page,
                    to_page=target_page_id,
                    node_name=task.node_name,
                    node_type=task.node_type,
                    locator=NodeLocator()  # 需要从 task 恢复
                )
                self.nav_map.add_transition(trans)

                # 新节点加入队列
                for nav in new_nav_nodes:
                    new_key = nav.locator.unique_key()
                    if new_key not in self.explored_keys:
                        new_task = ExploreTask(
                            locator=nav.locator,
                            path=task.path,
                            name=nav.name,
                            node_type=nav.node_type,
                            target_page=nav.target_page,
                            from_page=target_page_id,
                            depth=task.depth + 1
                        )
                        self.pending_tasks.append(new_task)

                # 更新实时状态
                with self._realtime_lock:
                    self._realtime["current_screenshot"] = base64.b64encode(task.screenshot).decode()
                    self._realtime["last_action"] = f"{task.node_name} → {target_page_id}"

            except Exception as e:
                self.log("error", f"处理 VLM 结果失败: {e}")

        return len(completed)

    def _wait_all_vlm_tasks(self):
        """等待所有 VLM 任务完成"""
        with self._vlm_tasks_lock:
            pending_count = len(self._pending_vlm_tasks)

        if pending_count == 0:
            return

        self.log("info", f"等待 {pending_count} 个 VLM 任务完成...")

        while True:
            with self._vlm_tasks_lock:
                if not self._pending_vlm_tasks:
                    break

            self._process_completed_vlm_tasks()
            time.sleep(0.5)

        self.log("info", "所有 VLM 任务已完成")

    # === 可视化 ===

    def _mark_point(self, screenshot: bytes, x: int, y: int, label: str = "") -> bytes:
        """在截图上标记点击位置"""
        if not HAS_PIL:
            return screenshot

        try:
            img = Image.open(BytesIO(screenshot))
            draw = ImageDraw.Draw(img)

            size = 30
            color = (255, 0, 0)
            width = 3

            draw.line([(x - size, y), (x + size, y)], fill=color, width=width)
            draw.line([(x, y - size), (x, y + size)], fill=color, width=width)
            draw.ellipse([(x - size//2, y - size//2), (x + size//2, y + size//2)],
                        outline=color, width=width)

            if label:
                try:
                    font = ImageFont.truetype("arial.ttf", 24)
                except:
                    font = ImageFont.load_default()
                draw.text((x + size, y - 12), label, fill=color, font=font)

            output = BytesIO()
            img.save(output, format='PNG')
            return output.getvalue()
        except:
            return screenshot

    # === 探索逻辑 ===

    def explore(self, package_name: str) -> dict:
        """执行探索"""
        from concurrent.futures import ThreadPoolExecutor

        self.status = ExplorationStatus.RUNNING
        self._stats["start_time"] = time.time()

        self.log("info", "=" * 50)
        mode_str = "并行" if self._explore_mode == ExplorationMode.PARALLEL else "串行"
        self.log("info", f"[v5] Node 驱动探索 ({mode_str}模式): {package_name}")
        self.log("info", "=" * 50)

        self.nav_map = NavigationMap()
        self.nav_map.package = package_name
        self.pending_tasks = deque()
        self.explored_keys = set()

        # 并行模式：初始化线程池
        if self._explore_mode == ExplorationMode.PARALLEL:
            self._vlm_executor = ThreadPoolExecutor(max_workers=5)
            self._pending_vlm_tasks = []
            self.log("info", f"并行模式: 点击延迟 {self._click_delay}s, VLM 线程池 5")
        else:
            self._vlm_executor = None

        try:
            self._get_screen_size()
            self.log("info", f"屏幕: {self._screen_width}x{self._screen_height}")

            # 启动应用
            self.log("info", f"启动应用: {package_name}")
            self._launch_app(package_name)

            if not self._check_control():
                return self._build_result(package_name)

            # 分析首页
            self.log("info", "分析首页...")
            screenshot = self._screenshot()
            if not screenshot:
                self.log("error", "首页截图失败")
                self.status = ExplorationStatus.STOPPED
                return self._build_result(package_name)

            xml_nodes = self._dump_actions()
            self.log("info", f"XML 节点数: {len(xml_nodes)}")

            self.log("info", "调用 VLM 分析...")
            home_page, home_nav_nodes, home_popups, home_block = self._analyze_page(screenshot)

            # 首页检测到异常页面（极少见，但可能有）
            if home_block:
                self.log("error", f"首页检测到异常页面: {home_block.block_type} - {home_block.description}")
                self.log("error", "无法继续探索，请检查应用状态")
                self.nav_map.add_block(home_block)
                self.status = ExplorationStatus.STOPPED
                return self._build_result(package_name)

            if home_page:
                self.log("info", f"VLM 返回: page={home_page.page_id}({home_page.name}), 原始节点={len(home_nav_nodes)}, 弹窗={len(home_popups)}")
            else:
                self.log("warn", "VLM 未返回页面信息")
                home_page = PageInfo(page_id="home", name="首页", description="应用首页", features=[])

            # 处理首页弹窗
            if home_popups:
                self._handle_popups(home_popups, xml_nodes, home_page.page_id)
                # 弹窗关闭后重新截图分析
                time.sleep(0.5)
                screenshot = self._screenshot()
                if screenshot:
                    xml_nodes = self._dump_actions()
                    home_page, home_nav_nodes, more_popups, _ = self._analyze_page(screenshot)
                    if more_popups:
                        self._handle_popups(more_popups, xml_nodes, home_page.page_id if home_page else "home")

            # 打印原始节点
            for nav in home_nav_nodes:
                self.log("debug", f"  原始: {nav.name} [{nav.node_type}] → {nav.target_page} @ {nav.locator.bounds}")

            # 用 XML 丰富导航节点
            home_nav_nodes = self._enrich_nav_nodes(home_nav_nodes, xml_nodes)

            # 添加首页到 map
            self.nav_map.add_page(home_page)
            self.log("info", f"首页: {home_page.name} - {home_page.description}")
            self.log("info", f"导航节点: {len(home_nav_nodes)} 个")

            if not home_nav_nodes:
                self.log("warn", "首页未发现导航节点，探索结束")
                self.status = ExplorationStatus.COMPLETED
                return self._build_result(package_name)

            for nav in home_nav_nodes:
                self.log("info", f"  - {nav.name} [{nav.node_type}] → {nav.target_page}")

            # 首页节点加入队列
            for nav in home_nav_nodes:
                task = ExploreTask(
                    locator=nav.locator,
                    path=[],
                    name=nav.name,
                    node_type=nav.node_type,
                    target_page=nav.target_page,
                    from_page=home_page.page_id,
                    depth=0
                )
                self.pending_tasks.append(task)

            # 主循环：探索每个节点
            task_count = 0
            while True:
                # 并行模式：检查是否还有工作要做
                if self._explore_mode == ExplorationMode.PARALLEL:
                    # 处理已完成的 VLM 任务（可能会添加新节点到队列）
                    completed = self._process_completed_vlm_tasks()
                    if completed > 0:
                        self.log("debug", f"  处理了 {completed} 个 VLM 结果")

                    # 检查是否还有待处理的任务
                    with self._vlm_tasks_lock:
                        has_pending_vlm = len(self._pending_vlm_tasks) > 0

                    # 如果队列空了但还有 VLM 任务在跑，等一下
                    if not self.pending_tasks and has_pending_vlm:
                        self.log("debug", "  队列空，等待 VLM 任务...")
                        time.sleep(0.5)
                        continue

                    # 如果队列空了且没有 VLM 任务，结束
                    if not self.pending_tasks and not has_pending_vlm:
                        break
                else:
                    # 串行模式：队列空就结束
                    if not self.pending_tasks:
                        break

                if not self._check_control():
                    break

                if self._stats["total_actions"] >= self.config.max_pages * 10:
                    self.log("info", "达到最大动作数")
                    break

                elapsed = time.time() - self._stats["start_time"]
                if elapsed >= self.config.max_time_seconds:
                    self.log("info", "达到时间限制")
                    break

                # 队列可能在等待 VLM 时被填充，再检查一次
                if not self.pending_tasks:
                    continue

                task = self.pending_tasks.popleft()
                node_key = task.locator.unique_key()

                # 检查是否已探索
                if node_key in self.explored_keys:
                    continue

                # 深度限制
                if task.depth >= self.config.max_depth:
                    self.log("debug", f"跳过深度超限: {task.name}")
                    continue

                task_count += 1
                self.explored_keys.add(node_key)

                self.log("info", "")
                self.log("info", f"━━━ [{task_count}] {task.name} (深度{task.depth}) ━━━")
                self.log("info", f"  {task.from_page} → {task.target_page}")

                # 回到首页（优先用 Back，避免 launch 导致卡死）
                self._go_home(package_name)

                # 按路径到达
                if task.path:
                    self.log("info", f"  重放路径 ({len(task.path)} 步)...")
                    path_ok = True
                    for i, step in enumerate(task.path):
                        self.log("debug", f"    步骤 {i+1}: {step.unique_key()}")
                        if not self._tap_node(step, label=f"路径步骤{i+1}"):
                            self.log("warn", f"    步骤 {i+1}: 无法定位节点，路径中断")
                            path_ok = False
                            break
                    if not path_ok:
                        self.log("warn", f"  路径重放失败，跳过")
                        continue

                # 操作前检测已知弹窗
                pre_xml = self._dump_actions()
                if self._check_and_dismiss_popups(pre_xml):
                    # 弹窗被关闭，重新获取 XML
                    pre_xml = self._dump_actions()

                # 点击目标节点（通过 find_node 定位）
                self.log("info", f"  点击目标: {task.name} [{task.locator.unique_key()}]")

                # 获取当前 activity（用于复合查询）
                current_activity = None
                try:
                    ok, _, act = self.client.get_activity()
                    if ok:
                        current_activity = act
                except:
                    pass

                # 截图并标记（点击前）
                screenshot = self._screenshot()
                click_point = task.locator.click_point()  # 仅用于截图标记
                if screenshot and click_point:
                    marked = self._mark_point(screenshot, click_point[0], click_point[1], task.name)
                    with self._realtime_lock:
                        self._realtime["current_screenshot"] = base64.b64encode(marked).decode()
                        self._realtime["current_node"] = task.name

                # 执行点击（compound 优先，find_node fallback，bounds fallback）
                if not self._tap_node(task.locator, label=task.name, activity=current_activity):
                    self.log("warn", f"  无法定位目标节点，跳过")
                    continue

                # 等待页面响应（使用配置的延迟时间）
                time.sleep(self._click_delay)

                # ============================================================
                # 点击后先用 XML 检查：1. 已知弹窗 2. 已知 Block 页面
                # ============================================================
                new_xml = self._dump_actions()

                # 检查已知 Block 页面
                known_block = self._check_for_known_blocks(new_xml)
                if known_block:
                    self.log("warn", f"  ⚠ 检测到已知异常页面: {known_block.block_type} - {known_block.description}")
                    # 重启应用并重新探索
                    self.log("info", f"  重启应用并重新探索节点: {task.name}")
                    self._launch_app(package_name)
                    time.sleep(2)
                    # 将该节点重新加入队列
                    self.explored_keys.discard(node_key)
                    task.depth += 1
                    if task.depth < self.config.max_depth + 2:
                        self.pending_tasks.appendleft(task)
                    continue

                # 检查已知弹窗并关闭
                popup_dismissed = False
                dismiss_attempts = 0
                while dismiss_attempts < 3:  # 最多尝试关闭 3 次
                    if self._check_and_dismiss_popups(new_xml):
                        popup_dismissed = True
                        time.sleep(0.3)
                        new_xml = self._dump_actions()
                        dismiss_attempts += 1
                    else:
                        break

                if popup_dismissed:
                    self.log("info", f"  已关闭 {dismiss_attempts} 个已知弹窗")

                # 弹窗关闭后重新截图
                new_screenshot = self._screenshot()
                if not new_screenshot:
                    continue

                new_path = task.path + [task.locator]

                # 根据模式处理 VLM 分析
                if self._explore_mode == ExplorationMode.PARALLEL:
                    # 并行模式：提交到后台，不等待
                    self._submit_vlm_task(
                        node_key, new_screenshot, new_xml, new_path, task.depth,
                        from_page=task.from_page, node_name=task.name,
                        node_type=task.node_type, expected_target=task.target_page
                    )
                    self.log("info", f"  VLM 分析已提交后台")

                    # 更新实时截图
                    with self._realtime_lock:
                        self._realtime["current_screenshot"] = base64.b64encode(new_screenshot).decode()
                        self._realtime["last_action"] = f"{task.name} → (分析中...)"
                else:
                    # 串行模式：VLM 分析
                    new_page, new_nav_nodes, new_popups, block_info = self._analyze_page(new_screenshot)

                    # ============================================================
                    # VLM 检测到新异常页面 → 提取特征 + 记录 + 重启 + 重新探索
                    # ============================================================
                    if block_info:
                        self.log("warn", f"  ⚠ VLM 检测到新异常页面: {block_info.block_type} - {block_info.description}")

                        # 提取页面特征用于后续快速识别
                        block_info.identifiers = self._extract_block_identifiers(new_xml)
                        try:
                            ok, _, activity = self.client.get_activity()
                            if ok:
                                block_info.activity = activity
                        except:
                            pass

                        block_info.trigger_node = task.name
                        block_info.trigger_locator = task.locator
                        self.nav_map.add_block(block_info)

                        self.log("info", f"    特征: activity={block_info.activity}, ids={block_info.identifiers[:5]}")

                        # 重启应用
                        self.log("info", f"  重启应用并重新探索节点: {task.name}")
                        self._launch_app(package_name)
                        time.sleep(2)

                        # 将该节点重新加入队列（移除已探索标记）
                        self.explored_keys.discard(node_key)
                        task.depth += 1
                        if task.depth < self.config.max_depth + 2:
                            self.pending_tasks.appendleft(task)
                        continue

                    # ============================================================
                    # 检测到新弹窗（VLM 发现的）→ 记录 + 关闭 + 重新探索该节点
                    # ============================================================
                    if new_popups:
                        self.log("info", f"  VLM 检测到 {len(new_popups)} 个新弹窗，记录并关闭")
                        self._handle_popups(new_popups, new_xml, task.from_page)

                        # 重启应用并重新探索该节点
                        self.log("info", f"  重启应用并重新探索节点: {task.name}")
                        self._launch_app(package_name)
                        time.sleep(2)

                        # 将该节点重新加入队列（移除已探索标记）
                        self.explored_keys.discard(node_key)
                        task.depth += 1
                        if task.depth < self.config.max_depth + 2:
                            self.pending_tasks.appendleft(task)
                        continue

                    # ============================================================
                    # 正常页面 → 记录跳转 + 发现新节点
                    # ============================================================
                    new_nav_nodes = self._enrich_nav_nodes(new_nav_nodes, new_xml)

                    # 确定目标页面ID
                    if new_page:
                        target_page_id = new_page.page_id
                        self.nav_map.add_page(new_page)
                        self.log("info", f"  → {new_page.name} ({new_page.page_id})")
                    else:
                        target_page_id = task.target_page or "unknown"
                        self.log("info", f"  → {target_page_id}")

                    self.log("info", f"    发现 {len(new_nav_nodes)} 个导航节点")

                    # 添加跳转记录
                    trans = Transition(
                        from_page=task.from_page,
                        to_page=target_page_id,
                        node_name=task.name,
                        node_type=task.node_type,
                        locator=task.locator
                    )
                    self.nav_map.add_transition(trans)

                    # 新节点加入队列
                    for nav in new_nav_nodes:
                        new_key = nav.locator.unique_key()
                        if new_key not in self.explored_keys:
                            new_task = ExploreTask(
                                locator=nav.locator,
                                path=new_path,
                                name=nav.name,
                                node_type=nav.node_type,
                                target_page=nav.target_page,
                                from_page=target_page_id,
                                depth=task.depth + 1
                            )
                            self.pending_tasks.append(new_task)

                    with self._realtime_lock:
                        self._realtime["current_screenshot"] = base64.b64encode(new_screenshot).decode()
                        self._realtime["last_action"] = f"{task.name} → {target_page_id}"

            # 并行模式：等待所有 VLM 任务完成
            if self._explore_mode == ExplorationMode.PARALLEL:
                self._wait_all_vlm_tasks()
                if self._vlm_executor:
                    self._vlm_executor.shutdown(wait=False)
                    self._vlm_executor = None

            # 完成
            elapsed = time.time() - self._stats["start_time"]
            stats = self.nav_map.get_stats()

            self.log("info", "")
            self.log("info", "=" * 50)
            self.log("info", "探索完成!")
            self.log("info", f"页面: {stats['total_pages']}, 跳转: {stats['total_transitions']}")
            self.log("info", f"弹窗: {stats['total_popups']}, 异常页面: {stats['total_blocks']}")
            self.log("info", f"动作: {self._stats['total_actions']}, 耗时: {elapsed:.1f}s")
            self.log("info", "=" * 50)

            self.status = ExplorationStatus.COMPLETED if self._status != ExplorationStatus.STOPPING else ExplorationStatus.STOPPED
            return self._build_result(package_name)

        except Exception as e:
            import traceback
            self.log("error", f"探索异常: {e}")
            self.log("error", f"异常堆栈:\n{traceback.format_exc()}")
            self.status = ExplorationStatus.STOPPED
            return self._build_result(package_name)

    def _build_result(self, package: str) -> dict:
        """构建结果"""
        return {
            "package": package,
            "nav_map": self.nav_map,
            "exploration_time_seconds": time.time() - self._stats["start_time"],
            "total_actions": self._stats["total_actions"],
            **self.nav_map.get_stats()
        }
