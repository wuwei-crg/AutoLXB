"""
LXB Auto Map Builder v2 - 数据结构定义

核心数据结构：
- VLMDetection / VLMPageResult: VLM 推理结果
- XMLNode: XML UI 节点
- FusedNode: 融合节点 (XML + VLM)
- PageState: 页面状态
- Transition: 页面跳转
- ExplorationConfig / ExplorationResult: 探索配置与结果
"""

from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple
from enum import Enum

# 类型别名
BBox = Tuple[int, int, int, int]  # (left, top, right, bottom)


class ActionType(Enum):
    """动作类型"""
    TAP = "tap"
    LONG_PRESS = "long_press"
    SWIPE = "swipe"
    INPUT_TEXT = "input_text"
    KEY_BACK = "key_back"


# =============================================================================
# VLM 相关数据结构
# =============================================================================

@dataclass
class VLMDetection:
    """VLM 单个检测结果"""
    bbox: BBox                          # 检测框 [left, top, right, bottom]
    label: str                          # OD 标签 (e.g., "button", "icon", "text")
    confidence: float = 1.0             # 置信度 [0, 1]
    ocr_text: Optional[str] = None      # OCR 识别文本


@dataclass
class VLMPageResult:
    """VLM 页面级推理结果"""
    page_caption: str                   # 整页自然语言描述
    detections: List[VLMDetection]      # 所有检测结果
    inference_time_ms: float = 0.0      # 推理耗时
    image_size: Tuple[int, int] = (0, 0)  # 图像尺寸 (width, height)

    # 并发推理信息
    concurrent_enabled: bool = False    # 是否使用了并发推理
    concurrent_requests: int = 0        # 并发请求数量
    concurrent_results: int = 0         # 成功的并发结果数量
    aggregated_count: int = 0           # 聚合后的检测数量


# =============================================================================
# XML 节点 (来自 dump_actions)
# =============================================================================

@dataclass
class XMLNode:
    """XML UI 节点 (来自 LXB-Link dump_actions)"""
    node_id: str                        # 唯一标识
    bounds: BBox                        # 边界框
    class_name: str                     # 类名 (e.g., "android.widget.Button")
    text: str = ""                      # 文本内容
    resource_id: str = ""               # 资源 ID
    content_desc: str = ""              # 内容描述
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False

    @property
    def center(self) -> Tuple[int, int]:
        """计算中心点坐标"""
        return (
            (self.bounds[0] + self.bounds[2]) // 2,
            (self.bounds[1] + self.bounds[3]) // 2
        )

    @property
    def area(self) -> int:
        """计算面积"""
        return (self.bounds[2] - self.bounds[0]) * (self.bounds[3] - self.bounds[1])


# =============================================================================
# 融合节点 (XML + VLM)
# =============================================================================

@dataclass
class FusedNode:
    """融合后的节点 (XML 结构 + VLM 语义)"""
    node_id: str                        # 唯一标识
    bounds: BBox                        # 边界框 (来自 XML)

    # XML 属性
    class_name: str = ""
    text: str = ""
    resource_id: str = ""
    content_desc: str = ""
    clickable: bool = False
    editable: bool = False
    scrollable: bool = False

    # VLM 增强属性
    vlm_label: Optional[str] = None     # VLM 检测标签
    vlm_caption: Optional[str] = None   # VLM 区域描述
    vlm_ocr_text: Optional[str] = None  # VLM OCR 文本
    iou_score: float = 0.0              # IoU 匹配分数

    @property
    def center(self) -> Tuple[int, int]:
        """计算中心点坐标"""
        return (
            (self.bounds[0] + self.bounds[2]) // 2,
            (self.bounds[1] + self.bounds[3]) // 2
        )

    @property
    def semantic_text(self) -> str:
        """获取最佳语义文本 (优先级: text > vlm_ocr > content_desc)"""
        return self.text or self.vlm_ocr_text or self.content_desc or ""

    @property
    def description(self) -> str:
        """获取节点描述 (用于 LLM 理解)"""
        parts = []
        if self.vlm_label:
            parts.append(f"[{self.vlm_label}]")
        if self.semantic_text:
            parts.append(f'"{self.semantic_text}"')
        if self.vlm_caption:
            parts.append(f"({self.vlm_caption})")
        return " ".join(parts) or self.class_name.split(".")[-1]

    @classmethod
    def from_xml_node(cls, xml_node: XMLNode) -> "FusedNode":
        """从 XMLNode 创建 FusedNode"""
        return cls(
            node_id=xml_node.node_id,
            bounds=xml_node.bounds,
            class_name=xml_node.class_name,
            text=xml_node.text,
            resource_id=xml_node.resource_id,
            content_desc=xml_node.content_desc,
            clickable=xml_node.clickable,
            editable=xml_node.editable,
            scrollable=xml_node.scrollable
        )


