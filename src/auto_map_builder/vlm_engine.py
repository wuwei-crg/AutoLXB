"""
LXB Auto Map Builder - VLM 推理引擎

基于 OpenAI 兼容 API 的视觉语言模型，支持：
- Object Detection: 检测 UI 元素
- OCR: 识别文本
- Caption: 生成页面描述
"""

import io
import json
import os
import re
import base64
import hashlib
import time
from dataclasses import dataclass
from typing import List, Optional, Dict
from concurrent.futures import ThreadPoolExecutor, as_completed
import threading

try:
    import numpy as np
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

from .models import VLMDetection, VLMPageResult, BBox
from . import coord_calibration as _cal


# =============================================================================
# 配置
# =============================================================================

@dataclass
class VLMConfig:
    """VLM API 配置"""
    api_base_url: str = ""
    api_key: str = ""
    model_name: str = "qwen-vl-plus"
    timeout: int = 120

    # 功能开关
    enable_od: bool = True
    enable_ocr: bool = True
    enable_caption: bool = True

    # 缓存
    cache_enabled: bool = True
    max_cache_size: int = 100

    # 并发推理配置
    concurrent_enabled: bool = False        # 是否启用并发推理
    concurrent_requests: int = 5            # 并发请求数量
    occurrence_threshold: int = 2           # 出现阈值（检测框出现多少次才认为有效）


_global_config: Optional[VLMConfig] = None


def get_config() -> VLMConfig:
    """获取全局配置"""
    global _global_config
    if _global_config is None:
        _global_config = VLMConfig(
            api_base_url=os.getenv('VLM_API_BASE_URL', ''),
            api_key=os.getenv('VLM_API_KEY', ''),
            model_name=os.getenv('VLM_MODEL_NAME', 'qwen-vl-plus')
        )
    return _global_config


def set_config(config: VLMConfig):
    """设置全局配置"""
    global _global_config
    _global_config = config


# =============================================================================
# VLM 引擎
# =============================================================================

