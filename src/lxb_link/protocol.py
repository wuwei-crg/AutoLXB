"""
LXB-Link Protocol Layer

This module handles binary frame packing and unpacking operations with CRC32
validation for the LXB-Link reliable UDP protocol.

Frame Format (Little Endian):
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│ Magic   │ Ver     │ Seq     │ Cmd     │ Len     │ Data    │ CRC32   │
│ 2 bytes │ 1 byte  │ 4 bytes │ 1 byte  │ 2 bytes │ N bytes │ 4 bytes │
│ 0xAA55  │ 0x01    │ uint32  │ uint8   │ uint16  │ payload │ uint32  │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘
"""

import struct
import zlib
from typing import Tuple, Optional

from .constants import (
    MAGIC,
    VERSION,
    HEADER_SIZE,
    MIN_FRAME_SIZE,
    MAX_PAYLOAD_SIZE,
    CRC_SIZE,
    ERR_INVALID_MAGIC,
    ERR_INVALID_VERSION,
    ERR_INVALID_CRC,
    ERR_INVALID_PAYLOAD_SIZE,
    LXBProtocolError,
    LXBChecksumError,
)


class ProtocolFrame:
    """
    LXB-Link protocol frame handler for packing and unpacking binary data.

    This class provides methods to construct binary frames from command data
    and parse received frames with comprehensive validation.
    """

    # Struct format for frame header (Little Endian)
    # H: unsigned short (2 bytes) - Magic
    # B: unsigned char (1 byte) - Version
    # I: unsigned int (4 bytes) - Sequence
    # B: unsigned char (1 byte) - Command
    # H: unsigned short (2 bytes) - Payload Length
    HEADER_FORMAT = '<HBIBH'

    # Struct format for CRC32 (Little Endian)
    # I: unsigned int (4 bytes) - CRC32
    CRC_FORMAT = '<I'

    @staticmethod
    def pack(seq: int, cmd: int, payload: bytes = b'') -> bytes:
        """
        Pack command data into a binary frame with CRC32 checksum.

        Args:
            seq: Sequence number (0 to 2^32-1)
            cmd: Command ID (0 to 255)
            payload: Optional payload data (default: empty bytes)

        Returns:
            Complete binary frame with header, payload, and CRC32

        Raises:
            LXBProtocolError: If payload size exceeds maximum allowed size
        """
        # Validate payload size
        payload_len = len(payload)
        if payload_len > MAX_PAYLOAD_SIZE:
            raise LXBProtocolError(
                f"Payload size {payload_len} exceeds maximum {MAX_PAYLOAD_SIZE}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        # Construct header
        header = struct.pack(
            ProtocolFrame.HEADER_FORMAT,
            MAGIC,           # Magic number (0xAA55)
            VERSION,         # Protocol version (0x01)
            seq,             # Sequence number
            cmd,             # Command ID
            payload_len      # Payload length
        )

        # Combine header and payload for CRC calculation
        frame_without_crc = header + payload

        # Calculate CRC32 checksum over entire frame (excluding CRC itself)
        crc = zlib.crc32(frame_without_crc) & 0xFFFFFFFF

        # Pack CRC32
        crc_bytes = struct.pack(ProtocolFrame.CRC_FORMAT, crc)

        # Return complete frame
        return frame_without_crc + crc_bytes

    @staticmethod
    def unpack(frame: bytes) -> Tuple[int, int, bytes]:
        """
        Unpack and validate a binary frame.

        Args:
            frame: Complete binary frame received from network

        Returns:
            Tuple of (sequence_number, command_id, payload)

        Raises:
            LXBProtocolError: If frame validation fails (magic/version mismatch)
            LXBChecksumError: If CRC32 checksum validation fails
        """
        # Validate minimum frame size
        if len(frame) < MIN_FRAME_SIZE:
            raise LXBProtocolError(
                f"Frame too short: {len(frame)} bytes (minimum {MIN_FRAME_SIZE})",
                ERR_INVALID_MAGIC
            )

        # Split frame into data and CRC
        frame_without_crc = frame[:-CRC_SIZE]
        received_crc_bytes = frame[-CRC_SIZE:]

        # Verify CRC32 checksum
        calculated_crc = zlib.crc32(frame_without_crc) & 0xFFFFFFFF
        received_crc = struct.unpack(ProtocolFrame.CRC_FORMAT, received_crc_bytes)[0]

        if calculated_crc != received_crc:
            raise LXBChecksumError(
                f"CRC mismatch: calculated 0x{calculated_crc:08X}, "
                f"received 0x{received_crc:08X}"
            )

        # Unpack header
        magic, version, seq, cmd, payload_len = struct.unpack(
            ProtocolFrame.HEADER_FORMAT,
            frame_without_crc[:HEADER_SIZE]
        )

        # Validate magic number
        if magic != MAGIC:
            raise LXBProtocolError(
                f"Invalid magic number: 0x{magic:04X} (expected 0x{MAGIC:04X})",
                ERR_INVALID_MAGIC
            )

        # Validate protocol version
        if version != VERSION:
            raise LXBProtocolError(
                f"Invalid protocol version: 0x{version:02X} (expected 0x{VERSION:02X})",
                ERR_INVALID_VERSION
            )

        # Extract payload
        payload = frame_without_crc[HEADER_SIZE:]

        # Validate payload length matches header declaration
        if len(payload) != payload_len:
            raise LXBProtocolError(
                f"Payload length mismatch: header declares {payload_len}, "
                f"actual {len(payload)}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        return seq, cmd, payload

    @staticmethod
    def pack_tap(seq: int, x: int, y: int) -> bytes:
        """
        Pack a TAP command with coordinates.

        Args:
            seq: Sequence number
            x: X coordinate (0 to 65535)
            y: Y coordinate (0 to 65535)

        Returns:
            Complete binary frame for TAP command
        """
        from .constants import CMD_TAP

        # Pack coordinates as two uint16 (Little Endian)
        payload = struct.pack('<HH', x, y)
        return ProtocolFrame.pack(seq, CMD_TAP, payload)

    @staticmethod
    def unpack_tap(payload: bytes) -> Tuple[int, int]:
        """
        Unpack TAP command payload to extract coordinates.

        Args:
            payload: TAP command payload

        Returns:
            Tuple of (x, y) coordinates

        Raises:
            LXBProtocolError: If payload size is invalid
        """
        if len(payload) != 4:
            raise LXBProtocolError(
                f"Invalid TAP payload size: {len(payload)} (expected 4)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        x, y = struct.unpack('<HH', payload)
        return x, y

    @staticmethod
    def pack_ack(seq: int) -> bytes:
        """
        Pack an ACK (acknowledgment) frame.

        Args:
            seq: Sequence number to acknowledge

        Returns:
            Complete binary frame for ACK command
        """
        from .constants import CMD_ACK

        return ProtocolFrame.pack(seq, CMD_ACK, b'')

    @staticmethod
    def is_ack(cmd: int, seq: int, expected_seq: int) -> bool:
        """
        Check if received frame is a valid ACK for expected sequence.

        Args:
            cmd: Received command ID
            seq: Received sequence number
            expected_seq: Expected sequence number

        Returns:
            True if frame is valid ACK with matching sequence, False otherwise
        """
        from .constants import CMD_ACK

        return cmd == CMD_ACK and seq == expected_seq

    # =========================================================================
    # Fragmented Screenshot Transfer Protocol
    # =========================================================================

    @staticmethod
    def pack_img_meta(seq: int, img_id: int, total_size: int, num_chunks: int) -> bytes:
        """
        Pack IMG_META frame with screenshot metadata.

        Payload format: img_id[uint32], total_size[uint32], num_chunks[uint16]

        Args:
            seq: Sequence number
            img_id: Unique image ID
            total_size: Total image size in bytes
            num_chunks: Total number of chunks

        Returns:
            Complete binary frame for IMG_META command
        """
        from .constants import CMD_IMG_META

        payload = struct.pack('<IIH', img_id, total_size, num_chunks)
        return ProtocolFrame.pack(seq, CMD_IMG_META, payload)

    @staticmethod
    def unpack_img_meta(payload: bytes) -> Tuple[int, int, int]:
        """
        Unpack IMG_META payload.

        Args:
            payload: IMG_META payload

        Returns:
            Tuple of (img_id, total_size, num_chunks)
        """
        if len(payload) != 10:
            raise LXBProtocolError(
                f"Invalid IMG_META payload size: {len(payload)} (expected 10)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        img_id, total_size, num_chunks = struct.unpack('<IIH', payload)
        return img_id, total_size, num_chunks

    @staticmethod
    def pack_img_chunk(seq: int, chunk_index: int, chunk_data: bytes) -> bytes:
        """
        Pack IMG_CHUNK frame with chunk data.

        Payload format: chunk_index[uint16] + chunk_data

        Args:
            seq: Sequence number
            chunk_index: Index of this chunk (0-based)
            chunk_data: Chunk data

        Returns:
            Complete binary frame for IMG_CHUNK command
        """
        from .constants import CMD_IMG_CHUNK

        payload = struct.pack('<H', chunk_index) + chunk_data
        return ProtocolFrame.pack(seq, CMD_IMG_CHUNK, payload)

    @staticmethod
    def unpack_img_chunk(payload: bytes) -> Tuple[int, bytes]:
        """
        Unpack IMG_CHUNK payload.

        Args:
            payload: IMG_CHUNK payload

        Returns:
            Tuple of (chunk_index, chunk_data)
        """
        if len(payload) < 2:
            raise LXBProtocolError(
                f"Invalid IMG_CHUNK payload size: {len(payload)} (minimum 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        chunk_index = struct.unpack('<H', payload[:2])[0]
        chunk_data = payload[2:]
        return chunk_index, chunk_data

    @staticmethod
    def pack_img_missing(seq: int, missing_indices: list) -> bytes:
        """
        Pack IMG_MISSING frame with list of missing chunk indices.

        Payload format: count[uint16] + indices[uint16 array]

        Args:
            seq: Sequence number
            missing_indices: List of missing chunk indices

        Returns:
            Complete binary frame for IMG_MISSING command
        """
        from .constants import CMD_IMG_MISSING

        count = len(missing_indices)
        # Pack: count + array of indices
        payload = struct.pack('<H', count) + struct.pack(f'<{count}H', *missing_indices)
        return ProtocolFrame.pack(seq, CMD_IMG_MISSING, payload)

    @staticmethod
    def unpack_img_missing(payload: bytes) -> list:
        """
        Unpack IMG_MISSING payload.

        Args:
            payload: IMG_MISSING payload

        Returns:
            List of missing chunk indices
        """
        if len(payload) < 2:
            raise LXBProtocolError(
                f"Invalid IMG_MISSING payload size: {len(payload)} (minimum 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        count = struct.unpack('<H', payload[:2])[0]
        if len(payload) != 2 + count * 2:
            raise LXBProtocolError(
                f"IMG_MISSING payload size mismatch: expected {2 + count * 2}, got {len(payload)}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        if count == 0:
            return []

        indices = struct.unpack(f'<{count}H', payload[2:])
        return list(indices)