# =============================================================================
# 页面状态
# =============================================================================

@dataclass
class PageState:
    """页面状态"""
    page_id: str                        # 页面唯一标识: {activity_short}_{hash[:8]}
    activity: str                       # Activity 全名
    package: str                        # 包名

    # 页面内容
    nodes: List[FusedNode] = field(default_factory=list)
    page_description: str = ""          # 整页自然语言描述 (来自 VLM)

    # 去重信息
    structure_hash: str = ""            # 结构哈希 (用于去重)

    # 元数据
    screenshot_path: Optional[str] = None
    visit_count: int = 0
    first_visit_time: float = 0.0

    @property
    def activity_short(self) -> str:
        """获取 Activity 短名"""
        return self.activity.split(".")[-1] if self.activity else "Unknown"

    @property
    def clickable_nodes(self) -> List[FusedNode]:
        """获取可点击节点"""
        return [n for n in self.nodes if n.clickable]

    @property
    def editable_nodes(self) -> List[FusedNode]:
        """获取可编辑节点"""
        return [n for n in self.nodes if n.editable]

    @property
    def scrollable_nodes(self) -> List[FusedNode]:
        """获取可滚动节点"""
        return [n for n in self.nodes if n.scrollable]


# =============================================================================
# 页面跳转
# =============================================================================

@dataclass
class Transition:
    """页面跳转记录"""
    from_page_id: str                   # 源页面 ID
    to_page_id: str                     # 目标页面 ID

    # 触发动作
    action_type: str = "tap"            # "tap" | "swipe" | "back" | "input"
    target_node_id: Optional[str] = None  # 目标节点 ID
    action_coords: Tuple[int, int] = (0, 0)  # 动作坐标

    # 元数据
    success: bool = True
    timestamp: float = 0.0


# =============================================================================
# 探索配置
# =============================================================================

@dataclass
class ExplorationConfig:
    """探索配置"""
    # 基础配置
    max_pages: int = 50                 # 最大页面数
    max_depth: int = 10                 # 最大探索深度
    max_time_seconds: int = 1800        # 最大探索时间 (30分钟)

    # VLM 功能开关 (API 配置通过 vlm_engine.set_vlm_config 设置)
    enable_od: bool = True              # 启用目标检测
    enable_ocr: bool = True             # 启用 OCR
    enable_caption: bool = True         # 启用图像描述

    # VLM 并发推理配置
    vlm_concurrent_enabled: bool = False    # 启用 VLM 并发推理
    vlm_concurrent_requests: int = 5        # 并发请求数量
    vlm_occurrence_threshold: int = 2       # 出现阈值（检测框出现多少次才认为有效）

    # 融合配置
    iou_threshold: float = 0.5          # IoU 匹配阈值

    # 探索配置
    action_delay_ms: int = 1000         # 动作后等待时间
    scroll_enabled: bool = True         # 启用滚动探索
    max_scrolls_per_page: int = 5       # 每页最大滚动次数

    # 回退配置
    max_back_attempts: int = 5          # 最大 Back 尝试次数

    # 输出配置
    save_screenshots: bool = True       # 保存截图
    output_dir: str = "./maps"          # 输出目录


# =============================================================================
# 探索结果
# =============================================================================

@dataclass
class ExplorationResult:
    """探索结果"""
    package: str                        # 应用包名
    pages: Dict[str, PageState] = field(default_factory=dict)  # 页面字典
    transitions: List[Transition] = field(default_factory=list)  # 跳转列表

    # 统计信息
    exploration_time_seconds: float = 0.0
    total_actions: int = 0
    vlm_inference_count: int = 0
    vlm_total_time_ms: float = 0.0

    @property
    def page_count(self) -> int:
        return len(self.pages)

    @property
    def transition_count(self) -> int:
        return len(self.transitions)
