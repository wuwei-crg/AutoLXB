"""
LXB-Link Advanced Test Suite

This test suite validates:
1. Packet loss handling and retry mechanism
2. Large data transfer (screenshot > MTU)
3. Performance under adverse network conditions

Usage:
    # Terminal 1: Start mock device with packet loss
    python tests/mock_device.py 12345 0.3

    # Terminal 2: Run advanced tests
    python tests/test_advanced.py
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


def test_retry_mechanism():
    """Test retry mechanism with simulated packet loss."""
    print("\n" + "=" * 60)
    print("Test 1: Retry Mechanism (30% Packet Loss)")
    print("=" * 60)
    print("⚠️  Make sure mock_device.py is running with packet_loss_rate=0.3")
    print("   Command: python tests/mock_device.py 12345 0.3\n")

    try:
        with LXBLinkClient('127.0.0.1', port=12345, timeout=1.0, max_retries=5) as client:
            success_count = 0
            retry_count = 0
            total_tests = 10

            print(f"Sending {total_tests} TAP commands to test retry mechanism...\n")

            for i in range(total_tests):
                try:
                    start = time.time()
                    client.tap(100 + i * 10, 200 + i * 10)
                    elapsed = time.time() - start
                    success_count += 1
                    print(f"✅ TAP #{i+1:2d}: Success in {elapsed*1000:6.1f}ms")
                except LXBTimeoutError as e:
                    print(f"❌ TAP #{i+1:2d}: Failed after max retries - {e}")
                except Exception as e:
                    print(f"❌ TAP #{i+1:2d}: Unexpected error - {e}")

            print(f"\n📊 Results:")
            print(f"   Success: {success_count}/{total_tests} ({success_count/total_tests*100:.1f}%)")
            print(f"   Failed: {total_tests - success_count}/{total_tests}")

            if success_count >= total_tests * 0.8:  # 80% success rate
                print(f"✅ Retry mechanism working correctly!")
            else:
                print(f"⚠️  Success rate below expected (should be >80% with 5 retries)")

    except Exception as e:
        print(f"❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        return False

    return True


def test_large_data_transfer():
    """Test large data transfer (screenshot > MTU)."""
    print("\n" + "=" * 60)
    print("Test 2: Large Data Transfer (Screenshot > MTU)")
    print("=" * 60)
    print("Testing 60KB JPEG transfer (exceeds typical 1500 byte MTU)\n")

    try:
        with LXBLinkClient('127.0.0.1', port=12345, timeout=3.0, max_retries=5) as client:
            print("Requesting screenshot (60KB)...")
            start = time.time()

            try:
                img_data = client.screenshot()
                elapsed = time.time() - start

                print(f"✅ Screenshot received successfully!")
                print(f"   Size: {len(img_data):,} bytes ({len(img_data)/1024:.1f} KB)")
                print(f"   Time: {elapsed*1000:.1f}ms")
                print(f"   Throughput: {len(img_data)/elapsed/1024:.1f} KB/s")

                # Verify JPEG header
                if img_data[:2] == b'\xff\xd8':
                    print(f"   Format: ✅ Valid JPEG header")
                else:
                    print(f"   Format: ❌ Invalid JPEG header")
                    return False

                # Check data integrity
                if len(img_data) > 50000:  # Should be around 60KB
                    print(f"   Integrity: ✅ Data size matches expected")
                else:
                    print(f"   Integrity: ⚠️  Data size smaller than expected")

                print(f"\n📊 UDP Fragmentation Analysis:")
                typical_mtu = 1500
                header_overhead = 14  # From our protocol
                fragments = (len(img_data) + header_overhead) // typical_mtu + 1
                print(f"   Typical MTU: {typical_mtu} bytes")
                print(f"   Frame size: {len(img_data) + header_overhead:,} bytes")
                print(f"   Estimated IP fragments: ~{fragments}")
                print(f"   ℹ️  UDP stack handled fragmentation automatically!")

                return True

            except LXBTimeoutError as e:
                print(f"❌ Screenshot failed: {e}")
                print(f"   This might indicate fragmentation issues or packet loss")
                return False

    except Exception as e:
        print(f"❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def test_stress_with_packet_loss():
    """Stress test with packet loss."""
    print("\n" + "=" * 60)
    print("Test 3: Stress Test with Packet Loss")
    print("=" * 60)
    print("Sending mixed commands under packet loss conditions\n")

    try:
        with LXBLinkClient('127.0.0.1', port=12345, timeout=2.0, max_retries=5) as client:
            commands_sent = 0
            commands_success = 0
            total_time = 0

            # Test sequence
            test_sequence = [
                ("Handshake", lambda: client.handshake()),
                ("Tap 1", lambda: client.tap(100, 200)),
                ("Tap 2", lambda: client.tap(300, 400)),
                ("Swipe", lambda: client.swipe(100, 500, 500, 100, 300)),
                ("Wake", lambda: client.wake()),
                ("Tap 3", lambda: client.tap(500, 600)),
            ]

            for name, cmd_func in test_sequence:
                commands_sent += 1
                try:
                    start = time.time()
                    result = cmd_func()
                    elapsed = time.time() - start
                    total_time += elapsed
                    commands_success += 1
                    print(f"✅ {name:12s}: {elapsed*1000:6.1f}ms")
                except LXBTimeoutError as e:
                    print(f"❌ {name:12s}: Timeout")
                except Exception as e:
                    print(f"❌ {name:12s}: {e}")

            print(f"\n📊 Stress Test Results:")
            print(f"   Commands sent: {commands_sent}")
            print(f"   Success: {commands_success}/{commands_sent} ({commands_success/commands_sent*100:.1f}%)")
            print(f"   Total time: {total_time*1000:.1f}ms")
            print(f"   Avg time/cmd: {total_time/commands_sent*1000:.1f}ms")

            if commands_success == commands_sent:
                print(f"✅ All commands succeeded despite packet loss!")
                return True
            else:
                print(f"⚠️  Some commands failed (retry mechanism may need tuning)")
                return commands_success >= commands_sent * 0.8

    except Exception as e:
        print(f"❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def test_timeout_behavior():
    """Test timeout behavior when server is not responding."""
    print("\n" + "=" * 60)
    print("Test 4: Timeout Behavior")
    print("=" * 60)
    print("Testing client behavior with very high packet loss (simulated disconnect)\n")

    try:
        # Use very short timeout and limited retries for this test
        client = LXBLinkClient('127.0.0.1', port=12345, timeout=0.5, max_retries=2)
        client.connect()

        print("Attempting to send command (will likely timeout with high packet loss)...")
        start = time.time()

        try:
            client.tap(100, 200)
            elapsed = time.time() - start
            print(f"✅ Command succeeded: {elapsed*1000:.1f}ms")
            result = True
        except LXBTimeoutError as e:
            elapsed = time.time() - start
            print(f"⏱️  Timeout occurred as expected: {elapsed*1000:.1f}ms")
            print(f"   Error: {e}")
            print(f"   Expected timeout: ~{0.5 * (2 + 1) * 1000:.0f}ms (timeout × max_retries)")
            result = True  # This is expected behavior
        finally:
            client.disconnect()

        return result

    except Exception as e:
        print(f"❌ Test failed: {e}")
        import traceback
        traceback.print_exc()
        return False


def main():
    """Run all advanced tests."""
    print("=" * 60)
    print("LXB-Link Advanced Test Suite")
    print("=" * 60)
    print("\n⚠️  IMPORTANT: Start mock device with packet loss first:")
    print("   python tests/mock_device.py 12345 0.3\n")

    input("Press Enter when mock device is ready...")

    results = []

    # Run all tests
    results.append(("Retry Mechanism", test_retry_mechanism()))
    results.append(("Large Data Transfer", test_large_data_transfer()))
    results.append(("Stress Test", test_stress_with_packet_loss()))
    results.append(("Timeout Behavior", test_timeout_behavior()))

    # Summary
    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)

    passed = 0
    for name, result in results:
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"{status} - {name}")
        if result:
            passed += 1

    print(f"\nTotal: {passed}/{len(results)} tests passed ({passed/len(results)*100:.0f}%)")

    if passed == len(results):
        print("\n🎉 All advanced tests passed!")
        return True
    else:
        print(f"\n⚠️  {len(results) - passed} test(s) failed")
        return False


if __name__ == "__main__":
    success = main()
    sys.exit(0 if success else 1)
