"""
Benchmark configuration template.

Copy to benchmark/config.py and fill in real values.
"""

# Device
DEVICE_IP = "192.168.0.100"
DEVICE_PORT = 12345

# Text LLM (planning / Text-ReAct)
TEXT_LLM_BASE_URL = "https://api.example.com/v1"
TEXT_LLM_API_KEY = "YOUR_TEXT_LLM_API_KEY"
TEXT_LLM_MODEL = "YOUR_TEXT_LLM_MODEL"

# VLM (VLM-ReAct / semantic-map runner)
VLM_BASE_URL = "https://api.example.com/v1"
VLM_API_KEY = "YOUR_VLM_API_KEY"
VLM_MODEL = "YOUR_VLM_MODEL"

# Verifier VLM (external acceptance model)
# Usually same base_url/api_key as above, but can use a different model.
VERIFY_BASE_URL = VLM_BASE_URL
VERIFY_API_KEY = VLM_API_KEY
VERIFY_MODEL = "gemini-3-flash-preview"

# Pricing (USD per token)
# Update to match your provider's actual pricing.
PRICING: dict[str, dict[str, float]] = {
    "YOUR_TEXT_LLM_MODEL": {"input": 0.0, "output": 0.0},
    "YOUR_VLM_MODEL": {"input": 0.0, "output": 0.0},
}

# Benchmark settings
MAX_REACT_STEPS = 20
STEP_PAUSE_SEC = 1.2
APP_LAUNCH_WAIT = 3.0
TRIALS_PER_TASK = 3

# Output
RESULTS_DIR = "benchmark/results"
VLM_STRUCTURED_LOG_FILE = "benchmark/logs/vlm_react_steps.jsonl"

# VLM coordinate fallback (used when probe unavailable)
VLM_COORD_NORM_MAX = 1000
