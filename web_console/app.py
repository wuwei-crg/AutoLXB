"""
LXB Web Console - Flask Backend
用于可视化调试 LXB-Link 协议的 Web 控制台
"""

from flask import Flask, render_template, request, jsonify, Response
from flask_cors import CORS
import sys
import os
import base64

# 尝试加载 python-dotenv (如果存在)
try:
    from dotenv import load_dotenv
    # 加载 .env 文件
    env_path = os.path.join(os.path.dirname(__file__), '..', '.env')
    load_dotenv(env_path)
except ImportError:
    pass

# 设置 HF_TOKEN 环境变量 (用于 Hugging Face 模型下载)
if os.getenv('HF_TOKEN'):
    os.environ['HF_TOKEN'] = os.getenv('HF_TOKEN')
    print("[app.py] HF_TOKEN 已设置")

# 添加项目根目录到 Python 路径
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from src.lxb_link.client import LXBLinkClient
from src.lxb_link.constants import (
    CMD_HEARTBEAT,
    KEY_HOME,
    KEY_BACK,
    KEY_ENTER,
    KEY_MENU,
    KEY_RECENT,
)

app = Flask(__name__)
CORS(app)  # 允许跨域请求

# 全局客户端实例
client = None
connection_info = {
    'connected': False,
    'host': None,
    'port': None
}


@app.route('/')
def index():
    """主页"""
    return render_template('index.html')


@app.route('/map_builder')
def map_builder():
    """Map Builder 页面"""
    return render_template('map_builder.html')


@app.route('/api/connect', methods=['POST'])
def connect():
    """连接到设备"""
    global client, connection_info

    data = request.json
    host = data.get('host', '192.168.1.100')  # 默认 WiFi 地址
    port = data.get('port', 12345)

    try:
        # 断开旧连接
        if client:
            try:
                client.disconnect()
            except:
                pass

        # 创建新连接
        client = LXBLinkClient(host, port, timeout=2.0)
        client.connect()

        # 尝试握手
        client.handshake()

        connection_info = {
            'connected': True,
            'host': host,
            'port': port
        }

        return jsonify({
            'success': True,
            'message': f'成功连接到 {host}:{port}'
        })

    except Exception as e:
        connection_info['connected'] = False
        return jsonify({
            'success': False,
            'message': f'连接失败: {str(e)}'
        }), 500


@app.route('/api/disconnect', methods=['POST'])
def disconnect():
    """断开连接"""
    global client, connection_info

    try:
        if client:
            client.disconnect()
            client = None

        connection_info['connected'] = False

        return jsonify({
            'success': True,
            'message': '已断开连接'
        })
    except Exception as e:
        return jsonify({
            'success': False,
            'message': f'断开失败: {str(e)}'
        }), 500


@app.route('/api/status', methods=['GET'])
def status():
    """获取连接状态"""
    return jsonify(connection_info)


# =============================================================================
# Link Layer (0x00-0x0F)
# =============================================================================

