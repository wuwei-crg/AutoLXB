"""
Mock Android Device Server for Testing

This script simulates an Android device that responds to LXB-Link protocol commands.
It receives commands via UDP, validates frames, and sends ACK responses.

Features:
- Packet loss simulation (configurable drop rate)
- Large data fragmentation (for screenshot transfers)
- Response delay simulation

Usage:
    python mock_device.py [port] [packet_loss_rate]

Example:
    python mock_device.py 12345 0.3    # 30% packet loss
    python mock_device.py 12345 0      # No packet loss (default)
"""

import socket
import struct
import time
import sys
import os
import io
import random

# Configure stdout to use UTF-8 encoding (fix Windows GBK encoding issue)
if sys.platform == 'win32':
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')

# Add src to path for importing ww_link package
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '../src'))

from lxb_link.protocol import ProtocolFrame
from lxb_link.constants import (
    CMD_ACK,
    CMD_TAP,
    CMD_SCREENSHOT,
    CMD_HANDSHAKE,
    CMD_WAKE,
    CMD_SWIPE,
    CMD_IMG_REQ,
    CMD_IMG_META,
    CMD_IMG_CHUNK,
    CMD_IMG_MISSING,
    CMD_IMG_FIN,
    CHUNK_SIZE,
    LXBProtocolError,
    LXBChecksumError,
)


def generate_screenshot_data(size_kb: int = 100) -> bytes:
    """
    Generate fake screenshot data (JPEG format).

    Args:
        size_kb: Size of screenshot in KB (default: 100KB)

    Returns:
        Fake JPEG data with valid header
    """
    jpeg_header = b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00'
    jpeg_body = bytes([random.randint(0, 255) for _ in range(size_kb * 1024 - len(jpeg_header))])
    return jpeg_header + jpeg_body