class VLMEngine:
    """OpenAI 兼容 API VLM 引擎"""

    # 提示词 - 结合 XML 和截图分析导航元素
    _PROMPT_OD = """分析这张手机 App 截图，**只识别用于页面导航的核心 UI 元素**。

**必须识别**（这些是页面跳转的锚点）：
1. 顶部导航栏：返回按钮、标题栏按钮、搜索入口、菜单按钮
2. 底部导航栏：首页/消息/购物车/我的等 Tab 按钮
3. 顶部 Tab 切换：如"关注"、"推荐"、"热门"等分类标签
4. 悬浮按钮：发布按钮、客服按钮、回到顶部等
5. 侧边栏入口：抽屉菜单按钮

**不要识别**（这些是动态内容，不是导航）：
- 商品卡片、商品图片、商品价格、商品标题
- 信息流中的任何内容（帖子、文章、视频缩略图）
- 广告横幅、促销活动、优惠券
- 列表中的每一项数据
- 搜索历史、推荐词、热搜词
- 用户头像、用户名、评论内容
- 任何滚动区域内的动态内容

**坐标格式**：像素坐标 [x1, y1, x2, y2]

返回 JSON：
```json
{
  "elements": [
    {"label": "nav_button", "bbox": [20, 50, 80, 110], "text": "返回"},
    {"label": "tab", "bbox": [55, 180, 165, 241], "text": "推荐"},
    {"label": "bottom_nav", "bbox": [100, 2700, 200, 2772], "text": "首页"}
  ]
}
```

label 类型：nav_button, tab, bottom_nav, fab, search, menu, icon
只返回 JSON，最多 15 个元素。"""

    # 新增：结合 XML 的联合分析 Prompt
    _PROMPT_JOINT_ANALYSIS = """你是一个 Android UI 分析专家。我会给你一张 App 截图和该页面的 XML 节点列表。

**你的任务**：从 XML 节点中筛选出**导航锚点**（用于页面跳转的重要 UI 元素）。

**导航锚点的特征**：
1. **位置固定**：通常在屏幕顶部（状态栏下方）或底部（底部导航栏）
2. **功能稳定**：返回按钮、Tab 切换、底部导航、搜索入口、菜单按钮
3. **不随内容变化**：无论页面内容如何滚动，这些元素位置不变

**不是导航锚点**：
- 列表中的每一项（商品、帖子、消息等）
- 搜索历史、推荐词
- 动态内容区域的任何元素
- 广告、促销横幅

**XML 节点列表**：
{xml_nodes}

**请分析并返回 JSON**：
```json
{{
  "nav_anchors": [
    {{"node_index": 0, "role": "back_button", "reason": "顶部返回按钮"}},
    {{"node_index": 5, "role": "bottom_tab", "reason": "底部导航-首页"}},
    {{"node_index": 6, "role": "bottom_tab", "reason": "底部导航-消息"}}
  ],
  "page_type": "首页/商品详情/搜索结果/个人中心/...",
  "page_function": "一句话描述页面功能"
}}
```

role 类型：back_button, search, menu, top_tab, bottom_tab, fab, sidebar
只返回 JSON。"""

    _PROMPT_OCR = ""  # 禁用单独的 OCR，OD 已包含文本

    _PROMPT_CAPTION = """用一句话描述这个 App 页面的**功能定位**。

要求：
- 描述页面的功能类型（如：首页、搜索页、商品详情页、个人中心、设置页、登录页等）
- 描述主要功能入口（如：有搜索框、有底部导航、有商品列表等）
- 不要描述具体内容（如商品名称、价格、用户名等动态数据）

示例：
- "电商App首页，包含搜索框、分类导航和商品推荐流"
- "个人中心页面，显示用户信息和功能入口列表"
- "商品详情页，展示商品图片、价格和购买按钮"
"""

    def __init__(self, config: Optional[VLMConfig] = None):
        self.config = config or get_config()
        self._client = None
        self._cache: Dict[str, VLMPageResult] = {}
        self.stats = {"total_inferences": 0, "cache_hits": 0, "total_time_ms": 0.0}
        self._probe: dict = {}  # populated by calibrate()

    def calibrate(self, attempts: int = 3) -> bool:
        """
        Run coordinate space calibration using two probe images.

        Sends synthetic corner-coloured images to the VLM and infers its
        native coordinate space via affine fitting.  Must be called once
        before any inference that requires coordinate conversion.

        Returns True if calibration succeeded.
        """
        self._probe = _cal.calibrate(self._call_api, attempts=attempts)
        ok = bool(self._probe)
        if ok:
            print(f"[VLM] 坐标校准完成: mode={self._probe['mode']}  "
                  f"x=[{self._probe['x_min']:.1f}, {self._probe['x_max']:.1f}]  "
                  f"y=[{self._probe['y_min']:.1f}, {self._probe['y_max']:.1f}]")
        else:
            print("[VLM] 坐标校准失败，将使用原始坐标值")
        return ok

    def _get_client(self):
        """延迟创建 OpenAI 客户端"""
        if self._client is None:
            from openai import OpenAI

            if not self.config.api_base_url:
                raise ValueError("VLM API URL 未配置")
            if not self.config.api_key:
                raise ValueError("VLM API Key 未配置")

            self._client = OpenAI(
                base_url=self.config.api_base_url,
                api_key=self.config.api_key,
                timeout=self.config.timeout
            )
        return self._client

    def _call_api(self, image_bytes: bytes, prompt: str) -> str:
        """调用 VLM API"""
        try:
            client = self._get_client()
            image_base64 = base64.b64encode(image_bytes).decode('utf-8')

            response = client.chat.completions.create(
                model=self.config.model_name,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}},
                        {"type": "text", "text": prompt}
                    ]
                }],
                max_tokens=4096
            )
            return response.choices[0].message.content
        except Exception as e:
            # 打印详细错误信息
            import traceback
            print(f"[VLM] API 调用失败: {e}")
            print(f"[VLM] 堆栈:\n{traceback.format_exc()}")
            raise  # 重新抛出让上层处理

    def _parse_json(self, response: str) -> Dict:
        """从响应中提取 JSON"""
        # 尝试提取 ```json ... ```
        match = re.search(r'```json\s*(.*?)\s*```', response, re.DOTALL)
        if match:
            response = match.group(1)
        else:
            # 尝试提取 { ... }
            match = re.search(r'\{.*\}', response, re.DOTALL)
            if match:
                response = match.group(0)

        try:
            return json.loads(response)
        except json.JSONDecodeError:
            return {}

    def _to_pixel_coords(self, bbox: List[int], image_width: int, image_height: int) -> BBox:
        """Convert VLM bbox coordinates to pixel coords using probe calibration."""
        x1, y1 = _cal.map_point(self._probe, bbox[0], bbox[1], image_width, image_height)
        x2, y2 = _cal.map_point(self._probe, bbox[2], bbox[3], image_width, image_height)
        return (x1, y1, x2, y2)

    def _iou(self, box1: BBox, box2: BBox) -> float:
        """计算 IoU"""
        x1, y1 = max(box1[0], box2[0]), max(box1[1], box2[1])
        x2, y2 = min(box1[2], box2[2]), min(box1[3], box2[3])

        if x2 <= x1 or y2 <= y1:
            return 0.0

        inter = (x2 - x1) * (y2 - y1)
        area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])
        return inter / (area1 + area2 - inter) if (area1 + area2 - inter) > 0 else 0.0

    def _run_od(self, image_bytes: bytes, image_width: int, image_height: int) -> List[VLMDetection]:
        """目标检测 - 自动检测坐标格式并转换为像素坐标"""
        try:
            data = self._parse_json(self._call_api(image_bytes, self._PROMPT_OD))
            detections = []

            for elem in data.get('elements', []):
                bbox = elem.get('bbox', [0, 0, 0, 0])
                if len(bbox) == 4:
                    # 自动检测坐标格式并转换
                    pixel_bbox = self._to_pixel_coords(bbox, image_width, image_height)
                    detections.append(VLMDetection(
                        bbox=pixel_bbox,
                        label=elem.get('label', 'unknown'),
                        confidence=1.0,
                        ocr_text=elem.get('text', '')
                    ))
            return detections
        except Exception as e:
            print(f"[VLMEngine] OD failed: {e}")
            return []

    def _run_ocr(self, image_bytes: bytes, detections: List[VLMDetection], image_width: int, image_height: int) -> List[VLMDetection]:
        """OCR 文本识别 - 自动检测坐标格式"""
        try:
            data = self._parse_json(self._call_api(image_bytes, self._PROMPT_OCR))

            for item in data.get('texts', []):
                bbox = item.get('bbox', [0, 0, 0, 0])
                text = item.get('text', '')

                if len(bbox) == 4 and text:
                    # 自动检测坐标格式并转换
                    pixel_bbox = self._to_pixel_coords(bbox, image_width, image_height)

                    # 尝试匹配已有检测框
                    for det in detections:
                        if not det.ocr_text and self._iou(det.bbox, pixel_bbox) > 0.5:
                            det.ocr_text = text
                            break
                    else:
                        detections.append(VLMDetection(
                            bbox=pixel_bbox,
                            label="text",
                            confidence=1.0,
                            ocr_text=text
                        ))
            return detections
        except Exception as e:
            print(f"[VLMEngine] OCR failed: {e}")
            return detections

    def _run_caption(self, image_bytes: bytes) -> str:
        """页面描述"""
        try:
            return self._call_api(image_bytes, self._PROMPT_CAPTION).strip()
        except Exception as e:
            print(f"[VLMEngine] Caption failed: {e}")
            return ""

    def analyze_with_xml(
        self,
        screenshot_bytes: bytes,
        xml_nodes: List[Dict]
    ) -> Dict:
        """
        结合截图和 XML 节点进行联合分析

        Args:
            screenshot_bytes: 截图
            xml_nodes: XML 节点列表，每个节点包含 bounds, text, resource_id, clickable 等

        Returns:
            {
                "nav_anchors": [{"node_index": 0, "role": "back_button", "reason": "..."}],
                "page_type": "首页",
                "page_function": "..."
            }
        """
        # 构建简化的 XML 节点描述
        xml_desc_lines = []
        for i, node in enumerate(xml_nodes[:50]):  # 最多 50 个节点
            bounds = node.get('bounds', [0, 0, 0, 0])
            text = node.get('text', '')[:30]  # 截断文本
            res_id = node.get('resource_id', '')
            if res_id:
                res_id = res_id.split('/')[-1]  # 只保留 ID 部分
            clickable = '可点击' if node.get('clickable') else ''
            class_name = node.get('class_name', '').split('.')[-1]

            # 格式：[索引] 类型 "文本" (resource_id) [bounds] 可点击
            line = f"[{i}] {class_name}"
            if text:
                line += f' "{text}"'
            if res_id:
                line += f' ({res_id})'
            line += f' [{bounds[0]},{bounds[1]},{bounds[2]},{bounds[3]}]'
            if clickable:
                line += f' {clickable}'
            xml_desc_lines.append(line)

        xml_desc = '\n'.join(xml_desc_lines)

        # 构建 prompt
        prompt = self._PROMPT_JOINT_ANALYSIS.format(xml_nodes=xml_desc)

        try:
            response = self._call_api(screenshot_bytes, prompt)
            result = self._parse_json(response)

            # 确保返回格式正确
            if 'nav_anchors' not in result:
                result['nav_anchors'] = []
            if 'page_type' not in result:
                result['page_type'] = ''
            if 'page_function' not in result:
                result['page_function'] = ''

            return result
        except Exception as e:
            print(f"[VLMEngine] Joint analysis failed: {e}")
            return {
                "nav_anchors": [],
                "page_type": "",
                "page_function": ""
            }
            return ""

    def infer(self, screenshot_bytes: bytes, bypass_cache: bool = False) -> VLMPageResult:
        """执行 VLM 推理"""
        from PIL import Image

        start = time.time()
        image_hash = hashlib.md5(screenshot_bytes).hexdigest()

        # 缓存检查（并发模式下禁用缓存）
        if self.config.cache_enabled and not bypass_cache and image_hash in self._cache:
            self.stats["cache_hits"] += 1
            return self._cache[image_hash]

        # 获取图像尺寸
        image = Image.open(io.BytesIO(screenshot_bytes))
        width, height = image.width, image.height

        # 执行推理 - 只做 OD（已包含文本），跳过单独的 OCR
        detections = self._run_od(screenshot_bytes, width, height) if self.config.enable_od else []
        # OCR 已禁用，OD prompt 已包含文本识别
        caption = self._run_caption(screenshot_bytes) if self.config.enable_caption else ""

        # 构建结果
        result = VLMPageResult(
            page_caption=caption,
            detections=detections,
            inference_time_ms=(time.time() - start) * 1000,
            image_size=(image.width, image.height)
        )

        # 更新统计和缓存
        self.stats["total_inferences"] += 1
        self.stats["total_time_ms"] += result.inference_time_ms

        # 设置并发信息（单次模式）
        result.concurrent_enabled = False
        result.concurrent_requests = 0
        result.concurrent_results = 0
        result.aggregated_count = len(result.detections)

        if self.config.cache_enabled and not bypass_cache:
            if len(self._cache) >= self.config.max_cache_size:
                next(iter(self._cache.popitem()))  # FIFO 淘汰
            self._cache[image_hash] = result

        return result

    def infer_concurrent(self, screenshot_bytes: bytes) -> VLMPageResult:
        """执行并发 VLM 推理 - 多次调用后聚合结果"""
        from PIL import Image

        if not self.config.concurrent_enabled:
            print("[VLM] 并发模式未启用，使用单次推理")
            return self.infer(screenshot_bytes)

        print(f"[VLM] 🚀 并发推理模式: {self.config.concurrent_requests} 次并发")

        start = time.time()
        image = Image.open(io.BytesIO(screenshot_bytes))
        width, height = image.width, image.height
        image_size = (width, height)

        # 并发执行多次推理
        num_requests = self.config.concurrent_requests
        results = []
        lock = threading.Lock()

        def single_inference(idx: int):
            """单次推理"""
            try:
                print(f"[VLM]   └─ 启动推理 #{idx + 1}/{num_requests}...")
                result = self.infer(screenshot_bytes, bypass_cache=True)  # 绕过缓存，强制调用 API
                with lock:
                    results.append(result)
                print(f"[VLM]   └─ 推理 #{idx + 1} 完成: 检测到 {len(result.detections)} 个元素")
                return result
            except Exception as e:
                print(f"[VLM]   └─ 推理 #{idx + 1} 失败: {e}")
                return None

        # 使用线程池并发执行
        print(f"[VLM] 启动 {num_requests} 个并发推理线程...")
        with ThreadPoolExecutor(max_workers=min(num_requests, 10)) as executor:
            futures = [executor.submit(single_inference, i) for i in range(num_requests)]
            for future in as_completed(futures):
                future.result()  # 等待完成，结果已在 results 中

        print(f"[VLM] 并发完成: 成功 {len(results)}/{num_requests} 次")

        if not results:
            # 所有请求都失败了，返回空结果
            print("[VLM] ⚠️ 所有推理均失败，返回空结果")
            return VLMPageResult(
                page_caption="",
                detections=[],
                inference_time_ms=(time.time() - start) * 1000,
                image_size=image_size
            )

        # 打印原始检测结果
        for i, r in enumerate(results):
            print(f"[VLM]   结果 #{i + 1}: {len(r.detections)} 个检测")

        # 合并所有推理结果（全部进入后续融合+去重流程）
        all_detections: List[VLMDetection] = []
        for r in results:
            all_detections.extend(r.detections)
        print(f"[VLM] 并发合并: {len(all_detections)} 个检测 (来自 {len(results)} 次推理)")

        # 聚合 caption（取最常见的）
        captions = [r.page_caption for r in results if r.page_caption]
        caption = max(set(captions), key=captions.count) if captions else ""

        total_time = (time.time() - start) * 1000
        print(f"[VLM] ⏱️ 并发推理总耗时: {total_time:.0f}ms")

        return VLMPageResult(
            page_caption=caption,
            detections=all_detections,
            inference_time_ms=total_time,
            image_size=image_size,
            concurrent_enabled=True,
            concurrent_requests=num_requests,
            concurrent_results=len(results),
            aggregated_count=len(all_detections)
        )

    def is_available(self) -> bool:
        """检查 VLM 是否可用"""
        try:
            return bool(self.config.api_base_url and self.config.api_key)
        except Exception:
            return False

    def clear_cache(self):
        """清空缓存"""
        self._cache.clear()

    def get_stats(self) -> Dict:
        """获取统计信息"""
        return self.stats.copy()
