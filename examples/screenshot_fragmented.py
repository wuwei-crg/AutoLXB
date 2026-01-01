"""
Fragmented Screenshot Transfer Example

This example demonstrates how to use the new fragmented screenshot transfer
feature which is more efficient for large screenshots (50KB-200KB).

Key Features:
- Application-layer chunking (1KB chunks)
- Burst transmission (no per-chunk ACK)
- Selective repeat (only retransmit missing chunks)
- Resilient to packet loss and reordering
"""

import sys
import os

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

from lxb_link import LXBLinkClient


def main():
    """Example: Request screenshot using fragmented transfer."""
    print("Fragmented Screenshot Transfer Example")
    print("=" * 50)
    print()

    # Create client
    client = LXBLinkClient('192.168.1.100', port=12345)

    try:
        # Connect
        client.connect()
        print("✅ Connected to device")

        # Handshake
        client.handshake()
        print("✅ Handshake successful")
        print()

        # Request screenshot using fragmented transfer (recommended)
        print("📸 Requesting screenshot (fragmented mode)...")
        img_data = client.request_screenshot()

        print(f"✅ Screenshot received: {len(img_data)} bytes")
        print(f"   Size: {len(img_data) / 1024:.1f} KB")
        print()

        # Save screenshot
        with open('screenshot_fragmented.jpg', 'wb') as f:
            f.write(img_data)

        print("💾 Screenshot saved to: screenshot_fragmented.jpg")
        print()

        # For comparison: Legacy single-frame mode
        print("📸 Requesting screenshot (legacy mode)...")
        img_data_legacy = client.screenshot()

        print(f"✅ Screenshot received: {len(img_data_legacy)} bytes")
        print()

        print("=" * 50)
        print("✅ Example completed successfully!")
        print()
        print("💡 Tip: Use request_screenshot() for large screenshots")
        print("   - Better performance with packet loss")
        print("   - Selective retransmission")
        print("   - Handles reordering gracefully")

    except Exception as e:
        print(f"❌ Error: {e}")
        import traceback
        traceback.print_exc()

    finally:
        # Disconnect
        client.disconnect()
        print("\n🔌 Disconnected")


def context_manager_example():
    """Example: Using context manager with fragmented transfer."""
    print("\nContext Manager Example")
    print("=" * 50)
    print()

    with LXBLinkClient('192.168.1.100', port=12345) as client:
        # Handshake
        client.handshake()

        # Request screenshot
        img_data = client.request_screenshot()

        # Save to file
        with open('screenshot.jpg', 'wb') as f:
            f.write(img_data)

        print(f"✅ Screenshot saved: {len(img_data) / 1024:.1f} KB")


if __name__ == "__main__":
    main()

    # Uncomment to run context manager example
    # context_manager_example()
