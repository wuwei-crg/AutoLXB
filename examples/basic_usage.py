"""
LXB-Link Basic Usage Example

This example demonstrates how to use the LXB-Link client to control
an Android device over UDP.
"""

import sys
import os
import time

# Add src to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

from lxb_link import LXBLinkClient, WWTimeoutError


def main():
    """Basic usage example."""

    # Device configuration
    DEVICE_IP = '192.168.1.100'  # Change to your device IP
    DEVICE_PORT = 12345

    # Method 1: Using context manager (recommended)
    print("=== Method 1: Using Context Manager ===\n")

    try:
        with LXBLinkClient(DEVICE_IP, DEVICE_PORT, timeout=2.0) as client:
            # Perform handshake
            print("1. Handshake...")
            response = client.handshake()
            print(f"   Response: {response}\n")

            # Tap at coordinate (500, 800)
            print("2. Tap at (500, 800)...")
            client.tap(500, 800)
            print("   Tap successful\n")

            # Swipe gesture
            print("3. Swipe from (100, 500) to (500, 100)...")
            client.swipe(100, 500, 500, 100, duration=300)
            print("   Swipe successful\n")

            # Take screenshot
            print("4. Take screenshot...")
            img_data = client.screenshot()
            print(f"   Screenshot received: {len(img_data)} bytes\n")

            # Save screenshot to file
            with open('screenshot.jpg', 'wb') as f:
                f.write(img_data)
            print("   Screenshot saved to screenshot.jpg\n")

            # Wake device
            print("5. Wake device...")
            client.wake()
            print("   Wake successful\n")

    except WWTimeoutError as e:
        print(f"Timeout error: {e}")
    except Exception as e:
        print(f"Error: {e}")

    # Method 2: Manual connection management
    print("\n=== Method 2: Manual Connection ===\n")

    client = LXBLinkClient(DEVICE_IP, DEVICE_PORT)

    try:
        # Connect to device
        client.connect()
        print("Connected to device\n")

        # Send commands
        print("Sending tap command...")
        client.tap(100, 200)
        print("Tap successful\n")

        # Send custom command
        print("Sending custom command...")
        response = client.send_custom_command(0xFF, b'Hello', reliable=True)
        print(f"Custom command response: {response}\n")

    except Exception as e:
        print(f"Error: {e}")
    finally:
        # Always disconnect
        client.disconnect()
        print("Disconnected from device")


if __name__ == "__main__":
    main()
