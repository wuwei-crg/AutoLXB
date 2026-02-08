"""
LXB-Link Protocol Layer - Binary First Architecture

This module handles binary frame packing and unpacking operations with CRC32
validation for the LXB-Link reliable UDP protocol.

Frame Format (Big Endian / Network Byte Order):
┌─────────┬─────────┬─────────┬─────────┬─────────┬─────────┬─────────┐
│ Magic   │ Ver     │ Seq     │ Cmd     │ Len     │ Data    │ CRC32   │
│ 2 bytes │ 1 byte  │ 4 bytes │ 1 byte  │ 2 bytes │ N bytes │ 4 bytes │
│ 0xAA55  │ 0x01    │ uint32  │  uint8  │ uint16  │ payload │ uint32  │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────┴─────────┘

Design Principles:
- Binary First: Reject JSON bloat, use compact binary encoding
- Big Endian (Network Byte Order): All multi-byte fields use '>' prefix
- String Pool: Compress repeated strings (saves 96% bandwidth)
- Zero Copy: Design for efficient parsing
"""

import struct
import zlib
from typing import Tuple, Optional, Dict, List

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
    ERR_UNSUPPORTED,
    LXBProtocolError,
    LXBChecksumError,
    # String Pool constants
    PREDEFINED_CLASSES,
    PREDEFINED_TEXTS,
    CLASS_TO_ID,
    TEXT_TO_ID,
    DYNAMIC_STRING_POOL_START,
    MAX_STRING_POOL_ID,
    STRING_POOL_EMPTY_ID,
)


# =============================================================================
# String Pool - Binary First Optimization
# =============================================================================

