"""
benchmark_comm local config template.

Copy to benchmark_comm/config.py and edit values.
"""

# Results
RESULTS_FILE = "benchmark_comm/results/results.jsonl"
SUMMARY_CSV = "benchmark_comm/results/summary.csv"

# Common benchmark defaults
DEFAULT_METHODS = ["udp", "adb_tcp"]
DEFAULT_COMMANDS = ["handshake", "dump", "screenshot"]
DEFAULT_PROFILES = [
    "lan_loss0",
    "lan_loss1",
    "lan_loss3",
    "lan_loss5",
    "tunnel_loss0",
    "tunnel_loss1",
    "tunnel_loss3",
    "tunnel_loss5",
]
DEFAULT_SUITE = "all"  # all | real | sim
ROUNDS_PER_CASE = 10
MAX_RETRIES = 2
BASE_TIMEOUT_SEC = 8.0

# LXB UDP device endpoint
UDP_DEVICE_IP = "192.168.1.100"
UDP_DEVICE_PORT = 12345
UDP_TAILSCALE_HOST = "phone-name.tailnet.ts.net"  # Tailscale MagicDNS

# Optional TCP device endpoint (requires phone-side TCP service)
TCP_DEVICE_IP = "192.168.1.100"
TCP_DEVICE_PORT = 12345
TCP_TAILSCALE_HOST = "phone-name.tailnet.ts.net"  # Tailscale MagicDNS

# ADB target (for adb-over-tcp set like "192.168.1.100:5555")
ADB_SERIAL = "192.168.1.100:5555"
ADB_TAILSCALE_SERIAL = "phone-name.tailnet.ts.net:5555"
ADB_BIN = "adb"

# Mocked impairment at PC sender side (application-layer).
# tunnel_* 仅用于结果分组命名；真实 tunnel 场景请用 --endpoint tailscale 单独跑。
# delay_ms: one-way base delay, jitter_ms: +/- jitter, loss_pct: per-attempt simulated drop
NET_PROFILES = {
    "lan_loss0": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 0.0},
    "lan_loss1": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 1.0},
    "lan_loss3": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 3.0},
    "lan_loss5": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 5.0},
    "tunnel_loss0": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 0.0},
    "tunnel_loss1": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 1.0},
    "tunnel_loss3": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 3.0},
    "tunnel_loss5": {"delay_ms": 80, "jitter_ms": 20, "loss_pct": 5.0},
}

# Method groups for paper reporting.
REAL_DEVICE_METHODS = ["udp", "adb_tcp"]
SIMULATION_METHODS = ["tcp"]
