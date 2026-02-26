# benchmark_comm

通信层基准测试（PC 发指令，手机端响应）。
当前对比 3 个真实分组：`udp` / `tcp` / `adb_tcp`。

网络环境改为**固定延迟 + 多丢包率**：
- 无 tunnel：`lan_loss0` / `lan_loss1` / `lan_loss3` / `lan_loss5`
- 有 tunnel：`tunnel_loss0` / `tunnel_loss1` / `tunnel_loss3` / `tunnel_loss5`

> 说明：`tunnel` 不是 PC 模拟隧道。真实 tunnel 请在手机+电脑开启 Tailscale，并把手机切到移动网络后，用 `--endpoint tailscale` 单独跑。

## 1) 准备
1. 复制配置：
   - `copy benchmark_comm\config.example.py benchmark_comm\config.py`
2. 修改 `benchmark_comm/config.py`：
   - 局域网地址：`UDP_DEVICE_IP` / `TCP_DEVICE_IP` / `ADB_SERIAL`
   - Tailscale 地址：`UDP_TAILSCALE_HOST` / `TCP_TAILSCALE_HOST` / `ADB_TAILSCALE_SERIAL`
3. 手机侧准备：
   - `udp`：LXB 服务已启动
   - `tcp`：LXB Ignition 里启动 TCP Mock
   - `adb_tcp`：`adb tcpip 5555` 后可连接

## 2) 三个分组单独测试（无 tunnel）

```bash
python -m benchmark_comm.run --endpoint lan --methods udp --profiles lan_loss0 lan_loss1 lan_loss3 lan_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_udp_lan.jsonl --summary benchmark_comm/results/summary_udp_lan.csv
```

```bash
python -m benchmark_comm.run --endpoint lan --methods tcp --profiles lan_loss0 lan_loss1 lan_loss3 lan_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_tcp_lan.jsonl --summary benchmark_comm/results/summary_tcp_lan.csv
```

```bash
python -m benchmark_comm.run --endpoint lan --methods adb_tcp --profiles lan_loss0 lan_loss1 lan_loss3 lan_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_adb_tcp_lan.jsonl --summary benchmark_comm/results/summary_adb_tcp_lan.csv
```

## 3) 三个分组单独测试（有 tunnel / tailscale）

先开 Tailscale，再跑：

```bash
python -m benchmark_comm.run --endpoint tailscale --methods udp --profiles tunnel_loss0 tunnel_loss1 tunnel_loss3 tunnel_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_udp_tunnel.jsonl --summary benchmark_comm/results/summary_udp_tunnel.csv
```

```bash
python -m benchmark_comm.run --endpoint tailscale --methods tcp --profiles tunnel_loss0 tunnel_loss1 tunnel_loss3 tunnel_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_tcp_tunnel.jsonl --summary benchmark_comm/results/summary_tcp_tunnel.csv
```

```bash
python -m benchmark_comm.run --endpoint tailscale --methods adb_tcp --profiles tunnel_loss0 tunnel_loss1 tunnel_loss3 tunnel_loss5 --commands handshake dump screenshot --rounds 20 --results benchmark_comm/results/results_adb_tcp_tunnel.jsonl --summary benchmark_comm/results/summary_adb_tcp_tunnel.csv
```

## 4) 合并结果 + 生成总汇总

```bash
python - <<'PY'
from pathlib import Path
files = [
    Path("benchmark_comm/results/results_udp_lan.jsonl"),
    Path("benchmark_comm/results/results_tcp_lan.jsonl"),
    Path("benchmark_comm/results/results_adb_tcp_lan.jsonl"),
    Path("benchmark_comm/results/results_udp_tunnel.jsonl"),
    Path("benchmark_comm/results/results_tcp_tunnel.jsonl"),
    Path("benchmark_comm/results/results_adb_tcp_tunnel.jsonl"),
]
out = Path("benchmark_comm/results/results_all_real.jsonl")
out.parent.mkdir(parents=True, exist_ok=True)
with out.open("w", encoding="utf-8") as w:
    for f in files:
        if f.exists():
            w.write(f.read_text(encoding="utf-8"))
print(f"[ok] merged -> {out}")
PY
```

```bash
python -m benchmark_comm.report --file benchmark_comm/results/results_all_real.jsonl --csv benchmark_comm/results/summary_all_real.csv
```

## 5) 生成图表

```bash
python - <<'PY'
import pandas as pd
import matplotlib.pyplot as plt
from pathlib import Path

df = pd.read_csv("benchmark_comm/results/summary_all_real.csv")
fig_dir = Path("benchmark_comm/results/figures")
fig_dir.mkdir(parents=True, exist_ok=True)

# 大包对比（screenshot）
sub = df[df["command"] == "screenshot"].copy()

# 图1：P95 延迟
pt = sub.pivot_table(index="profile", columns="method", values="latency_p95_ms")
ax = pt.plot(kind="bar", figsize=(9, 4))
ax.set_ylabel("Latency P95 (ms)")
ax.set_xlabel("Profile")
ax.set_title("P95 Latency by Method")
plt.tight_layout()
plt.savefig(fig_dir / "latency_p95_screenshot.png", dpi=200)
plt.close()

# 图2：成功率
pt2 = sub.pivot_table(index="profile", columns="method", values="success_rate")
ax = pt2.plot(kind="bar", figsize=(9, 4), ylim=(0, 1.05))
ax.set_ylabel("Success Rate")
ax.set_xlabel("Profile")
ax.set_title("Success Rate by Method")
plt.tight_layout()
plt.savefig(fig_dir / "success_rate_screenshot.png", dpi=200)
plt.close()

# 图3：总时间（秒）
pt3 = sub.pivot_table(index="profile", columns="method", values="total_time_sec")
ax = pt3.plot(kind="bar", figsize=(9, 4))
ax.set_ylabel("Total Time (s)")
ax.set_xlabel("Profile")
ax.set_title("Total Time by Method")
plt.tight_layout()
plt.savefig(fig_dir / "total_time_screenshot.png", dpi=200)
plt.close()

print("[ok] figures ->", fig_dir)
PY
```

## 输出
- 明细：`benchmark_comm/results/*.jsonl`
- 汇总：`benchmark_comm/results/*.csv`
- 图表：`benchmark_comm/results/figures/*.png`
