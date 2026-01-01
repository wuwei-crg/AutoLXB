"""
LXB-Link Client Test Suite

This script tests the LXB-Link client against a mock device server.
Make sure to run mock_device.py first before running this test.

Usage:
    # Terminal 1: Start mock device
    python tests/mock_device.py

    # Terminal 2: Run tests
    python tests/test_basic.py
"""

import sys
import os
import time
import io

# Configure stdout to use UTF-8 encoding (fix Windows GBK encoding issue)
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

# Add src to path for importing ww_link package
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

from lxb_link import LXBLinkClient, LXBTimeoutError


def test_client():
    """Run comprehensive tests against mock device."""

    print("=" * 60)
    print("LXB-Link Client Test Suite")
    print("=" * 60)
    print("Make sure mock_device.py is running on 127.0.0.1:12345\n")

    # Create client with context manager
    try:
        with LXBLinkClient('127.0.0.1', port=12345, timeout=2.0) as client:

            # Test 1: Handshake
            print("\n--- Test 1: Handshake ---")
            try:
                start = time.time()
                response = client.handshake()
                elapsed = time.time() - start
                print(f"✅ Handshake Success!")
                print(f"   Response: {response}")
                print(f"   Time: {elapsed*1000:.2f}ms")
            except LXBTimeoutError as e:
                print(f"❌ Handshake Failed: {e}")
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")

            # Test 2: Tap command
            print("\n--- Test 2: Tap Command ---")
            try:
                start = time.time()
                response = client.tap(100, 200)
                elapsed = time.time() - start
                print(f"✅ Tap Success!")
                print(f"   Response: {response}")
                print(f"   Time: {elapsed*1000:.2f}ms")
            except LXBTimeoutError as e:
                print(f"❌ Tap Failed: {e}")
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")

            # Test 3: Multiple taps (test sequence numbers)
            print("\n--- Test 3: Multiple Taps (Sequence Test) ---")
            coordinates = [(100, 100), (200, 200), (300, 300)]
            for i, (x, y) in enumerate(coordinates, 1):
                try:
                    start = time.time()
                    response = client.tap(x, y)
                    elapsed = time.time() - start
                    print(f"✅ Tap #{i} at ({x}, {y}): {elapsed*1000:.2f}ms")
                except Exception as e:
                    print(f"❌ Tap #{i} Failed: {e}")

            # Test 4: Swipe command
            print("\n--- Test 4: Swipe Command ---")
            try:
                start = time.time()
                response = client.swipe(100, 500, 500, 100, duration=300)
                elapsed = time.time() - start
                print(f"✅ Swipe Success!")
                print(f"   Response: {response}")
                print(f"   Time: {elapsed*1000:.2f}ms")
            except LXBTimeoutError as e:
                print(f"❌ Swipe Failed: {e}")
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")

            # Test 5: Screenshot command
            print("\n--- Test 5: Screenshot Command ---")
            try:
                start = time.time()
                img_data = client.screenshot()
                elapsed = time.time() - start
                print(f"✅ Screenshot Success!")
                print(f"   Received: {len(img_data)} bytes")
                print(f"   Time: {elapsed*1000:.2f}ms")
                # Check JPEG header
                if img_data[:2] == b'\xff\xd8':
                    print(f"   Format: Valid JPEG header detected")
            except LXBTimeoutError as e:
                print(f"❌ Screenshot Failed: {e}")
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")

            # Test 6: Wake command
            print("\n--- Test 6: Wake Command ---")
            try:
                start = time.time()
                response = client.wake()
                elapsed = time.time() - start
                print(f"✅ Wake Success!")
                print(f"   Response: {response}")
                print(f"   Time: {elapsed*1000:.2f}ms")
            except LXBTimeoutError as e:
                print(f"❌ Wake Failed: {e}")
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")

            # Test 7: Custom command
            print("\n--- Test 7: Custom Command ---")
            try:
                start = time.time()
                response = client.send_custom_command(0xFF, b'TestPayload', reliable=True)
                elapsed = time.time() - start
                print(f"✅ Custom Command Success!")
                print(f"   Response: {response}")
                print(f"   Time: {elapsed*1000:.2f}ms")
            except Exception as e:
                print(f"❌ Custom Command Failed: {e}")

    except ConnectionRefusedError:
        print("\n❌ ERROR: Cannot connect to mock device!")
        print("   Make sure mock_device.py is running first.")
        return False
    except Exception as e:
        print(f"\n❌ Fatal Error: {e}")
        import traceback
        traceback.print_exc()
        return False

    print("\n" + "=" * 60)
    print("✅ All tests completed!")
    print("=" * 60)
    return True


if __name__ == "__main__":
    success = test_client()
    sys.exit(0 if success else 1)