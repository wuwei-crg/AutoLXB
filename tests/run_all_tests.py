#!/usr/bin/env python3
"""
LXB-Link Quick Test Runner

This script runs both basic and advanced tests automatically.
It starts the mock device server and runs tests in sequence.
"""

import subprocess
import time
import sys
import os
import signal

def run_test_suite():
    """Run complete test suite."""

    print("=" * 70)
    print("LXB-Link Automated Test Suite")
    print("=" * 70)
    print()

    # Test configurations
    test_configs = [
        {
            "name": "Basic Tests (No Packet Loss)",
            "packet_loss": 0.0,
            "script": "test_basic.py",
            "timeout": 30
        },
        {
            "name": "Advanced Tests (40% Packet Loss)",
            "packet_loss": 0.4,
            "script": "test_advanced.py",
            "timeout": 60,
            "requires_input": True
        }
    ]

    results = []

    for config in test_configs:
        print(f"\n{'=' * 70}")
        print(f"Running: {config['name']}")
        print(f"Packet Loss: {config['packet_loss'] * 100:.0f}%")
        print(f"{'=' * 70}\n")

        # Start mock device
        print(f"Starting mock device with {config['packet_loss'] * 100:.0f}% packet loss...")
        mock_process = subprocess.Popen(
            [sys.executable, "mock_device.py", "12345", str(config['packet_loss'])],
            cwd=os.path.join(os.path.dirname(__file__)),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )

        # Wait for server to start
        time.sleep(2)

        try:
            # Run test
            print(f"Running {config['script']}...\n")

            test_input = b"\n" if config.get("requires_input") else None

            result = subprocess.run(
                [sys.executable, config['script']],
                cwd=os.path.join(os.path.dirname(__file__)),
                input=test_input,
                capture_output=True,
                timeout=config['timeout']
            )

            # Print output
            output = result.stdout.decode('utf-8', errors='replace')
            print(output)

            # Check result
            success = result.returncode == 0
            results.append((config['name'], success))

            if success:
                print(f"✅ {config['name']} PASSED")
            else:
                print(f"❌ {config['name']} FAILED")
                if result.stderr:
                    print(f"Error: {result.stderr.decode('utf-8', errors='replace')}")

        except subprocess.TimeoutExpired:
            print(f"⏱️  {config['name']} TIMEOUT")
            results.append((config['name'], False))

        except Exception as e:
            print(f"❌ {config['name']} ERROR: {e}")
            results.append((config['name'], False))

        finally:
            # Stop mock device
            print(f"\nStopping mock device...")
            mock_process.terminate()
            try:
                mock_process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                mock_process.kill()

            # Wait before next test
            time.sleep(2)

    # Print summary
    print("\n" + "=" * 70)
    print("TEST SUMMARY")
    print("=" * 70)

    for name, success in results:
        status = "✅ PASS" if success else "❌ FAIL"
        print(f"{status} - {name}")

    passed = sum(1 for _, success in results if success)
    total = len(results)

    print(f"\nTotal: {passed}/{total} test suites passed ({passed/total*100:.0f}%)")

    if passed == total:
        print("\n🎉 All test suites passed!")
        return 0
    else:
        print(f"\n⚠️  {total - passed} test suite(s) failed")
        return 1


if __name__ == "__main__":
    try:
        exit_code = run_test_suite()
        sys.exit(exit_code)
    except KeyboardInterrupt:
        print("\n\n⚠️  Test suite interrupted by user")
        sys.exit(1)