def handle_screenshot_request(
    sock: socket.socket,
    addr: tuple,
    seq: int,
    packet_loss_rate: float = 0.0,
    chunk_size: int = CHUNK_SIZE
) -> None:
    """
    Handle fragmented screenshot transfer request (Server-Pull Model).

    Flow:
    1. Generate/load screenshot data
    2. Split into chunks
    3. Send IMG_META (metadata)
    4. Burst send all IMG_CHUNK packets (no ACK waiting)
    5. Enter retransmission loop:
       - Wait for IMG_MISSING or IMG_FIN
       - Retransmit requested chunks if IMG_MISSING received
       - Exit if IMG_FIN received

    Args:
        sock: UDP socket for communication
        addr: Client address tuple (ip, port)
        seq: Original request sequence number
        packet_loss_rate: Probability of dropping packets (for testing)
        chunk_size: Size of each chunk in bytes
    """
    print(f"\n{'='*60}")
    print(f"📷 [FRAGMENTED SCREENSHOT] Starting transfer...")
    print(f"{'='*60}")

    # Step 1: Generate screenshot data
    img_data = generate_screenshot_data(size_kb=100)  # 100KB screenshot
    img_id = random.randint(1000, 9999)
    total_size = len(img_data)
    num_chunks = (total_size + chunk_size - 1) // chunk_size  # Ceiling division

    print(f"📊 Transfer Info:")
    print(f"   Image ID: {img_id}")
    print(f"   Total Size: {total_size:,} bytes ({total_size / 1024:.1f} KB)")
    print(f"   Chunk Size: {chunk_size} bytes")
    print(f"   Total Chunks: {num_chunks}")

    # Step 2: Split into chunks
    chunks = []
    for i in range(num_chunks):
        start = i * chunk_size
        end = min(start + chunk_size, total_size)
        chunks.append(img_data[start:end])

    # Step 3: Send IMG_META
    meta_frame = ProtocolFrame.pack_img_meta(seq, img_id, total_size, num_chunks)

    # Simulate packet loss for IMG_META
    if random.random() < packet_loss_rate:
        print(f"💥 [PACKET LOSS] Dropping IMG_META!")
    else:
        sock.sendto(meta_frame, addr)
        print(f"✅ Sent IMG_META (img_id={img_id}, chunks={num_chunks})")

    # Step 3.5: Wait for ACK from client confirming META received
    print(f"⏳ Waiting for client ACK to confirm META received...")
    sock.settimeout(2.0)  # 2 second timeout for ACK

    try:
        while True:
            try:
                data, recv_addr = sock.recvfrom(65536)

                # Only process if from same client
                if recv_addr != addr:
                    continue

                recv_seq, recv_cmd, recv_payload = ProtocolFrame.unpack(data)

                if recv_cmd == CMD_ACK and recv_seq == seq:
                    print(f"✅ Received ACK for IMG_META - Client ready!")
                    break
                else:
                    print(f"⚠️  Unexpected response: cmd=0x{recv_cmd:02X}, seq={recv_seq}")

            except (LXBProtocolError, LXBChecksumError) as e:
                print(f"⚠️  Invalid frame: {e}")
                continue

    except socket.timeout:
        print(f"⚠️  No ACK received, proceeding anyway...")

    # Reset timeout
    sock.settimeout(None)

    # Step 4: Burst send all chunks
    print(f"\n🚀 [BURST MODE] Sending all {num_chunks} chunks...")
    sent_count = 0
    dropped_count = 0

    for chunk_index in range(num_chunks):
        chunk_frame = ProtocolFrame.pack_img_chunk(seq, chunk_index, chunks[chunk_index])

        # Simulate packet loss
        if random.random() < packet_loss_rate:
            dropped_count += 1
            if dropped_count <= 5:  # Only print first 5 drops
                print(f"   💥 Chunk {chunk_index}: DROPPED")
        else:
            sock.sendto(chunk_frame, addr)
            sent_count += 1
            if chunk_index < 3 or chunk_index >= num_chunks - 3:  # Print first and last 3
                print(f"   ✅ Chunk {chunk_index}: Sent ({len(chunks[chunk_index])} bytes)")
            elif chunk_index == 3:
                print(f"   ... (sending chunks 3-{num_chunks-4}) ...")

    print(f"\n📊 Burst Summary: Sent={sent_count}/{num_chunks}, Dropped={dropped_count}")

    # Step 5: Wait for client response (IMG_MISSING or IMG_FIN)
    print(f"\n⏳ Waiting for client response (IMG_MISSING or IMG_FIN)...")

    sock.settimeout(5.0)  # 5 second timeout for client response
    max_retries = 5
    retry_count = 0

    while retry_count < max_retries:
        try:
            data, recv_addr = sock.recvfrom(65536)

            # Only process if from same client
            if recv_addr != addr:
                print(f"⚠️  Ignoring packet from different client: {recv_addr}")
                continue

            try:
                recv_seq, recv_cmd, recv_payload = ProtocolFrame.unpack(data)

                if recv_cmd == CMD_IMG_FIN:
                    print(f"✅ [IMG_FIN] Transfer complete! Client acknowledged.")
                    print(f"{'='*60}\n")
                    break

                elif recv_cmd == CMD_IMG_MISSING:
                    missing_indices = ProtocolFrame.unpack_img_missing(recv_payload)
                    print(f"\n📬 [IMG_MISSING] Client requests {len(missing_indices)} chunks:")
                    print(f"   Missing indices: {missing_indices[:20]}{'...' if len(missing_indices) > 20 else ''}")

                    # Retransmit missing chunks
                    print(f"🔄 Retransmitting missing chunks...")
                    retrans_count = 0
                    retrans_dropped = 0

                    for chunk_index in missing_indices:
                        if 0 <= chunk_index < num_chunks:
                            chunk_frame = ProtocolFrame.pack_img_chunk(
                                recv_seq, chunk_index, chunks[chunk_index]
                            )

                            # Simulate packet loss for retransmission
                            if random.random() < packet_loss_rate:
                                retrans_dropped += 1
                                if retrans_dropped <= 3:
                                    print(f"   💥 Chunk {chunk_index}: DROPPED (retry)")
                            else:
                                sock.sendto(chunk_frame, addr)
                                retrans_count += 1
                                if retrans_count <= 5:
                                    print(f"   ✅ Chunk {chunk_index}: Retransmitted")

                    print(f"📊 Retransmit Summary: Sent={retrans_count}, Dropped={retrans_dropped}")
                    retry_count += 1

                else:
                    print(f"⚠️  Unexpected command during transfer: 0x{recv_cmd:02X}")

            except (LXBProtocolError, LXBChecksumError) as e:
                print(f"❌ Invalid frame received: {e}")
                continue

        except socket.timeout:
            print(f"⏱️  Timeout waiting for client response (retry {retry_count + 1}/{max_retries})")
            retry_count += 1

    # Reset socket timeout
    sock.settimeout(None)

    if retry_count >= max_retries:
        print(f"❌ [TIMEOUT] No response from client after {max_retries} retries")
        print(f"{'='*60}\n")


