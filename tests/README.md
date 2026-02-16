# Tests

This folder contains protocol/client and integration-oriented tests.

## Layout

- `unit/`: unit tests for protocol and data handling.
- `integration/`: end-to-end style tests (when available).
- `legacy/`: older test scripts kept for reference.
- `logs/`: generated test logs.
- `run_all_tests.py`: grouped test runner.

## Run

Run all grouped tests:

```bash
python tests/run_all_tests.py
```

Run one group:

```bash
python tests/run_all_tests.py unit
python tests/run_all_tests.py integration
python tests/run_all_tests.py legacy
```

Run a single file:

```bash
python -m pytest tests/unit/test_string_pool.py -v
```

## Notes

- Some tests assume protocol-level mock behavior.
- Device-required tests should be isolated from pure unit tests.
- Keep logs concise and deterministic for regression comparison.