class StringPool:
    """
    String pool for compressing repeated strings in UI hierarchy.

    Uses a two-tier strategy:
    1. Predefined strings (0x00-0x7F): Common Android classes and texts
    2. Dynamic strings (0x80-0xFE): Runtime-discovered strings

    Saves 96% bandwidth by encoding "android.widget.TextView" as 1 byte (0x02)
    instead of 28 bytes.
    """

    def __init__(self):
        """Initialize string pool with empty dynamic pool."""
        self.pool: Dict[str, int] = {}  # {string: id} for dynamic strings
        self.reverse_pool: Dict[int, str] = {}  # {id: string} for decoding
        self.next_id = DYNAMIC_STRING_POOL_START  # Start at 0x80

    def add(self, s: str) -> int:
        """
        Add string to pool and return its ID.

        Args:
            s: String to encode

        Returns:
            String ID (0x00-0xFE) or 0xFF if empty

        Encoding strategy:
        - Empty string → 0xFF (special marker)
        - Predefined class (0x00-0x3F) → return predefined ID
        - Predefined text (0x40-0x7F) → return predefined ID
        - Dynamic string (0x80-0xFE) → allocate new ID or return existing
        """
        # Handle empty string
        if not s:
            return STRING_POOL_EMPTY_ID

        # Check predefined classes (0x00-0x3F)
        if s in CLASS_TO_ID:
            return CLASS_TO_ID[s]

        # Check predefined texts (0x40-0x7F)
        if s in TEXT_TO_ID:
            return TEXT_TO_ID[s]

        # Check if already in dynamic pool
        if s in self.pool:
            return self.pool[s]

        # Allocate new dynamic ID
        if self.next_id > MAX_STRING_POOL_ID:
            raise ValueError(
                f"String pool overflow: cannot allocate more than "
                f"{MAX_STRING_POOL_ID - DYNAMIC_STRING_POOL_START} dynamic strings"
            )

        str_id = self.next_id
        self.pool[s] = str_id
        self.reverse_pool[str_id] = s
        self.next_id += 1

        return str_id

    def get(self, str_id: int) -> str:
        """
        Decode string ID back to string.

        Args:
            str_id: String ID (0x00-0xFE or 0xFF)

        Returns:
            Decoded string

        Raises:
            ValueError: If ID is invalid
        """
        # Handle empty string marker
        if str_id == STRING_POOL_EMPTY_ID:
            return ""

        # Predefined classes (0x00-0x3F)
        if 0x00 <= str_id <= 0x3F:
            if str_id < len(PREDEFINED_CLASSES):
                return PREDEFINED_CLASSES[str_id]
            else:
                raise ValueError(f"Invalid class ID: 0x{str_id:02X}")

        # Predefined texts (0x40-0x7F)
        if 0x40 <= str_id <= 0x7F:
            text_index = str_id - 0x40
            if text_index < len(PREDEFINED_TEXTS):
                return PREDEFINED_TEXTS[text_index]
            else:
                raise ValueError(f"Invalid text ID: 0x{str_id:02X}")

        # Dynamic strings (0x80-0xFE)
        if str_id in self.reverse_pool:
            return self.reverse_pool[str_id]

        raise ValueError(f"String ID not found in pool: 0x{str_id:02X}")

    def pack(self) -> bytes:
        """
        Serialize dynamic string pool to binary format.

        Only packs dynamic strings (0x80-0xFE), as predefined strings
        are known to both client and server.

        Returns:
            Binary representation of dynamic string pool

        Format:
            count[uint16] + entries[StringEntry...]

            StringEntry:
                str_id[uint8] + str_len[uint8] + str_data[UTF-8]
        """
        if not self.pool:
            # Empty dynamic pool
            return struct.pack('>H', 0)

        # Sort by ID for deterministic output
        entries = sorted(self.pool.items(), key=lambda x: x[1])

        # Pack count
        packed = struct.pack('>H', len(entries))

        # Pack each entry
        for string, str_id in entries:
            encoded = string.encode('utf-8')
            if len(encoded) > 255:
                raise ValueError(
                    f"String too long for pool (max 255 bytes): {len(encoded)} bytes"
                )

            # Pack: str_id[uint8] + str_len[uint8] + str_data
            packed += struct.pack('>BB', str_id, len(encoded))
            packed += encoded

        return packed

    @staticmethod
    def unpack(data: bytes) -> Tuple['StringPool', int]:
        """
        Deserialize string pool from binary data.

        Args:
            data: Binary data starting with string pool

        Returns:
            Tuple of (StringPool instance, bytes_consumed)

        Raises:
            LXBProtocolError: If data is malformed
        """
        if len(data) < 2:
            raise LXBProtocolError(
                f"String pool data too short: {len(data)} bytes (minimum 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        pool = StringPool()
        offset = 0

        # Unpack count
        count = struct.unpack('>H', data[offset:offset+2])[0]
        offset += 2

        # Unpack each entry
        for _ in range(count):
            if offset + 2 > len(data):
                raise LXBProtocolError(
                    "String pool data truncated (missing entry header)",
                    ERR_INVALID_PAYLOAD_SIZE
                )

            str_id, str_len = struct.unpack('>BB', data[offset:offset+2])
            offset += 2

            if offset + str_len > len(data):
                raise LXBProtocolError(
                    f"String pool data truncated (missing string data: {str_len} bytes)",
                    ERR_INVALID_PAYLOAD_SIZE
                )

            string = data[offset:offset+str_len].decode('utf-8')
            offset += str_len

            # Add to pool
            pool.pool[string] = str_id
            pool.reverse_pool[str_id] = string

        # Update next_id to highest ID + 1
        if pool.reverse_pool:
            pool.next_id = max(pool.reverse_pool.keys()) + 1

        return pool, offset


# =============================================================================
# Protocol Frame Handler
# =============================================================================

class ProtocolFrame:
    """
    LXB-Link protocol frame handler for packing and unpacking binary data.

    This class provides methods to construct binary frames from command data
    and parse received frames with comprehensive validation.
    """

    # Struct format for frame header (Big Endian)
    # H: unsigned short (2 bytes) - Magic
    # B: unsigned char (1 byte) - Version
    # I: unsigned int (4 bytes) - Sequence
    # B: unsigned char (1 byte) - Command
    # H: unsigned short (2 bytes) - Payload Length
    HEADER_FORMAT = '>HBIBH'

    # Struct format for CRC32 (Big Endian)
    # I: unsigned int (4 bytes) - CRC32
    CRC_FORMAT = '>I'

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

        # Pack coordinates as two uint16 (Big Endian)
        payload = struct.pack('>HH', x, y)
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

        x, y = struct.unpack('>HH', payload)
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

        payload = struct.pack('>IIH', img_id, total_size, num_chunks)
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

        img_id, total_size, num_chunks = struct.unpack('>IIH', payload)
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

        payload = struct.pack('>H', chunk_index) + chunk_data
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

        chunk_index = struct.unpack('>H', payload[:2])[0]
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
        payload = struct.pack('>H', count) + struct.pack(f'>{count}H', *missing_indices)
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

        count = struct.unpack('>H', payload[:2])[0]
        if len(payload) != 2 + count * 2:
            raise LXBProtocolError(
                f"IMG_MISSING payload size mismatch: expected {2 + count * 2}, got {len(payload)}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        if count == 0:
            return []

        indices = struct.unpack(f'>{count}H', payload[2:])
        return list(indices)

    # =========================================================================
    # Sense Layer - Perception Capabilities (Binary First) ⭐
    # =========================================================================

    @staticmethod
    def pack_get_activity(seq: int) -> bytes:
        """
        Pack GET_ACTIVITY command (no payload).

        Args:
            seq: Sequence number

        Returns:
            Complete binary frame for GET_ACTIVITY command
        """
        from .constants import CMD_GET_ACTIVITY
        return ProtocolFrame.pack(seq, CMD_GET_ACTIVITY, b'')

    @staticmethod
    def unpack_get_activity_response(payload: bytes) -> Tuple[bool, str, str]:
        """
        Unpack GET_ACTIVITY response payload.

        Payload format (variable):
            success[uint8] + package_len[uint16] + package_name[UTF-8] +
            activity_len[uint16] + activity_name[UTF-8]

        Args:
            payload: GET_ACTIVITY response payload

        Returns:
            Tuple of (success, package_name, activity_name)

        Raises:
            LXBProtocolError: If payload is malformed
        """
        if len(payload) < 5:  # Minimum: 1 + 2 + 0 + 2 + 0
            raise LXBProtocolError(
                f"Invalid GET_ACTIVITY response size: {len(payload)} (minimum 5)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        offset = 0

        # Unpack success flag
        success = struct.unpack('>B', payload[offset:offset+1])[0]
        offset += 1

        # Unpack package name
        package_len = struct.unpack('>H', payload[offset:offset+2])[0]
        offset += 2

        if offset + package_len > len(payload):
            raise LXBProtocolError(
                "GET_ACTIVITY response truncated (package_name)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        package_name = payload[offset:offset+package_len].decode('utf-8')
        offset += package_len

        # Unpack activity name
        if offset + 2 > len(payload):
            raise LXBProtocolError(
                "GET_ACTIVITY response truncated (activity_len)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        activity_len = struct.unpack('>H', payload[offset:offset+2])[0]
        offset += 2

        if offset + activity_len > len(payload):
            raise LXBProtocolError(
                "GET_ACTIVITY response truncated (activity_name)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        activity_name = payload[offset:offset+activity_len].decode('utf-8')

        return bool(success), package_name, activity_name

    @staticmethod
    def pack_find_node(seq: int, match_type: int, return_mode: int,
                      query: str, multi_match: bool = False,
                      timeout_ms: int = 3000) -> bytes:
        """
        Pack FIND_NODE command (computation offloading).

        Args:
            seq: Sequence number
            match_type: Match type (MATCH_EXACT_TEXT, MATCH_CONTAINS_TEXT, etc.)
            return_mode: Return mode (RETURN_COORDS, RETURN_BOUNDS, RETURN_FULL)
            query: Query string (text/id/class to find)
            multi_match: Return all matches (True) or first only (False)
            timeout_ms: Find timeout in milliseconds

        Returns:
            Complete binary frame for FIND_NODE command
        """
        from .constants import CMD_FIND_NODE

        query_bytes = query.encode('utf-8')

        # Pack request: match_type[1B] + return_mode[1B] + multi_match[1B] +
        #               timeout_ms[2B] + query_len[2B] + query_str[UTF-8]
        payload = struct.pack('>BBBHH',
            match_type,
            return_mode,
            1 if multi_match else 0,
            timeout_ms,
            len(query_bytes)
        ) + query_bytes

        return ProtocolFrame.pack(seq, CMD_FIND_NODE, payload)

    @staticmethod
    def unpack_find_node_coords(payload: bytes) -> Tuple[int, List[Tuple[int, int]]]:
        """
        Unpack FIND_NODE response (return_mode=RETURN_COORDS).

        Payload format:
            status[uint8] + count[uint8] + coords[Coord[4B] * count]

            Coord: x[uint16] + y[uint16]

        Args:
            payload: FIND_NODE response payload

        Returns:
            Tuple of (status, [(x, y), ...])

        Status codes:
            0 = Not found
            1 = Success
            2 = Timeout
        """
        if len(payload) < 2:
            raise LXBProtocolError(
                f"Invalid FIND_NODE response size: {len(payload)} (minimum 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        status, count = struct.unpack('>BB', payload[:2])

        if len(payload) != 2 + count * 4:
            raise LXBProtocolError(
                f"FIND_NODE coords payload size mismatch: expected {2 + count * 4}, got {len(payload)}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        coords = []
        offset = 2
        for _ in range(count):
            x, y = struct.unpack('>HH', payload[offset:offset+4])
            coords.append((x, y))
            offset += 4

        return status, coords

    @staticmethod
    def unpack_find_node_bounds(payload: bytes) -> Tuple[int, List[Tuple[int, int, int, int]]]:
        """
        Unpack FIND_NODE response (return_mode=RETURN_BOUNDS).

        Payload format:
            status[uint8] + count[uint8] + boxes[Box[8B] * count]

            Box: left[uint16] + top[uint16] + right[uint16] + bottom[uint16]

        Args:
            payload: FIND_NODE response payload

        Returns:
            Tuple of (status, [(left, top, right, bottom), ...])
        """
        if len(payload) < 2:
            raise LXBProtocolError(
                f"Invalid FIND_NODE response size: {len(payload)} (minimum 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        status, count = struct.unpack('>BB', payload[:2])

        if len(payload) != 2 + count * 8:
            raise LXBProtocolError(
                f"FIND_NODE bounds payload size mismatch: expected {2 + count * 8}, got {len(payload)}",
                ERR_INVALID_PAYLOAD_SIZE
            )

        boxes = []
        offset = 2
        for _ in range(count):
            left, top, right, bottom = struct.unpack('>HHHH', payload[offset:offset+8])
            boxes.append((left, top, right, bottom))
            offset += 8

        return status, boxes

    # =========================================================================
    # Input Extension Layer - Advanced Input (Binary First) ⭐
    # =========================================================================

    @staticmethod
    def pack_input_text(seq: int, text: str, method: int = 1,
                       clear_first: bool = False, press_enter: bool = False,
                       hide_keyboard: bool = False,
                       target_x: int = 0, target_y: int = 0,
                       delay_ms: int = 0) -> bytes:
        """
        Pack INPUT_TEXT command (pure binary, NO JSON!).

        Args:
            seq: Sequence number
            text: Text to input (UTF-8)
            method: Input method (0=ADB, 1=Clipboard, 2=Accessibility)
            clear_first: Clear existing text before input
            press_enter: Press ENTER after input
            hide_keyboard: Hide keyboard after input
            target_x: Target input box X coordinate (0=current focus)
            target_y: Target input box Y coordinate (0=current focus)
            delay_ms: Delay between characters (for human simulation)

        Returns:
            Complete binary frame for INPUT_TEXT command
        """
        from .constants import CMD_INPUT_TEXT, INPUT_FLAG_CLEAR_FIRST, \
                              INPUT_FLAG_PRESS_ENTER, INPUT_FLAG_HIDE_KEYBOARD

        text_bytes = text.encode('utf-8')

        # Build flags byte (bit field)
        flags = 0
        if clear_first:
            flags |= INPUT_FLAG_CLEAR_FIRST
        if press_enter:
            flags |= INPUT_FLAG_PRESS_ENTER
        if hide_keyboard:
            flags |= INPUT_FLAG_HIDE_KEYBOARD

        # Pack: method[1B] + flags[1B] + target_x[2B] + target_y[2B] +
        #       delay_ms[2B] + text_len[2B] + text[UTF-8]
        payload = struct.pack('>BBHHHH',
            method,
            flags,
            target_x,
            target_y,
            delay_ms,
            len(text_bytes)
        ) + text_bytes

        return ProtocolFrame.pack(seq, CMD_INPUT_TEXT, payload)

    @staticmethod
    def unpack_input_text_response(payload: bytes) -> Tuple[int, int]:
        """
        Unpack INPUT_TEXT response payload.

        Payload format:
            status[uint8] + actual_method[uint8]

        Args:
            payload: INPUT_TEXT response payload

        Returns:
            Tuple of (status, actual_method)

        Status codes:
            0 = Failed
            1 = Success
            2 = Partial success
        """
        if len(payload) != 2:
            raise LXBProtocolError(
                f"Invalid INPUT_TEXT response size: {len(payload)} (expected 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        status, actual_method = struct.unpack('>BB', payload)
        return status, actual_method

    @staticmethod
    def pack_key_event(seq: int, keycode: int, action: int = 2, meta_state: int = 0) -> bytes:
        """
        Pack KEY_EVENT command.

        Args:
            seq: Sequence number
            keycode: Android KeyEvent code (3=HOME, 4=BACK, 66=ENTER, etc.)
            action: Key action (0=down, 1=up, 2=click)
            meta_state: Modifier keys state (Shift/Ctrl/Alt)

        Returns:
            Complete binary frame for KEY_EVENT command
        """
        from .constants import CMD_KEY_EVENT

        # Pack: keycode[1B] + action[1B] + meta_state[4B]
        payload = struct.pack('>BBI', keycode, action, meta_state)

        return ProtocolFrame.pack(seq, CMD_KEY_EVENT, payload)

    @staticmethod
    def unpack_key_event(payload: bytes) -> Tuple[int, int, int]:
        """
        Unpack KEY_EVENT payload.

        Args:
            payload: KEY_EVENT payload

        Returns:
            Tuple of (keycode, action, meta_state)
        """
        if len(payload) != 6:
            raise LXBProtocolError(
                f"Invalid KEY_EVENT payload size: {len(payload)} (expected 6)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        keycode, action, meta_state = struct.unpack('>BBI', payload)
        return keycode, action, meta_state

    # =========================================================================
    # Sense Layer - DUMP_HIERARCHY (Binary First) ⭐
    # =========================================================================

    @staticmethod
    def pack_dump_hierarchy(seq: int, format: int = 2, compress: int = 1,
                           max_depth: int = 0) -> bytes:
        """
        Pack DUMP_HIERARCHY command.

        Args:
            seq: Sequence number
            format: 0=XML, 1=JSON, 2=Binary (recommended)
            compress: 0=None, 1=zlib, 2=lz4
            max_depth: Maximum traversal depth (0=unlimited)

        Returns:
            Complete binary frame for DUMP_HIERARCHY command
        """
        from .constants import CMD_DUMP_HIERARCHY

        # Pack: format[1B] + compress[1B] + max_depth[2B]
        payload = struct.pack('>BBH', format, compress, max_depth)
        return ProtocolFrame.pack(seq, CMD_DUMP_HIERARCHY, payload)

    @staticmethod
    def unpack_dump_hierarchy_binary(payload: bytes) -> Tuple[dict, StringPool]:
        """
        Unpack DUMP_HIERARCHY response (format=2, binary).

        Payload format:
            version[1B] + compress[1B] + original_size[4B] + compressed_size[4B] +
            node_count[2B] + string_pool_size[2B] + [StringPool] + [Nodes Array]

        Args:
            payload: DUMP_HIERARCHY binary response payload

        Returns:
            Tuple of (hierarchy_dict, string_pool)

        Raises:
            LXBProtocolError: If payload is malformed
        """
        if len(payload) < 14:
            raise LXBProtocolError(
                f"DUMP_HIERARCHY payload too short: {len(payload)} (minimum 14)",
                ERR_INVALID_PAYLOAD_SIZE
            )

        offset = 0

        # Unpack header (14 bytes)
        version, compress, original_size, compressed_size, node_count, string_pool_size = \
            struct.unpack('>BBIIHH', payload[offset:offset+14])
        offset += 14

        # Decompress if needed
        if compress == 1:  # zlib
            import zlib
            compressed_data = payload[offset:]
            try:
                decompressed = zlib.decompress(compressed_data)
            except zlib.error as e:
                raise LXBProtocolError(
                    f"zlib decompression failed: {e}",
                    ERR_INVALID_PAYLOAD_SIZE
                )
            data = decompressed
            data_offset = 0
        elif compress == 2:  # lz4
            # LZ4 support (optional, requires lz4 package)
            try:
                import lz4.frame
                compressed_data = payload[offset:]
                decompressed = lz4.frame.decompress(compressed_data)
                data = decompressed
                data_offset = 0
            except ImportError:
                raise LXBProtocolError(
                    "lz4 package not installed (pip install lz4)",
                    ERR_UNSUPPORTED
                )
            except Exception as e:
                raise LXBProtocolError(
                    f"lz4 decompression failed: {e}",
                    ERR_INVALID_PAYLOAD_SIZE
                )
        else:  # No compression
            data = payload
            data_offset = offset

        # Unpack string pool
        pool, pool_size = StringPool.unpack(data[data_offset:])
        data_offset += pool_size

        # Unpack nodes (15 bytes per node - fixed length)
        nodes = []
        for i in range(node_count):
            if data_offset + 15 > len(data):
                raise LXBProtocolError(
                    f"DUMP_HIERARCHY data truncated (node {i})",
                    ERR_INVALID_PAYLOAD_SIZE
                )

            # Unpack node structure (15 bytes)
            parent_index, child_count, flags, \
                left, top, right, bottom, \
                class_id, text_id, res_id, desc_id = struct.unpack('>BBBHHHHBBBB', data[data_offset:data_offset+15])
            data_offset += 15

            # Decode strings using pool
            class_name = pool.get(class_id) if class_id != 0xFF else ""
            text = pool.get(text_id) if text_id != 0xFF else ""
            resource_id = pool.get(res_id) if res_id != 0xFF else ""
            content_desc = pool.get(desc_id) if desc_id != 0xFF else ""

            # Decode flags
            node = {
                'index': i,
                'parent_index': parent_index if parent_index != 0xFF else None,
                'child_count': child_count,
                'class': class_name,
                'bounds': [left, top, right, bottom],
                'text': text,
                'resource_id': resource_id,
                'content_desc': content_desc,
                # Decode bit flags
                'clickable': bool(flags & 0x01),
                'visible': bool(flags & 0x02),
                'enabled': bool(flags & 0x04),
                'focused': bool(flags & 0x08),
                'scrollable': bool(flags & 0x10),
                'editable': bool(flags & 0x20),
                'checkable': bool(flags & 0x40),
                'checked': bool(flags & 0x80),
            }

            nodes.append(node)

        # Build hierarchy dictionary
        hierarchy = {
            'version': version,
            'node_count': node_count,
            'nodes': nodes,
        }

        return hierarchy, pool

    @staticmethod
    def pack_hierarchy_binary(nodes: List[dict], pool: StringPool = None) -> bytes:
        """
        Pack UI hierarchy to binary format with string pool.

        Args:
            nodes: List of node dictionaries with structure:
                {
                    'parent_index': int or None,
                    'child_count': int,
                    'class': str,
                    'bounds': [left, top, right, bottom],
                    'text': str,
                    'resource_id': str,
                    'content_desc': str,
                    'clickable': bool,
                    'visible': bool,
                    'enabled': bool,
                    'focused': bool,
                    'scrollable': bool,
                    'editable': bool,
                    'checkable': bool,
                    'checked': bool,
                }
            pool: Optional StringPool to use (creates new if None)

        Returns:
            Binary encoded hierarchy with string pool

        Format:
            version[1B] + compress[1B] + original_size[4B] + compressed_size[4B] +
            node_count[2B] + string_pool_size[2B] + [StringPool] + [Nodes Array]
        """
        if pool is None:
            pool = StringPool()

        # Collect all strings into pool
        for node in nodes:
            pool.add(node.get('class', ''))
            pool.add(node.get('text', ''))
            pool.add(node.get('resource_id', ''))
            pool.add(node.get('content_desc', ''))

        # Pack string pool
        pool_data = pool.pack()

        # Pack nodes (15 bytes each)
        nodes_data = b''
        for node in nodes:
            # Get string IDs
            class_id = pool.add(node.get('class', ''))
            text_id = pool.add(node.get('text', ''))
            res_id = pool.add(node.get('resource_id', ''))
            desc_id = pool.add(node.get('content_desc', ''))

            # Encode flags as bit field
            flags = 0
            if node.get('clickable', False): flags |= 0x01
            if node.get('visible', False): flags |= 0x02
            if node.get('enabled', False): flags |= 0x04
            if node.get('focused', False): flags |= 0x08
            if node.get('scrollable', False): flags |= 0x10
            if node.get('editable', False): flags |= 0x20
            if node.get('checkable', False): flags |= 0x40
            if node.get('checked', False): flags |= 0x80

            # Get bounds
            bounds = node.get('bounds', [0, 0, 0, 0])
            left, top, right, bottom = bounds[0], bounds[1], bounds[2], bounds[3]

            # Get parent index (0xFF = root)
            parent_idx = node.get('parent_index', None)
            parent_index = parent_idx if parent_idx is not None else 0xFF

            # Pack node (15 bytes fixed)
            nodes_data += struct.pack('>BBBHHHHBBBB',
                parent_index,
                node.get('child_count', 0),
                flags,
                left, top, right, bottom,
                class_id,
                text_id,
                res_id,
                desc_id
            )

        # Combine pool + nodes
        uncompressed_data = pool_data + nodes_data
        original_size = len(uncompressed_data)

        # Compress with zlib (compress=1)
        import zlib
        compressed_data = zlib.compress(uncompressed_data, level=6)
        compressed_size = len(compressed_data)

        # Pack header
        header = struct.pack('>BBIIHH',
            0x01,                   # version
            0x01,                   # compress (zlib)
            original_size,          # original_size
            compressed_size,        # compressed_size
            len(nodes),             # node_count
            len(pool.pool)          # string_pool_size (dynamic only)
        )

        return header + compressed_data

    # =========================================================================
    # New Commands - Screen & App Control ⭐
    # =========================================================================

    @staticmethod
    def pack_unlock(seq: int) -> bytes:
        """
        Pack UNLOCK command (no payload).

        Args:
            seq: Sequence number

        Returns:
            Complete binary frame for UNLOCK command
        """
        from .constants import CMD_UNLOCK
        return ProtocolFrame.pack(seq, CMD_UNLOCK, b'')

    @staticmethod
    def pack_get_screen_state(seq: int) -> bytes:
        """
        Pack GET_SCREEN_STATE command (no payload).

        Args:
            seq: Sequence number

        Returns:
            Complete binary frame for GET_SCREEN_STATE command
        """
        from .constants import CMD_GET_SCREEN_STATE
        return ProtocolFrame.pack(seq, CMD_GET_SCREEN_STATE, b'')

    @staticmethod
    def unpack_screen_state_response(payload: bytes) -> Tuple[int, int]:
        """
        Unpack GET_SCREEN_STATE response payload.

        Args:
            payload: GET_SCREEN_STATE response payload

        Returns:
            Tuple of (status, state)
            state: 0=off, 1=on_unlocked, 2=on_locked
        """
        if len(payload) != 2:
            raise LXBProtocolError(
                f"Invalid GET_SCREEN_STATE response size: {len(payload)} (expected 2)",
                ERR_INVALID_PAYLOAD_SIZE
            )
        status, state = struct.unpack('>BB', payload)
        return status, state

    @staticmethod
    def pack_get_screen_size(seq: int) -> bytes:
        """
        Pack GET_SCREEN_SIZE command (no payload).

        Args:
            seq: Sequence number

        Returns:
            Complete binary frame for GET_SCREEN_SIZE command
        """
        from .constants import CMD_GET_SCREEN_SIZE
        return ProtocolFrame.pack(seq, CMD_GET_SCREEN_SIZE, b'')

    @staticmethod
    def unpack_screen_size_response(payload: bytes) -> Tuple[int, int, int, int]:
        """
        Unpack GET_SCREEN_SIZE response payload.

        Args:
            payload: GET_SCREEN_SIZE response payload

        Returns:
            Tuple of (status, width, height, density)
        """
        if len(payload) != 7:
            raise LXBProtocolError(
                f"Invalid GET_SCREEN_SIZE response size: {len(payload)} (expected 7)",
                ERR_INVALID_PAYLOAD_SIZE
            )
        status, width, height, density = struct.unpack('>BHHH', payload)
        return status, width, height, density

    @staticmethod
    def pack_launch_app(seq: int, package_name: str, clear_task: bool = False,
                       wait: bool = False) -> bytes:
        """
        Pack LAUNCH_APP command.

        Args:
            seq: Sequence number
            package_name: Package name to launch (e.g., "com.tencent.mm")
            clear_task: Clear task stack before launch
            wait: Wait for Activity to fully launch

        Returns:
            Complete binary frame for LAUNCH_APP command
        """
        from .constants import CMD_LAUNCH_APP, LAUNCH_FLAG_CLEAR_TASK, LAUNCH_FLAG_WAIT

        package_bytes = package_name.encode('utf-8')

        # Build flags
        flags = 0
        if clear_task:
            flags |= LAUNCH_FLAG_CLEAR_TASK
        if wait:
            flags |= LAUNCH_FLAG_WAIT

        # Pack: flags[1B] + package_len[2B] + package_name[UTF-8]
        payload = struct.pack('>BH', flags, len(package_bytes)) + package_bytes

        return ProtocolFrame.pack(seq, CMD_LAUNCH_APP, payload)

    @staticmethod
    def pack_stop_app(seq: int, package_name: str) -> bytes:
        """
        Pack STOP_APP command.

        Args:
            seq: Sequence number
            package_name: Package name to stop (e.g., "com.tencent.mm")

        Returns:
            Complete binary frame for STOP_APP command
        """
        from .constants import CMD_STOP_APP

        package_bytes = package_name.encode('utf-8')

        # Pack: package_len[2B] + package_name[UTF-8]
        payload = struct.pack('>H', len(package_bytes)) + package_bytes

        return ProtocolFrame.pack(seq, CMD_STOP_APP, payload)

    @staticmethod
    def pack_find_node_compound(seq: int, return_mode: int, conditions: list,
                                multi_match: bool = False) -> bytes:
        """
        Pack FIND_NODE_COMPOUND command for multi-condition node search.

        Args:
            seq: Sequence number
            return_mode: Return mode (RETURN_COORDS or RETURN_BOUNDS)
            conditions: List of (field, op, value) tuples
                - field: COMPOUND_FIELD_* constant (0-5)
                - op: COMPOUND_OP_* constant (0-3)
                - value: UTF-8 string to match
            multi_match: Return all matches (True) or first only (False)

        Returns:
            Complete binary frame for FIND_NODE_COMPOUND command
        """
        from .constants import CMD_FIND_NODE_COMPOUND

        flags = 0x01 if multi_match else 0x00
        payload = struct.pack('>BBB', return_mode, flags, len(conditions))

        for field, op, value in conditions:
            val_bytes = value.encode('utf-8')
            payload += struct.pack('>BBH', field, op, len(val_bytes)) + val_bytes

        return ProtocolFrame.pack(seq, CMD_FIND_NODE_COMPOUND, payload)