@app.route('/api/command/handshake', methods=['POST'])
def cmd_handshake():
    """发送握手命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        response = client.handshake()
        return jsonify({
            'success': True,
            'message': '握手成功',
            'response': {
                'length': len(response)
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/heartbeat', methods=['POST'])
def cmd_heartbeat():
    """发送 HEARTBEAT 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        response = client._transport.send_reliable(CMD_HEARTBEAT, b'')
        return jsonify({
            'success': True,
            'message': '心跳成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Input Layer (0x10-0x1F)
# =============================================================================

@app.route('/api/command/tap', methods=['POST'])
def cmd_tap():
    """发送 TAP 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    x = data.get('x', 500)
    y = data.get('y', 800)

    try:
        response = client.tap(x, y)
        return jsonify({
            'success': True,
            'message': f'TAP ({x}, {y}) 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/swipe', methods=['POST'])
def cmd_swipe():
    """发送 SWIPE 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    x1 = data.get('x1', 500)
    y1 = data.get('y1', 1000)
    x2 = data.get('x2', 500)
    y2 = data.get('y2', 500)
    duration = data.get('duration', 300)

    try:
        response = client.swipe(x1, y1, x2, y2, duration)
        return jsonify({
            'success': True,
            'message': f'SWIPE ({x1},{y1})→({x2},{y2}) 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/long_press', methods=['POST'])
def cmd_long_press():
    """发送 LONG_PRESS 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    x = data.get('x', 500)
    y = data.get('y', 800)
    duration = data.get('duration', 1000)

    try:
        response = client.long_press(x, y, duration)
        return jsonify({
            'success': True,
            'message': f'LONG_PRESS ({x}, {y}) {duration}ms 成功',
            'response': {
                'length': len(response),
                'data': list(response) if response else []
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/unlock', methods=['POST'])
def cmd_unlock():
    """发送 UNLOCK 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        success = client.unlock()
        return jsonify({
            'success': success,
            'message': '解锁成功' if success else '解锁失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Input Extension (0x20-0x2F)
# =============================================================================

@app.route('/api/command/input_text', methods=['POST'])
def cmd_input_text():
    """发送 INPUT_TEXT 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    text = data.get('text', 'Hello LXB')
    clear_first = data.get('clear_first', False)
    press_enter = data.get('press_enter', False)

    try:
        status, actual_method = client.input_text(
            text,
            clear_first=clear_first,
            press_enter=press_enter
        )
        return jsonify({
            'success': status == 1,
            'message': f'输入文本 "{text}" 成功' if status == 1 else '输入文本失败',
            'response': {
                'status': status,
                'method': actual_method
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/key_event', methods=['POST'])
def cmd_key_event():
    """发送 KEY_EVENT 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    keycode = data.get('keycode', KEY_BACK)
    action = data.get('action', 2)  # 2 = CLICK

    # 支持按名称指定按键
    key_map = {
        'home': KEY_HOME,
        'back': KEY_BACK,
        'enter': KEY_ENTER,
        'menu': KEY_MENU,
        'recent': KEY_RECENT
    }
    if isinstance(keycode, str):
        keycode = key_map.get(keycode.lower(), KEY_BACK)

    try:
        response = client.key_event(keycode, action)
        return jsonify({
            'success': True,
            'message': f'KEY_EVENT keycode={keycode} 成功',
            'response': {
                'length': len(response) if response else 0
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Sense Layer (0x30-0x3F)
# =============================================================================

@app.route('/api/command/get_activity', methods=['POST'])
def cmd_get_activity():
    """发送 GET_ACTIVITY 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        success, package_name, activity_name = client.get_activity()
        return jsonify({
            'success': success,
            'message': '获取 Activity 成功' if success else '获取 Activity 失败',
            'response': {
                'package': package_name,
                'activity': activity_name
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/get_screen_state', methods=['POST'])
def cmd_get_screen_state():
    """发送 GET_SCREEN_STATE 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        success, state = client.get_screen_state()
        state_names = {0: '关闭', 1: '亮屏已解锁', 2: '亮屏已锁定'}
        return jsonify({
            'success': success,
            'message': f'屏幕状态: {state_names.get(state, "未知")}',
            'response': {
                'state': state,
                'state_name': state_names.get(state, 'unknown')
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/get_screen_size', methods=['POST'])
def cmd_get_screen_size():
    """发送 GET_SCREEN_SIZE 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        success, width, height, density = client.get_screen_size()
        return jsonify({
            'success': success,
            'message': f'屏幕尺寸: {width}x{height} @{density}dpi',
            'response': {
                'width': width,
                'height': height,
                'density': density
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/find_node', methods=['POST'])
def cmd_find_node():
    """发送 FIND_NODE 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    query = data.get('query', '')
    match_type = data.get('match_type', 1)  # MATCH_CONTAINS_TEXT
    multi_match = data.get('multi_match', False)

    try:
        status, results = client.find_node(
            query,
            match_type=match_type,
            multi_match=multi_match
        )
        return jsonify({
            'success': status == 1,
            'message': f'找到 {len(results)} 个节点' if status == 1 else '未找到节点',
            'response': {
                'status': status,
                'count': len(results),
                'results': results
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/dump_hierarchy', methods=['POST'])
def cmd_dump_hierarchy():
    """发送 DUMP_HIERARCHY 命令，获取完整 UI 层级结构"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    max_depth = data.get('max_depth', 0)  # 0 = 无限制

    try:
        hierarchy = client.dump_hierarchy(max_depth=max_depth)
        node_count = hierarchy.get('node_count', 0)
        nodes = hierarchy.get('nodes', [])

        # 统计可交互节点
        clickable_count = sum(1 for n in nodes if n.get('clickable', False))
        editable_count = sum(1 for n in nodes if n.get('editable', False))
        scrollable_count = sum(1 for n in nodes if n.get('scrollable', False))

        return jsonify({
            'success': True,
            'message': f'获取 UI 树成功: {node_count} 个节点',
            'response': {
                'version': hierarchy.get('version', 1),
                'node_count': node_count,
                'clickable_count': clickable_count,
                'editable_count': editable_count,
                'scrollable_count': scrollable_count,
                'nodes': nodes
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/dump_actions', methods=['POST'])
def cmd_dump_actions():
    """发送 DUMP_ACTIONS 命令，获取可交互节点 (用于路径规划)"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        actions = client.dump_actions()
        node_count = actions.get('node_count', 0)
        nodes = actions.get('nodes', [])

        # 统计各类型节点
        clickable_count = sum(1 for n in nodes if n.get('clickable', False))
        editable_count = sum(1 for n in nodes if n.get('editable', False))
        scrollable_count = sum(1 for n in nodes if n.get('scrollable', False))
        text_only_count = sum(1 for n in nodes if n.get('text_only', False))

        return jsonify({
            'success': True,
            'message': f'获取可交互节点成功: {node_count} 个节点',
            'response': {
                'version': actions.get('version', 1),
                'node_count': node_count,
                'clickable_count': clickable_count,
                'editable_count': editable_count,
                'scrollable_count': scrollable_count,
                'text_only_count': text_only_count,
                'nodes': nodes
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Lifecycle Layer (0x40-0x4F)
# =============================================================================

@app.route('/api/command/launch_app', methods=['POST'])
def cmd_launch_app():
    """发送 LAUNCH_APP 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    package_name = data.get('package', '')
    clear_task = data.get('clear_task', False)

    if not package_name:
        return jsonify({'success': False, 'message': '请输入包名'}), 400

    try:
        success = client.launch_app(package_name, clear_task=clear_task)
        return jsonify({
            'success': success,
            'message': f'启动 {package_name} 成功' if success else f'启动 {package_name} 失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/stop_app', methods=['POST'])
def cmd_stop_app():
    """发送 STOP_APP 命令"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请输入包名'}), 400

    try:
        success = client.stop_app(package_name)
        return jsonify({
            'success': success,
            'message': f'停止 {package_name} 成功' if success else f'停止 {package_name} 失败'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/list_apps', methods=['POST'])
def cmd_list_apps():
    """发送 LIST_APPS 命令，获取已安装应用列表"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    data = request.json
    filter_type = data.get('filter', 'user')  # user / system / all

    try:
        apps = client.list_apps(filter_type)
        return jsonify({
            'success': True,
            'message': f'获取应用列表成功: {len(apps)} 个应用',
            'response': {
                'filter': filter_type,
                'count': len(apps),
                'apps': apps
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


# =============================================================================
# Media Layer (0x60-0x6F)
# =============================================================================

@app.route('/api/command/screenshot', methods=['POST'])
def cmd_screenshot():
    """发送 SCREENSHOT 命令 (使用分片传输)"""
    if not client:
        return jsonify({'success': False, 'message': '未连接'}), 400

    try:
        # 使用分片传输方式获取截图
        image_data = client.request_screenshot()

        if image_data and len(image_data) > 0:
            # 截图成功，返回 base64 编码的图片
            image_base64 = base64.b64encode(image_data).decode('utf-8')
            return jsonify({
                'success': True,
                'message': f'截图成功: {len(image_data)} 字节 ({len(image_data)/1024:.1f} KB)',
                'response': {
                    'size': len(image_data),
                    'image': image_base64
                }
            })
        else:
            return jsonify({
                'success': False,
                'message': '截图失败: 无数据返回'
            })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/command/screenshot/raw', methods=['GET'])
def cmd_screenshot_raw():
    """获取原始截图图片（可直接嵌入 img 标签）"""
    if not client:
        return Response('未连接', status=400, mimetype='text/plain')

    try:
        # 使用分片传输方式获取截图
        image_data = client.request_screenshot()

        if image_data and len(image_data) > 0:
            # 返回 JPEG 图片 (服务端已压缩为 JPEG)
            return Response(image_data, mimetype='image/jpeg')
        else:
            return Response('截图失败', status=500, mimetype='text/plain')
    except Exception as e:
        return Response(str(e), status=500, mimetype='text/plain')


# =============================================================================
# Auto Map Builder v2 (VLM + XML 融合建图)
# =============================================================================

# 检测 VLM 是否可用
VLM_AVAILABLE = False
try:
    from src.auto_map_builder import AutoMapBuilder, ExplorationConfig
    from src.auto_map_builder.vlm_engine import VLMEngine
    from src.auto_map_builder.fusion_engine import FusionEngine, parse_xml_nodes
    from src.auto_map_builder.page_manager import PageManager
    VLM_AVAILABLE = True
except ImportError as e:
    print(f"[app.py] Auto Map Builder 不可用: {e}")

# 全局探索器实例和状态
explorer_instance = None
exploration_result = None
exploration_status = {
    'running': False,
    'package': None,
    'progress': {
        'pages_discovered': 0,
        'nodes_discovered': 0,
        'current_page': None
    },
    'result': None,
    'logs': []
}


@app.route('/api/explore/start', methods=['POST'])
def explore_start():
    """启动应用探索 (v2 VLM+XML 融合)"""
    global client, explorer_instance, exploration_result, exploration_status

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

    if exploration_status['running']:
        return jsonify({'success': False, 'message': '探索正在进行中'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'Auto Map Builder 模块不可用'}), 400

    data = request.json
    package_name = data.get('package', '')

    if not package_name:
        return jsonify({'success': False, 'message': '请指定应用包名'}), 400

    try:
        from datetime import datetime

        # 创建配置
        config = ExplorationConfig(
            max_pages=data.get('max_pages', 50),
            max_depth=data.get('max_depth', 10),
            max_time_seconds=data.get('max_time_seconds', 1800),
            enable_od=data.get('enable_od', True),
            enable_ocr=data.get('enable_ocr', True),
            enable_caption=data.get('enable_caption', True),
            # 并发推理配置
            vlm_concurrent_enabled=data.get('vlm_concurrent_enabled', False),
            vlm_concurrent_requests=data.get('vlm_concurrent_requests', 5),
            vlm_occurrence_threshold=data.get('vlm_occurrence_threshold', 2),
            iou_threshold=data.get('iou_threshold', 0.5),
            action_delay_ms=data.get('action_delay_ms', 1000),
            scroll_enabled=data.get('scroll_enabled', True),
            max_scrolls_per_page=data.get('max_scrolls_per_page', 5),
            save_screenshots=data.get('save_screenshots', True),
            output_dir=data.get('output_dir', './maps')
        )

        # 日志回调
        def log_callback(level, message, log_data=None):
            log_entry = {
                'time': datetime.now().strftime('%H:%M:%S'),
                'level': level,
                'message': message,
                'data': log_data
            }
            exploration_status['logs'].append(log_entry)

        # 清空日志
        exploration_status['logs'] = []

        # 创建探索器
        explorer_instance = AutoMapBuilder(client, config, log_callback)

        # 更新状态
        exploration_status['running'] = True
        exploration_status['package'] = package_name
        exploration_status['progress'] = {
            'pages_discovered': 0,
            'nodes_discovered': 0,
            'current_page': None
        }
        exploration_status['result'] = None

        log_callback('info', f'开始探索: {package_name}')

        # 执行探索
        exploration_result = explorer_instance.explore(package_name)

        # 更新结果
        exploration_status['running'] = False
        exploration_status['progress'] = {
            'pages_discovered': exploration_result.page_count,
            'nodes_discovered': sum(len(p.nodes) for p in exploration_result.pages.values()),
            'current_page': 'completed'
        }
        exploration_status['result'] = {
            'pages': exploration_result.page_count,
            'transitions': exploration_result.transition_count,
            'time': round(exploration_result.exploration_time_seconds, 2),
            'vlm_inferences': exploration_result.vlm_inference_count,
            'vlm_time_ms': round(exploration_result.vlm_total_time_ms, 2)
        }

        return jsonify({
            'success': True,
            'message': f'探索完成: {exploration_result.page_count} 个页面',
            'result': exploration_status['result']
        })

    except Exception as e:
        import traceback
        exploration_status['running'] = False
        return jsonify({
            'success': False,
            'message': f'探索失败: {str(e)}',
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/status', methods=['GET'])
def explore_status():
    """获取探索状态"""
    return jsonify(exploration_status)


@app.route('/api/explore/logs', methods=['GET'])
def explore_logs():
    """获取探索日志"""
    since = request.args.get('since', 0, type=int)
    logs = exploration_status.get('logs', [])
    return jsonify({
        'success': True,
        'logs': logs[since:],
        'total': len(logs)
    })


@app.route('/api/explore/result/overview', methods=['GET'])
def explore_result_overview():
    """获取探索结果 - app_overview.json"""
    global explorer_instance, exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    try:
        overview = explorer_instance.generate_overview_json()
        return jsonify({
            'success': True,
            'data': overview
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/explore/result/pages', methods=['GET'])
def explore_result_pages():
    """获取所有页面列表"""
    global exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    try:
        pages_summary = []
        for page_id, page in exploration_result.pages.items():
            pages_summary.append({
                'page_id': page_id,
                'activity': page.activity,
                'description': page.page_description,
                'node_count': len(page.nodes),
                'clickable_count': len(page.clickable_nodes)
            })

        return jsonify({
            'success': True,
            'data': pages_summary
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/result/page/<page_id>', methods=['GET'])
def explore_result_page(page_id):
    """获取指定页面详情"""
    global exploration_result

    if not exploration_result:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    if page_id not in exploration_result.pages:
        return jsonify({
            'success': False,
            'message': f'页面 {page_id} 不存在',
            'available': list(exploration_result.pages.keys())
        }), 404

    try:
        page = exploration_result.pages[page_id]
        nodes_data = []
        for node in page.nodes:
            nodes_data.append({
                'node_id': node.node_id,
                'bounds': list(node.bounds),
                'center': list(node.center),
                'class_name': node.class_name,
                'text': node.text,
                'resource_id': node.resource_id,
                'clickable': node.clickable,
                'editable': node.editable,
                'scrollable': node.scrollable,
                'vlm_label': node.vlm_label,
                'vlm_ocr_text': node.vlm_ocr_text,
                'iou_score': node.iou_score
            })

        return jsonify({
            'success': True,
            'data': {
                'page_id': page.page_id,
                'activity': page.activity,
                'package': page.package,
                'description': page.page_description,
                'structure_hash': page.structure_hash,
                'nodes': nodes_data
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/explore/save', methods=['POST'])
def explore_save():
    """保存探索结果到文件"""
    global explorer_instance

    if not explorer_instance:
        return jsonify({'success': False, 'message': '没有探索结果'}), 400

    data = request.json or {}
    output_dir = data.get('output_dir', './maps')

    try:
        explorer_instance.save(output_dir)

        return jsonify({
            'success': True,
            'message': f'已保存到 {output_dir}',
            'path': output_dir
        })
    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


# =============================================================================
# VLM 调试 API
# =============================================================================

@app.route('/api/vlm/config', methods=['GET'])
def vlm_config_get():
    """获取 VLM 配置"""
    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        from src.auto_map_builder.vlm_engine import get_config as get_vlm_config
        config = get_vlm_config()
        return jsonify({
            'success': True,
            'data': {
                'api_base_url': config.api_base_url,
                'api_key': '***' if config.api_key else '',  # 隐藏 API Key
                'model_name': config.model_name,
                'enable_od': config.enable_od,
                'enable_ocr': config.enable_ocr,
                'enable_caption': config.enable_caption,
                'timeout': config.timeout,
                # 并发配置
                'concurrent_enabled': config.concurrent_enabled,
                'concurrent_requests': config.concurrent_requests,
                'occurrence_threshold': config.occurrence_threshold or 2
            }
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/vlm/config', methods=['POST'])
def vlm_config_set():
    """设置 VLM 配置"""
    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        from src.auto_map_builder.vlm_engine import VLMConfig, set_config, get_config

        data = request.json
        current_config = get_config()

        # 创建新配置，保留未修改的字段
        new_config = VLMConfig(
            api_base_url=data.get('api_base_url', current_config.api_base_url),
            api_key=data.get('api_key', current_config.api_key) if data.get('api_key') != '***' else current_config.api_key,
            model_name=data.get('model_name', current_config.model_name),
            enable_od=data.get('enable_od', current_config.enable_od),
            enable_ocr=data.get('enable_ocr', current_config.enable_ocr),
            enable_caption=data.get('enable_caption', current_config.enable_caption),
            timeout=data.get('timeout', current_config.timeout),
            # 并发配置
            concurrent_enabled=data.get('concurrent_enabled', current_config.concurrent_enabled),
            concurrent_requests=data.get('concurrent_requests', current_config.concurrent_requests),
            occurrence_threshold=data.get('occurrence_threshold', current_config.occurrence_threshold or 2)
        )

        set_config(new_config)

        return jsonify({
            'success': True,
            'message': 'VLM 配置已更新'
        })
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/debug/vlm_status', methods=['GET', 'POST'])
def debug_vlm_status():
    """检测 VLM 可用性"""
    try:
        status = {
            'available': VLM_AVAILABLE,
            'message': 'VLM 模块可用' if VLM_AVAILABLE else 'VLM 模块不可用'
        }

        if VLM_AVAILABLE:
            try:
                from src.auto_map_builder.vlm_engine import get_config as get_vlm_config
                config = get_vlm_config()
                engine = VLMEngine()
                status['model_available'] = engine.is_available()
                status['model_name'] = config.model_name
                status['api_configured'] = bool(config.api_base_url and config.api_key)
            except Exception as e:
                status['model_available'] = False
                status['error'] = str(e)

        return jsonify({'success': True, 'data': status})

    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500


@app.route('/api/debug/vlm_test', methods=['POST'])
def debug_vlm_test():
    """测试 VLM 推理"""
    global client

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        import time

        # 获取截图
        screenshot = client.request_screenshot()
        if not screenshot:
            return jsonify({'success': False, 'message': '截图失败'}), 400

        # VLM 推理
        engine = VLMEngine()
        start = time.time()
        result = engine.infer(screenshot)
        elapsed = (time.time() - start) * 1000

        # 序列化结果
        detections = []
        for det in result.detections:
            detections.append({
                'label': det.label,
                'bbox': list(det.bbox),
                'ocr_text': det.ocr_text
            })

        return jsonify({
            'success': True,
            'message': f'VLM 推理成功: {len(detections)} 个检测, {elapsed:.0f}ms',
            'response': {
                'page_caption': result.page_caption,
                'detections': detections,
                'inference_time_ms': elapsed
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


@app.route('/api/debug/analyze_page', methods=['POST'])
def debug_analyze_page():
    """分析当前页面 (VLM + XML 融合)"""
    global client

    if not client:
        return jsonify({'success': False, 'message': '未连接设备'}), 400

    if not VLM_AVAILABLE:
        return jsonify({'success': False, 'message': 'VLM 模块不可用'}), 400

    try:
        import time

        # 获取基础信息
        success, package, activity = client.get_activity()
        if not success:
            return jsonify({'success': False, 'message': '获取 Activity 失败'}), 400

        # 先获取 XML 节点（确保和截图是同一页面）
        actions = client.dump_actions()
        raw_nodes = actions.get('nodes', [])

        # 短暂延迟确保同步
        time.sleep(0.1)

        # 获取截图
        screenshot = client.request_screenshot()

        # VLM 推理 - 使用并发推理（如果已配置）
        vlm_engine = VLMEngine()
        start = time.time()
        vlm_result = vlm_engine.infer_concurrent(screenshot)
        vlm_time = (time.time() - start) * 1000

        # XML 解析
        xml_nodes = parse_xml_nodes(raw_nodes)

        # 融合 - 降低阈值便于调试
        fusion_engine = FusionEngine(iou_threshold=0.2)
        fused_nodes = fusion_engine.fuse(xml_nodes, vlm_result)

        # 计算哈希
        page_manager = PageManager()
        structure_hash = page_manager.compute_structure_hash(fused_nodes)
        page_id = page_manager.generate_page_id(activity, structure_hash)

        # 序列化融合结果
        nodes_data = []
        for node in fused_nodes:
            nodes_data.append({
                'node_id': node.node_id,
                'bounds': list(node.bounds),
                'class_name': node.class_name.split('.')[-1],
                'text': node.text[:100] if node.text else '',
                'resource_id': node.resource_id,
                'content_desc': node.content_desc,
                'clickable': node.clickable,
                'editable': node.editable,
                'vlm_label': node.vlm_label,
                'vlm_ocr_text': node.vlm_ocr_text,
                'iou_score': round(node.iou_score, 3)
            })

        # VLM 原始检测结果（用于调试）
        vlm_raw = []
        for det in vlm_result.detections[:20]:  # 只返回前 20 个
            vlm_raw.append({
                'label': det.label,
                'bbox': list(det.bbox),
                'text': det.ocr_text
            })

        # 获取融合统计
        fusion_stats = fusion_engine.get_stats()

        # XML 节点样本（用于调试）
        xml_sample = []
        for node in xml_nodes[:5]:
            xml_sample.append({
                'bounds': list(node.bounds),
                'class': node.class_name.split('.')[-1],
                'text': node.text[:30] if node.text else '',
                'resource_id': node.resource_id
            })

        return jsonify({
            'success': True,
            'message': f'分析完成: VLM 检测 {len(vlm_result.detections)} 个, 匹配 {len(fused_nodes)} 个',
            'response': {
                'page_id': page_id,
                'activity': activity,
                'package': package,
                'page_description': vlm_result.page_caption,
                'structure_hash': structure_hash,
                'vlm_time_ms': round(vlm_time, 2),
                'node_count': len(fused_nodes),
                'clickable_count': len([n for n in fused_nodes if n.clickable]),
                'image_size': list(vlm_result.image_size),
                # 并发推理信息
                'concurrent_enabled': vlm_result.concurrent_enabled,
                'concurrent_requests': vlm_result.concurrent_requests,
                'concurrent_results': vlm_result.concurrent_results,
                'aggregated_count': vlm_result.aggregated_count,
                'fusion_stats': {
                    'vlm_detections': len(vlm_result.detections),
                    'xml_nodes': fusion_stats.get("total_xml_nodes", 0),
                    'matched': fusion_stats.get("matched_count", 0),
                    'unmatched_vlm': fusion_stats.get("unmatched_vlm", 0)
                },
                'vlm_raw': vlm_raw,  # VLM 原始检测（调试用）
                'xml_sample': xml_sample,  # XML 节点样本（调试用）
                'nodes': nodes_data
            }
        })

    except Exception as e:
        import traceback
        return jsonify({
            'success': False,
            'message': str(e),
            'traceback': traceback.format_exc()
        }), 500


if __name__ == '__main__':
    print("=" * 60)
    print("LXB Web Console")
    print("=" * 60)
    print("访问地址: http://localhost:5000")
    if VLM_AVAILABLE:
        print("VLM 模块: 可用")
    else:
        print("VLM 模块: 不可用 (需要安装 torch, transformers)")
    print("按 Ctrl+C 停止服务")
    print("=" * 60)

    app.run(host='0.0.0.0', port=5000, debug=True)
