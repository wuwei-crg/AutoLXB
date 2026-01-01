"""
LXB-Link Protocol Constants

This module defines all constants used in the LXB-Link protocol including:
- Magic numbers and protocol version
- Command set for device control
- Error codes for exception handling
- Protocol configuration parameters
"""

# =============================================================================
# Protocol Magic Numbers
# =============================================================================

MAGIC = 0xAA55  # Protocol magic number (2 bytes)
VERSION = 0x01  # Protocol version (1 byte)

# =============================================================================
# Frame Structure Constants
# =============================================================================

MAGIC_SIZE = 2      # Magic field size in bytes
VERSION_SIZE = 1    # Version field size in bytes
SEQ_SIZE = 4        # Sequence number field size in bytes
CMD_SIZE = 1        # Command field size in bytes
LEN_SIZE = 2        # Payload length field size in bytes
CRC_SIZE = 4        # CRC32 checksum field size in bytes

# Calculate header size (excluding payload and CRC)
HEADER_SIZE = MAGIC_SIZE + VERSION_SIZE + SEQ_SIZE + CMD_SIZE + LEN_SIZE

# Minimum frame size (header + CRC, no payload)
MIN_FRAME_SIZE = HEADER_SIZE + CRC_SIZE

# Maximum payload size (64KB - header - CRC)
MAX_PAYLOAD_SIZE = 65535 - HEADER_SIZE - CRC_SIZE

# =============================================================================
# Command Set
# =============================================================================

CMD_HANDSHAKE = 0x01    # Handshake command for connection establishment
CMD_ACK = 0x02          # Acknowledgment command for reliable delivery
CMD_TAP = 0x03          # Single tap command (payload: x[uint16], y[uint16])
CMD_SWIPE = 0x04        # Swipe gesture command
CMD_SCREENSHOT = 0x09   # Screenshot capture command (legacy, single-frame)
CMD_WAKE = 0x0A         # Wake/unlock device command

# Fragmented Screenshot Transfer Commands (Server-Pull Model)
CMD_IMG_REQ = 0x10      # Client -> Server: Request screenshot
CMD_IMG_META = 0x11     # Server -> Client: Metadata (img_id, total_size, num_chunks)
CMD_IMG_CHUNK = 0x12    # Server -> Client: Data chunk (chunk_index, data)
CMD_IMG_MISSING = 0x13  # Client -> Server: Request missing chunks (list of indices)
CMD_IMG_FIN = 0x14      # Client -> Server: Transfer complete acknowledgment

# =============================================================================
# Error Codes
# =============================================================================

ERR_SUCCESS = 0x00              # Success
ERR_INVALID_MAGIC = 0x01        # Invalid magic number
ERR_INVALID_VERSION = 0x02      # Invalid protocol version
ERR_INVALID_CRC = 0x03          # CRC checksum mismatch
ERR_INVALID_PAYLOAD_SIZE = 0x04 # Payload size exceeds maximum
ERR_TIMEOUT = 0x05              # Operation timeout
ERR_MAX_RETRIES = 0x06          # Maximum retry attempts exceeded
ERR_INVALID_ACK = 0x07          # Invalid acknowledgment received
ERR_SEQ_MISMATCH = 0x08         # Sequence number mismatch

# =============================================================================
# Transport Configuration
# =============================================================================

DEFAULT_TIMEOUT = 1.0           # Default socket timeout in seconds
MAX_RETRIES = 3                 # Maximum retry attempts for reliable delivery
DEFAULT_PORT = 12345            # Default UDP port for communication
SOCKET_BUFFER_SIZE = 65536      # Socket receive buffer size

# Fragmented Transfer Configuration
CHUNK_SIZE = 1024               # Default chunk size for fragmented transfer (1KB)
CHUNK_RECV_TIMEOUT = 0.3        # Timeout for receiving chunks (300ms)
MAX_MISSING_RETRIES = 3         # Maximum retries for requesting missing chunks

# =============================================================================
# Exception Classes
# =============================================================================


class LXBLinkError(Exception):
    """Base exception class for LXB-Link protocol errors."""

    def __init__(self, message: str, error_code: int = ERR_SUCCESS):
        """
        Initialize LXB-Link error.

        Args:
            message: Human-readable error message
            error_code: Protocol error code
        """
        super().__init__(message)
        self.error_code = error_code


class LXBTimeoutError(LXBLinkError):
    """Exception raised when operation times out after maximum retries."""

    def __init__(self, message: str = "Operation timeout after maximum retries"):
        super().__init__(message, ERR_TIMEOUT)


class LXBProtocolError(LXBLinkError):
    """Exception raised when protocol validation fails."""

    def __init__(self, message: str, error_code: int):
        super().__init__(message, error_code)


class LXBChecksumError(LXBLinkError):
    """Exception raised when CRC32 checksum validation fails."""

    def __init__(self, message: str = "CRC32 checksum mismatch"):
        super().__init__(message, ERR_INVALID_CRC)
