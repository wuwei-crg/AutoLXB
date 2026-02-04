"""
LXB Auto Map Builder v2 - BFS 探索引擎

实现：
- BFS 广度优先探索
- 路径记录 + 重启回退策略
- 滚动处理 (VLM 判重)
"""

import time
from collections import deque
from typing import List, Dict, Tuple, Optional, Callable, Set
from dataclasses import dataclass, field

from .models import (
    PageState, FusedNode, Transition, ExplorationConfig, ExplorationResult
)
from .vlm_engine import VLMEngine, VLMConfig
from .fusion_engine import FusionEngine, parse_xml_nodes
from .page_manager import PageManager, is_duplicate_node


# 路径步骤: (page_id, node_id)
PathStep = Tuple[str, str]


@dataclass
class ExplorationState:
    """探索状态"""
    package: str
    start_time: float = 0.0
    total_actions: int = 0

    # BFS 队列: (page_id, depth, path_to_here)
    queue: deque = field(default_factory=deque)

    # 已探索的节点 (page_id, node_id)
    explored_nodes: Set[Tuple[str, str]] = field(default_factory=set)


class Explorer:
    """BFS 探索引擎"""

    def __init__(
        self,
        client,  # LXBLinkClient
        config: Optional[ExplorationConfig] = None,
        log_callback: Optional[Callable] = None
    ):
        """
        Args:
            client: LXB-Link 客户端实例
            config: 探索配置
            log_callback: 日志回调函数 (level, message, data)
        """
        self.client = client
        self.config = config or ExplorationConfig()
        self.log = log_callback or self._default_log

        # 初始化组件
        # VLM 使用全局配置 (通过 web console 设置)
        from .vlm_engine import get_config
        vlm_config = get_config()
        # 覆盖功能开关
        vlm_config.enable_od = self.config.enable_od
        vlm_config.enable_ocr = self.config.enable_ocr
        vlm_config.enable_caption = self.config.enable_caption
        # 设置并发推理配置
        vlm_config.concurrent_enabled = self.config.vlm_concurrent_enabled
        vlm_config.concurrent_requests = self.config.vlm_concurrent_requests
        vlm_config.occurrence_threshold = self.config.vlm_occurrence_threshold

        self.vlm_engine = VLMEngine(vlm_config)
        self.fusion_engine = FusionEngine(self.config.iou_threshold)
        self.page_manager = PageManager()

        # 探索状态
        self.state: Optional[ExplorationState] = None

        # 结果
        self.transitions: List[Transition] = []

    def _default_log(self, level: str, message: str, data: dict = None):
        """默认日志输出"""
        print(f"[{level.upper()}] {message}")

    def explore(self, package_name: str) -> ExplorationResult:
        """
        执行 BFS 探索

        Args:
            package_name: 应用包名

        Returns:
            探索结果
        """
        self.log("info", f"开始探索应用: {package_name}")

        # 初始化状态
        self.state = ExplorationState(
            package=package_name,
            start_time=time.time()
        )
        self.transitions = []

        # 启动应用
        self._launch_app(package_name)

        # 分析首页
        first_page = self._analyze_current_page()
        if not first_page:
            self.log("error", "无法分析首页")
            return self._build_result()

        self.page_manager.register_page(first_page)
        self.state.queue.append((first_page.page_id, 0, []))

        self.log("info", f"首页: {first_page.page_id}", {
            "description": first_page.page_description[:100]
        })

        # BFS 主循环
        while self.state.queue:
            # 检查终止条件
            if self._should_stop():
                break

            current_page_id, depth, path_to_current = self.state.queue.popleft()
            current_page = self.page_manager.get_page(current_page_id)

            if not current_page:
                continue

            if depth >= self.config.max_depth:
                self.log("debug", f"跳过深度超限页面: {current_page_id}")
                continue

            self.log("info", f"探索页面: {current_page_id} (深度={depth})", {
                "clickable_count": len(current_page.clickable_nodes)
            })

            # 确保在正确的页面上
            if not self._ensure_on_page(current_page_id, path_to_current):
                self.log("warn", f"无法导航到页面: {current_page_id}")
                continue

            # 处理滚动
            if self.config.scroll_enabled:
                self._handle_scroll(current_page)

            # 遍历可点击节点
            for node in current_page.clickable_nodes:
                node_key = (current_page_id, node.node_id)
                if node_key in self.state.explored_nodes:
                    continue

                self.state.explored_nodes.add(node_key)

                # 执行点击
                self.log("debug", f"点击节点: {node.description}")
                self._tap_node(node)

                # 分析新页面
                new_page = self._analyze_current_page()
                if not new_page:
                    self._navigate_back(current_page_id, path_to_current)
                    continue

                # 记录跳转
                transition = Transition(
                    from_page_id=current_page_id,
                    to_page_id=new_page.page_id,
                    action_type="tap",
                    target_node_id=node.node_id,
                    action_coords=node.center,
                    timestamp=time.time()
                )
                self.transitions.append(transition)

                # 检查是否是新页面
                is_new = self.page_manager.register_page(new_page)
                if is_new:
                    self.log("info", f"发现新页面: {new_page.page_id}", {
                        "description": new_page.page_description[:100]
                    })

                    # 加入队列
                    new_path = path_to_current + [(current_page_id, node.node_id)]
                    self.state.queue.append((new_page.page_id, depth + 1, new_path))

                # 返回当前页面
                self._navigate_back(current_page_id, path_to_current)

        return self._build_result()

    def _should_stop(self) -> bool:
        """检查是否应该停止探索"""
        # 页面数量限制
        if len(self.page_manager.pages) >= self.config.max_pages:
            self.log("info", "达到最大页面数限制")
            return True

        # 时间限制
        elapsed = time.time() - self.state.start_time
        if elapsed >= self.config.max_time_seconds:
            self.log("info", "达到最大时间限制")
            return True

        return False

    def _launch_app(self, package_name: str):
        """启动应用"""
        self.log("info", f"启动应用: {package_name}")
        self.client.launch_app(package_name, clear_task=True)
        time.sleep(2)

    def _analyze_current_page(self) -> Optional[PageState]:
        """分析当前页面"""
        try:
            # 1. 获取基础信息
            success, package, activity = self.client.get_activity()
            if not success:
                return None

            # 检查是否还在目标应用
            if package != self.state.package:
                self.log("warn", f"已离开目标应用: {package}")
                return None

            # 2. 获取截图
            screenshot = self.client.request_screenshot()
            if not screenshot:
                self.log("warn", "截图失败")
                return None

            # 3. 获取 XML 节点
            actions = self.client.dump_actions()
            raw_nodes = actions.get("nodes", [])
            xml_nodes = parse_xml_nodes(raw_nodes)

            # 4. VLM 推理
            vlm_result = self.vlm_engine.infer_concurrent(screenshot)

            # 5. 融合
            fused_nodes = self.fusion_engine.fuse(xml_nodes, vlm_result)

            # 6. 计算哈希和 ID
            structure_hash = self.page_manager.compute_structure_hash(fused_nodes)
            page_id = self.page_manager.generate_page_id(activity, structure_hash)

            # 7. 创建页面状态
            page = PageState(
                page_id=page_id,
                activity=activity,
                package=package,
                nodes=fused_nodes,
                page_description=vlm_result.page_caption,
                structure_hash=structure_hash,
                first_visit_time=time.time()
            )

            return page

        except Exception as e:
            self.log("error", f"分析页面失败: {e}")
            return None

    def _analyze_current_page_quick(self) -> Optional[str]:
        """快速分析当前页面，只返回 page_id"""
        try:
            success, package, activity = self.client.get_activity()
            if not success or package != self.state.package:
                return None

            actions = self.client.dump_actions()
            raw_nodes = actions.get("nodes", [])
            xml_nodes = parse_xml_nodes(raw_nodes)

            # 创建临时融合节点 (不做 VLM)
            fused_nodes = [FusedNode.from_xml_node(n) for n in xml_nodes]

            structure_hash = self.page_manager.compute_structure_hash(fused_nodes)
            return self.page_manager.generate_page_id(activity, structure_hash)

        except Exception:
            return None

    def _tap_node(self, node: FusedNode):
        """点击节点"""
        x, y = node.center
        self.client.tap(x, y)
        self.state.total_actions += 1
        time.sleep(self.config.action_delay_ms / 1000)

    def _ensure_on_page(self, target_page_id: str, path: List[PathStep]) -> bool:
        """确保当前在目标页面上"""
        current_page_id = self._analyze_current_page_quick()

        if current_page_id == target_page_id:
            return True

        # 不在目标页面，尝试导航
        return self._navigate_to(target_page_id, path)

    def _navigate_back(self, target_page_id: str, path: List[PathStep]):
        """
        回退到目标页面

        策略:
        1. 先尝试 Back 键
        2. 如果失败，重启应用并按路径导航
        """
        for attempt in range(self.config.max_back_attempts):
            # 按 Back 键
            self.client.key_event(4)  # KEYCODE_BACK = 4
            time.sleep(0.5)

            # 检查是否回到目标
            current_page_id = self._analyze_current_page_quick()
            if current_page_id == target_page_id:
                return

            # 检查是否退出应用
            success, pkg, _ = self.client.get_activity()
            if not success or pkg != self.state.package:
                break

        # Back 失败，重启并导航
        self.log("debug", f"Back 失败，重启导航到: {target_page_id}")
        self._restart_and_navigate(path)

    def _navigate_to(self, target_page_id: str, path: List[PathStep]) -> bool:
        """导航到目标页面"""
        self._restart_and_navigate(path)

        # 验证
        current_page_id = self._analyze_current_page_quick()
        return current_page_id == target_page_id

    def _restart_and_navigate(self, path: List[PathStep]):
        """重启应用并按路径导航"""
        self._launch_app(self.state.package)

        for page_id, node_id in path:
            page = self.page_manager.get_page(page_id)
            if not page:
                continue

            # 找到对应节点
            node = next((n for n in page.nodes if n.node_id == node_id), None)
            if not node:
                continue

            self._tap_node(node)

    def _handle_scroll(self, page: PageState):
        """
        处理可滚动页面

        策略:
        1. 滚动后仍视为同一页面
        2. VLM 判断是否重复内容
        3. 重复则跳过，否则追加节点
        """
        scrollable_nodes = page.scrollable_nodes
        if not scrollable_nodes:
            return

        scrollable = scrollable_nodes[0]  # 取第一个可滚动区域
        seen_labels: Set[str] = {n.vlm_label for n in page.nodes if n.vlm_label}

        for scroll_idx in range(self.config.max_scrolls_per_page):
            # 执行滚动
            bounds = scrollable.bounds
            start_y = bounds[3] - 100
            end_y = bounds[1] + 100
            center_x = (bounds[0] + bounds[2]) // 2

            self.client.swipe(center_x, start_y, center_x, end_y, duration=300)
            time.sleep(0.5)
            self.state.total_actions += 1

            # 分析滚动后内容
            screenshot = self.client.request_screenshot()
            if not screenshot:
                break

            vlm_result = self.vlm_engine.infer_concurrent(screenshot)
            new_labels = {det.label for det in vlm_result.detections}

            # 检查是否重复内容
            if self._is_repetitive_content(new_labels, seen_labels):
                self.log("debug", f"滚动 {scroll_idx + 1}: 检测到重复内容，停止滚动")
                # 更新页面描述
                if new_labels:
                    common_label = max(new_labels, key=lambda l: sum(1 for d in vlm_result.detections if d.label == l))
                    page.page_description += f" (可滚动，包含多个 {common_label} 元素)"
                break

            # 追加新节点
            actions = self.client.dump_actions()
            raw_nodes = actions.get("nodes", [])
            xml_nodes = parse_xml_nodes(raw_nodes)
            new_fused = self.fusion_engine.fuse(xml_nodes, vlm_result)

            added_count = 0
            for node in new_fused:
                if not is_duplicate_node(node, page.nodes):
                    page.nodes.append(node)
                    added_count += 1

            self.log("debug", f"滚动 {scroll_idx + 1}: 添加 {added_count} 个新节点")
            seen_labels.update(new_labels)

    def _is_repetitive_content(self, new_labels: Set[str], seen_labels: Set[str]) -> bool:
        """判断是否是重复内容"""
        if not new_labels:
            return True

        if not seen_labels:
            return False

        overlap = len(new_labels & seen_labels) / len(new_labels)
        return overlap > 0.9

    def _build_result(self) -> ExplorationResult:
        """构建探索结果"""
        vlm_stats = self.vlm_engine.get_stats()

        return ExplorationResult(
            package=self.state.package,
            pages=self.page_manager.pages,
            transitions=self.transitions,
            exploration_time_seconds=time.time() - self.state.start_time,
            total_actions=self.state.total_actions,
            vlm_inference_count=vlm_stats["total_inferences"],
            vlm_total_time_ms=vlm_stats["total_time_ms"]
        )