def run_mock_device(port=12345, packet_loss_rate=0.0):
    """
    Run mock Android device server with configurable packet loss.

    Args:
        port: UDP port to listen on (default: 12345)
        packet_loss_rate: Probability of dropping packets (0.0 to 1.0)
    """
    # Create UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('127.0.0.1', port))

    print("=" * 60)
    print(f"📱 [MockDevice] Fake Android running on 127.0.0.1:{port}")
    print(f"📊 Packet Loss Rate: {packet_loss_rate * 100:.1f}%")
    print("=" * 60)
    print("Waiting for commands...\n")

    frame_count = 0
    dropped_count = 0

    while True:
        try:
            # Receive data
            data, addr = sock.recvfrom(65536)
            frame_count += 1

            print(f"\n[Frame #{frame_count}] Received {len(data)} bytes from {addr}")

            # Parse and validate frame
            try:
                seq, cmd, payload = ProtocolFrame.unpack(data)
                print(f"✅ Valid frame: Seq={seq}, Cmd=0x{cmd:02X}, Payload={len(payload)} bytes")

            except LXBChecksumError as e:
                print(f"❌ CRC Error: {e}")
                continue
            except LXBProtocolError as e:
                print(f"❌ Protocol Error: {e}")
                continue
            except Exception as e:
                print(f"❌ Unexpected Error: {e}")
                continue

            # Simulate packet loss (drop ACK response)
            if random.random() < packet_loss_rate:
                dropped_count += 1
                drop_rate_current = (dropped_count / frame_count) * 100
                print(f"💥 [PACKET LOSS] Dropping ACK! (Total: {dropped_count}/{frame_count} = {drop_rate_current:.1f}%)")
                continue

            # Simulate command processing
            payload_to_send = b''

            if cmd == CMD_HANDSHAKE:
                print(f"   🤝 [Action] Handshake received")
                payload_to_send = b'LXB-Link v1.0'

            elif cmd == CMD_TAP:
                if len(payload) == 4:
                    x, y = struct.unpack('<HH', payload)
                    print(f"   👉 [Action] Tap at ({x}, {y})")
                    payload_to_send = b'TAP_OK'
                else:
                    print(f"   ⚠️  Invalid TAP payload length: {len(payload)}")

            elif cmd == CMD_SWIPE:
                if len(payload) == 10:
                    x1, y1, x2, y2, duration = struct.unpack('<HHHHH', payload)
                    print(f"   👆 [Action] Swipe ({x1},{y1}) -> ({x2},{y2}), duration={duration}ms")
                    payload_to_send = b'SWIPE_OK'
                else:
                    print(f"   ⚠️  Invalid SWIPE payload length: {len(payload)}")

            elif cmd == CMD_SCREENSHOT:
                print(f"   📷 [Action] Taking screenshot...")
                # Simulate realistic screenshot size (60KB JPEG image)
                # Note: UDP max payload is ~65507 bytes (65535 - 8 byte UDP header - 20 byte IP header)
                # Our protocol adds 14 bytes header + 4 bytes CRC = 18 bytes overhead
                # Safe payload size: 65507 - 18 = 65489 bytes, we use 60KB to be safe
                jpeg_header = b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00'
                jpeg_body = bytes([random.randint(0, 255) for _ in range(60 * 1024 - len(jpeg_header))])
                payload_to_send = jpeg_header + jpeg_body
                print(f"   📤 Sending {len(payload_to_send):,} bytes of fake JPEG data")
                print(f"   ⚠️  WARNING: Payload size ({len(payload_to_send):,} bytes) exceeds typical MTU (1500 bytes)")
                print(f"   ℹ️  This will test UDP fragmentation at IP layer")

            elif cmd == CMD_WAKE:
                print(f"   🌅 [Action] Wake device")
                payload_to_send = b'WAKE_OK'

            elif cmd == CMD_IMG_REQ:
                print(f"   📸 [Action] Screenshot request (fragmented transfer)")
                # Handle fragmented screenshot transfer (blocking operation)
                handle_screenshot_request(sock, addr, seq, packet_loss_rate)
                # Skip normal ACK sending since we handled the entire transfer
                continue

            else:
                print(f"   ⚠️  Unknown command: 0x{cmd:02X}")
                payload_to_send = b'UNKNOWN_CMD'

            # Optional: Simulate processing delay
            # time.sleep(0.05)

            # Build ACK frame with same sequence number
            ack_bytes = ProtocolFrame.pack_ack(seq)

            # If we have payload to send, rebuild the ACK with payload
            if payload_to_send:
                ack_bytes = ProtocolFrame.pack(seq, CMD_ACK, payload_to_send)

            # Send ACK response
            sock.sendto(ack_bytes, addr)
            print(f"   📤 Sent ACK (Seq={seq}, Payload={len(payload_to_send)} bytes)")

        except KeyboardInterrupt:
            print("\n\n🛑 Mock device shutting down...")
            print(f"📊 Final Statistics:")
            print(f"   Total Frames: {frame_count}")
            print(f"   Dropped: {dropped_count}")
            print(f"   Drop Rate: {(dropped_count / max(frame_count, 1)) * 100:.1f}%")
            break
        except Exception as e:
            print(f"❌ Unexpected error: {e}")
            import traceback
            traceback.print_exc()

    sock.close()
    print("Mock device stopped.")


if __name__ == "__main__":
    # Allow custom port and packet loss rate via command line arguments
    port = 12345
    packet_loss_rate = 0.0

    if len(sys.argv) > 1:
        try:
            port = int(sys.argv[1])
        except ValueError:
            print(f"Invalid port: {sys.argv[1]}, using default 12345")

    if len(sys.argv) > 2:
        try:
            packet_loss_rate = float(sys.argv[2])
            if not (0.0 <= packet_loss_rate <= 1.0):
                print(f"Warning: packet_loss_rate should be between 0.0 and 1.0")
                packet_loss_rate = max(0.0, min(1.0, packet_loss_rate))
        except ValueError:
            print(f"Invalid packet loss rate: {sys.argv[2]}, using default 0.0")

    run_mock_device(port, packet_loss_rate)