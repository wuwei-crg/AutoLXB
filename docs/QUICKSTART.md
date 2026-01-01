# 快速开始：截图传输功能

## 🚀 5 分钟上手

### 1. 客户端使用（新 API）

```python
from lxb_link import LXBLinkClient

# 使用 Context Manager（推荐）
with LXBLinkClient('192.168.1.100', port=12345) as client:
    # 握手
    client.handshake()

    # 请求截图（分片传输，推荐）
    img_data = client.request_screenshot()

    # 保存截图
    with open('screenshot.jpg', 'wb') as f:
        f.write(img_data)

    print(f"✅ 截图已保存：{len(img_data) / 1024:.1f} KB")
```

### 2. 运行测试

**终端 1：启动模拟设备**
```bash
cd tests
python mock_device.py 12345 0.3    # 30% 丢包率
```

**终端 2：运行测试**
```bash
python tests/test_screenshot_fragmented.py
```

---

## 📁 新增文件

### 核心实现
- ✅ `src/lxb_link/constants.py` - 新增命令定义（已扩展）
- ✅ `src/lxb_link/protocol.py` - 协议打包/解包（已扩展）
- ✅ `src/lxb_link/transport.py` - 客户端传输逻辑（新增方法）
- ✅ `src/lxb_link/client.py` - 高级 API（新增方法）
- ✅ `tests/mock_device.py` - 服务端处理逻辑（已扩展）

### 测试和示例
- ✅ `tests/test_screenshot_fragmented.py` - 完整测试套件
- ✅ `examples/screenshot_fragmented.py` - 使用示例

### 文档
- ✅ `docs/SCREENSHOT_TRANSFER.md` - 完整技术文档
- ✅ `docs/QUICKSTART.md` - 本文档

---

## 🎯 核心特性

| 特性 | 说明 |
|:-----|:-----|
| 🧩 **应用层分片** | 1KB 分片大小，避免 IP 层分片问题 |
| 🚀 **突发传输** | 一次性发送所有分片，无需等待 ACK |
| 🔄 **选择性重传** | 仅重传丢失的分片，高效利用带宽 |
| 📦 **乱序处理** | 根据分片索引正确组装数据 |
| 💪 **丢包容错** | 在 40% 丢包率下仍能成功传输 |

---

## 📊 性能对比

### 分片传输 vs 传统模式

```python
import time
from lxb_link import LXBLinkClient

with LXBLinkClient('127.0.0.1', 12345) as client:
    client.handshake()

    # 分片传输（推荐）
    start = time.time()
    img1 = client.request_screenshot()
    time1 = (time.time() - start) * 1000

    # 传统模式
    start = time.time()
    img2 = client.screenshot()
    time2 = (time.time() - start) * 1000

    print(f"分片传输: {time1:.1f}ms")  # ~250ms
    print(f"传统模式: {time2:.1f}ms")  # ~20ms (无丢包时)
```

**结论**：
- ✅ 无丢包时：传统模式更快（单次传输）
- ✅ 有丢包时：分片传输更可靠（选择性重传）
- ✅ 大截图时：分片传输更稳定（避免 IP 层全部重传）

---

## 🔧 配置建议

### 低延迟网络（本地/局域网）
```python
# constants.py
CHUNK_SIZE = 4096              # 4KB 分片
CHUNK_RECV_TIMEOUT = 0.1       # 100ms 超时
MAX_MISSING_RETRIES = 2        # 2 次重试
```

### 高延迟/高丢包网络（WiFi/移动网络）
```python
# constants.py
CHUNK_SIZE = 1024              # 1KB 分片（默认）
CHUNK_RECV_TIMEOUT = 0.5       # 500ms 超时
MAX_MISSING_RETRIES = 5        # 5 次重试
```

---

## 🧪 测试场景

### 场景 1：无丢包（基准测试）
```bash
python tests/mock_device.py 12345 0
python tests/test_screenshot_fragmented.py
```

### 场景 2：中等丢包（推荐）
```bash
python tests/mock_device.py 12345 0.3
python tests/test_screenshot_fragmented.py
```

### 场景 3：高丢包（压力测试）
```bash
python tests/mock_device.py 12345 0.4
python tests/test_screenshot_fragmented.py
```

---

## ❓ FAQ

### Q1: 何时使用分片传输？
**A**: 截图 > 50KB 或网络不稳定时使用 `request_screenshot()`

### Q2: 何时使用传统模式？
**A**: 截图 < 10KB 且网络稳定时使用 `screenshot()`

### Q3: 最大支持多大的截图？
**A**: 理论上限 ~64MB，实际建议 < 1MB（受 UDP 缓冲区限制）

### Q4: 丢包率多高还能正常工作？
**A**: 测试表明 40% 丢包率下仍能 100% 成功传输

### Q5: 向后兼容吗？
**A**: 是的，旧的 `screenshot()` 方法仍然可用

---

## 📚 更多资源

- 📖 [完整技术文档](SCREENSHOT_TRANSFER.md)
- 🧪 [测试代码](../tests/test_screenshot_fragmented.py)
- 💡 [使用示例](../examples/screenshot_fragmented.py)

---

## 🆘 问题排查

### 问题 1: 传输超时
```
❌ LXBTimeoutError: Failed to receive all chunks after 3 retries
```

**解决方案**：
1. 检查 mock device 是否运行
2. 增加 `MAX_MISSING_RETRIES` 参数
3. 降低 `packet_loss_rate`

### 问题 2: 导入错误
```
ModuleNotFoundError: No module named 'lxb_link'
```

**解决方案**：
```bash
# 开发安装
pip install -e .

# 或设置 PYTHONPATH
export PYTHONPATH="${PYTHONPATH}:$(pwd)/src"
```

### 问题 3: 编码错误（Windows）
```
UnicodeEncodeError: 'gbk' codec can't encode...
```

**解决方案**：
- 测试文件已自动处理 UTF-8 编码
- 或者在代码开头添加：
  ```python
  import sys, io
  if sys.platform == 'win32':
      sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
  ```

---

## 🎉 开始使用

```bash
# 1. 启动模拟设备
python tests/mock_device.py 12345 0.3

# 2. 运行测试（新终端）
python tests/test_screenshot_fragmented.py

# 3. 查看结果
ls tests/screenshot_test.jpg
```

**祝你使用愉快！** 🚀
