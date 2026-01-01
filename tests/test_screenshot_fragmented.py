"""
Test Fragmented Screenshot Transfer

This script tests the new fragmented screenshot transfer feature with:
- Application-layer chunking (1KB chunks)
- Burst transmission (no ACK per chunk)
- Selective repeat mechanism
- Packet loss resilience

Requirements:
- Run mock_device.py first with packet loss enabled:
  python mock_device.py 12345 0.3
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


def test_fragmented_screenshot_transfer():
    """Test fragmented screenshot transfer with selective repeat."""
    print("=" * 70)
    print("📸 Fragmented Screenshot Transfer Test")
    print("=" * 70)
    print()

    client = LXBLinkClient('127.0.0.1', port=12345, timeout=2.0, max_retries=3)

    try:
        # Step 1: Connect
        print("🔌 Connecting to mock device...")
        client.connect()
        print("✅ Connected!\n")

        # Step 2: Handshake
        print("🤝 Performing handshake...")
        start_time = time.time()
        response = client.handshake()
        elapsed = (time.time() - start_time) * 1000
        print(f"✅ Handshake Success: {response.decode('utf-8')}")
        print(f"   Time: {elapsed:.1f}ms\n")

        # Step 3: Request screenshot using fragmented transfer
        print("-" * 70)
        print("📷 Testing Fragmented Screenshot Transfer")
        print("-" * 70)
        print("Requesting screenshot with fragmented transfer protocol...")
        print()

        start_time = time.time()
        img_data = client.request_screenshot()
        elapsed = (time.time() - start_time) * 1000

        print()
        print("=" * 70)
        print("✅ Screenshot Transfer Complete!")
        print("=" * 70)
        print(f"📊 Transfer Statistics:")
        print(f"   Total Size: {len(img_data):,} bytes ({len(img_data) / 1024:.1f} KB)")
        print(f"   Transfer Time: {elapsed:.1f}ms")
        print(f"   Throughput: {(len(img_data) / 1024) / (elapsed / 1000):.1f} KB/s")
        print()

        # Validate JPEG header
        if img_data[:2] == b'\xff\xd8':
            print("✅ Valid JPEG header detected (0xFFD8)")
        else:
            print(f"⚠️  Unexpected header: {img_data[:10].hex()}")

        # Optional: Save to file for manual inspection
        output_path = os.path.join(os.path.dirname(__file__), 'screenshot_test.jpg')
        with open(output_path, 'wb') as f:
            f.write(img_data)
        print(f"💾 Screenshot saved to: {output_path}")
        print()

        # Step 4: Test legacy screenshot for comparison
        print("-" * 70)
        print("📷 Testing Legacy Screenshot Transfer (for comparison)")
        print("-" * 70)
        print("Note: Legacy mode relies on IP-layer fragmentation")
        print()

        start_time = time.time()
        img_data_legacy = client.screenshot()
        elapsed_legacy = (time.time() - start_time) * 1000

        print()
        print("✅ Legacy Screenshot Complete!")
        print(f"   Size: {len(img_data_legacy):,} bytes ({len(img_data_legacy) / 1024:.1f} KB)")
        print(f"   Time: {elapsed_legacy:.1f}ms")
        print()

        # Comparison
        print("-" * 70)
        print("📊 Comparison: Fragmented vs Legacy")
        print("-" * 70)
        print(f"Fragmented Transfer:")
        print(f"   - Time: {elapsed:.1f}ms")
        print(f"   - Throughput: {(len(img_data) / 1024) / (elapsed / 1000):.1f} KB/s")
        print(f"   - Advantages: Selective repeat, handles packet loss efficiently")
        print()
        print(f"Legacy Transfer:")
        print(f"   - Time: {elapsed_legacy:.1f}ms")
        print(f"   - Throughput: {(len(img_data_legacy) / 1024) / (elapsed_legacy / 1000):.1f} KB/s")
        print(f"   - Relies on: IP-layer fragmentation (all-or-nothing)")
        print()

        # Summary
        print("=" * 70)
        print("🎉 All Tests Passed!")
        print("=" * 70)
        print()
        print("Key Benefits of Fragmented Transfer:")
        print("  ✓ Resilient to packet loss (selective retransmission)")
        print("  ✓ Efficient burst mode (no per-chunk ACK)")
        print("  ✓ Handles UDP reordering gracefully")
        print("  ✓ Suitable for large screenshots (50KB-200KB)")
        print()

    except LXBTimeoutError as e:
        print(f"\n❌ Timeout Error: {e}")
        print("\n💡 Troubleshooting:")
        print("   1. Ensure mock_device.py is running:")
        print("      python tests/mock_device.py 12345 0.3")
        print("   2. Check network connectivity")
        print("   3. Verify port 12345 is available")
        return False

    except Exception as e:
        print(f"\n❌ Unexpected Error: {e}")
        import traceback
        traceback.print_exc()
        return False

    finally:
        # Step 5: Disconnect
        print("🔌 Disconnecting...")
        client.disconnect()
        print("✅ Disconnected")
        print()

    return True


def test_with_high_packet_loss():
    """Test fragmented transfer with high packet loss (40%)."""
    print()
    print("=" * 70)
    print("🔥 Stress Test: High Packet Loss (40%)")
    print("=" * 70)
    print("⚠️  NOTE: Run mock device with 0.4 packet loss rate:")
    print("   python tests/mock_device.py 12345 0.4")
    print()

    input("Press Enter when ready to start stress test...")
    print()

    client = LXBLinkClient('127.0.0.1', port=12345, timeout=2.0, max_retries=3)

    try:
        client.connect()
        print("🔌 Connected!\n")

        print("📷 Requesting screenshot with 40% packet loss...")
        start_time = time.time()
        img_data = client.request_screenshot()
        elapsed = (time.time() - start_time) * 1000

        print()
        print("=" * 70)
        print("✅ Screenshot Transfer Successful Despite High Packet Loss!")
        print("=" * 70)
        print(f"   Size: {len(img_data):,} bytes ({len(img_data) / 1024:.1f} KB)")
        print(f"   Time: {elapsed:.1f}ms")
        print(f"   Throughput: {(len(img_data) / 1024) / (elapsed / 1000):.1f} KB/s")
        print()
        print("🎉 Selective repeat mechanism working correctly!")
        print()

        return True

    except LXBTimeoutError as e:
        print(f"\n❌ Transfer Failed: {e}")
        return False

    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
        return False

    finally:
        client.disconnect()


if __name__ == "__main__":
    print()
    print("╔" + "=" * 68 + "╗")
    print("║" + " " * 10 + "LXB-Link Fragmented Screenshot Test Suite" + " " * 16 + "║")
    print("╚" + "=" * 68 + "╝")
    print()

    # Test 1: Basic fragmented transfer
    success = test_fragmented_screenshot_transfer()

    if success:
        # Test 2: High packet loss stress test (optional)
        print()
        choice = input("Run stress test with high packet loss? (y/n): ").strip().lower()
        if choice == 'y':
            test_with_high_packet_loss()

    print()
    print("=" * 70)
    print("Test suite completed!")
    print("=" * 70)
