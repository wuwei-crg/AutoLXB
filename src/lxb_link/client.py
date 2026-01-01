"""
LXB-Link Client API

This module provides the user-facing API for controlling Android devices using
the LXB-Link reliable UDP protocol. It abstracts low-level socket operations
and binary frame manipulation behind a simple, intuitive interface.

Example:
    >>> from ww_link import LXBLinkClient
    >>>
    >>> # Using context manager (recommended)
    >>> with LXBLinkClient('192.168.1.100', 12345) as client:
    ...     client.handshake()
    ...     client.tap(500, 800)
    ...     screenshot_data = client.screenshot()
    ...
    >>> # Manual connection management
    >>> client = LXBLinkClient('192.168.1.100', 12345)
    >>> client.connect()
    >>> client.tap(100, 200)
    >>> client.disconnect()
"""

import logging
from typing import Optional

from .constants import (
    DEFAULT_PORT,
    DEFAULT_TIMEOUT,
    MAX_RETRIES,
    CMD_HANDSHAKE,
    CMD_TAP,
    CMD_SWIPE,
    CMD_SCREENSHOT,
    CMD_WAKE,
)
from .transport import Transport
from .protocol import ProtocolFrame


# Configure logging
logger = logging.getLogger(__name__)


class LXBLinkClient:
    """
    High-level client for LXB-Link protocol communication.

    This class provides a simple API for controlling Android devices through
    the LXB-Link protocol, hiding the complexity of UDP socket management,
    frame packing/unpacking, and retry logic.
    """

    def __init__(
        self,
        host: str,
        port: int = DEFAULT_PORT,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = MAX_RETRIES
    ):
        """
        Initialize LXB-Link client.

        Args:
            host: Target device IP address or hostname
            port: Target device UDP port (default: 12345)
            timeout: Command timeout in seconds (default: 1.0)
            max_retries: Maximum retry attempts (default: 3)
        """
        self.host = host
        self.port = port
        self.timeout = timeout
        self.max_retries = max_retries

        # Transport layer instance
        self._transport: Optional[Transport] = None

        # Connection state
        self._connected = False

        logger.info(f"LXBLinkClient initialized for {host}:{port}")

    def connect(self) -> None:
        """
        Establish connection to remote device.

        This method initializes the transport layer and prepares the client
        for sending commands.

        Raises:
            OSError: If socket creation or configuration fails
        """
        if self._connected:
            logger.warning("Client already connected")
            return

        # Create transport layer
        self._transport = Transport(
            remote_host=self.host,
            remote_port=self.port,
            timeout=self.timeout,
            max_retries=self.max_retries
        )

        # Establish transport connection
        self._transport.connect()
        self._connected = True

        logger.info(f"Client connected to {self.host}:{self.port}")

    def disconnect(self) -> None:
        """
        Close connection to remote device and release resources.
        """
        if self._transport:
            self._transport.disconnect()
            self._transport = None
            self._connected = False

        logger.info("Client disconnected")

    def _ensure_connected(self) -> None:
        """
        Verify transport layer is connected.

        Raises:
            RuntimeError: If client is not connected
        """
        if not self._connected or not self._transport:
            raise RuntimeError(
                "Client not connected. Call connect() first or use context manager."
            )

    def handshake(self) -> bytes:
        """
        Perform handshake with remote device.

        This command is typically used to verify connectivity and protocol
        compatibility during connection establishment.

        Returns:
            Response payload from device (typically empty or version info)

        Raises:
            LXBTimeoutError: If handshake times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending handshake command")
        response = self._transport.send_reliable(CMD_HANDSHAKE, b'') # pyright: ignore[reportOptionalMemberAccess]

        logger.info("Handshake successful")
        return response

    def tap(self, x: int, y: int) -> bytes:
        """
        Perform a tap gesture at specified screen coordinates.

        Args:
            x: X coordinate (0 to 65535)
            y: Y coordinate (0 to 65535)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If tap command times out
            RuntimeError: If client is not connected
            ValueError: If coordinates are out of range
        """
        # Validate coordinates
        if not (0 <= x <= 65535):
            raise ValueError(f"X coordinate {x} out of range [0, 65535]")
        if not (0 <= y <= 65535):
            raise ValueError(f"Y coordinate {y} out of range [0, 65535]")

        self._ensure_connected()

        logger.info(f"Sending TAP command: ({x}, {y})")

        # Pack TAP payload: x[uint16], y[uint16]
        import struct
        payload = struct.pack('<HH', x, y)

        response = self._transport.send_reliable(CMD_TAP, payload) # pyright: ignore[reportOptionalMemberAccess]

        logger.info(f"TAP successful: ({x}, {y})")
        return response

    def swipe(
        self,
        x1: int,
        y1: int,
        x2: int,
        y2: int,
        duration: int = 300
    ) -> bytes:
        """
        Perform a swipe gesture from (x1, y1) to (x2, y2).

        Args:
            x1: Start X coordinate (0 to 65535)
            y1: Start Y coordinate (0 to 65535)
            x2: End X coordinate (0 to 65535)
            y2: End Y coordinate (0 to 65535)
            duration: Swipe duration in milliseconds (default: 300)

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If swipe command times out
            RuntimeError: If client is not connected
            ValueError: If coordinates are out of range
        """
        # Validate coordinates
        for coord, name in [(x1, 'x1'), (y1, 'y1'), (x2, 'x2'), (y2, 'y2')]:
            if not (0 <= coord <= 65535):
                raise ValueError(f"Coordinate {name}={coord} out of range [0, 65535]")

        if not (0 <= duration <= 65535):
            raise ValueError(f"Duration {duration} out of range [0, 65535]")

        self._ensure_connected()

        logger.info(f"Sending SWIPE command: ({x1}, {y1}) -> ({x2}, {y2}), "
                    f"duration={duration}ms")

        # Pack SWIPE payload: x1, y1, x2, y2, duration (all uint16)
        import struct
        payload = struct.pack('<HHHHH', x1, y1, x2, y2, duration)

        response = self._transport.send_reliable(CMD_SWIPE, payload)

        logger.info(f"SWIPE successful")
        return response

    def screenshot(self) -> bytes:
        """
        Capture a screenshot from the device (legacy single-frame mode).

        This method uses the legacy CMD_SCREENSHOT which sends the entire
        screenshot in a single UDP frame. For large screenshots (>50KB),
        this relies on IP-layer fragmentation which may be inefficient.

        For better performance with large screenshots, use request_screenshot()
        which implements application-layer fragmentation with selective repeat.

        Returns:
            Screenshot image data (format depends on device implementation,
            typically JPEG or PNG encoded)

        Raises:
            LXBTimeoutError: If screenshot command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending SCREENSHOT command (legacy mode)")
        response = self._transport.send_reliable(CMD_SCREENSHOT, b'')

        logger.info(f"SCREENSHOT successful: {len(response)} bytes received")
        return response

    def request_screenshot(self) -> bytes:
        """
        Request screenshot using fragmented transfer with selective repeat.

        This method implements an efficient fragmented transfer protocol for
        large screenshots (50KB-200KB). It uses application-layer fragmentation
        with selective repeat instead of relying on IP-layer fragmentation.

        Features:
        - Chunked transfer (1KB chunks by default)
        - Burst transmission (server sends all chunks without waiting)
        - Selective repeat (only missing chunks are retransmitted)
        - Handles UDP packet loss and reordering efficiently

        Returns:
            Screenshot image data (format depends on device implementation,
            typically JPEG or PNG encoded)

        Raises:
            LXBTimeoutError: If transfer fails after maximum retries
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Requesting screenshot (fragmented mode)")
        response = self._transport.request_screenshot_fragmented() # type: ignore

        logger.info(
            f"Screenshot transfer successful: {len(response)} bytes "
            f"({len(response) / 1024:.1f} KB)"
        )
        return response

    def wake(self) -> bytes:
        """
        Wake up the device (turn on screen/unlock).

        Returns:
            Response payload from device

        Raises:
            LXBTimeoutError: If wake command times out
            RuntimeError: If client is not connected
        """
        self._ensure_connected()

        logger.info("Sending WAKE command")
        response = self._transport.send_reliable(CMD_WAKE, b'') # type: ignore

        logger.info("WAKE successful")
        return response

    def send_custom_command(
        self,
        cmd: int,
        payload: bytes = b'',
        reliable: bool = True
    ) -> Optional[bytes]:
        """
        Send a custom command to the device.

        This method allows sending arbitrary commands not covered by the
        standard API methods.

        Args:
            cmd: Command ID (0 to 255)
            payload: Command payload (default: empty)
            reliable: Use reliable delivery with ACK (default: True)

        Returns:
            Response payload if reliable=True, None otherwise

        Raises:
            LXBTimeoutError: If reliable command times out
            RuntimeError: If client is not connected
            ValueError: If command ID is out of range
        """
        if not (0 <= cmd <= 255):
            raise ValueError(f"Command ID {cmd} out of range [0, 255]")

        self._ensure_connected()

        logger.info(f"Sending custom command: cmd=0x{cmd:02X}, "
                    f"payload={len(payload)} bytes, reliable={reliable}")

        if reliable:
            response = self._transport.send_reliable(cmd, payload)
            logger.info(f"Custom command successful: 0x{cmd:02X}")
            return response
        else:
            self._transport.send_and_forget(cmd, payload)
            logger.info(f"Custom command sent (unreliable): 0x{cmd:02X}")
            return None

    def __enter__(self):
        """Context manager entry: establish connection."""
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit: close connection."""
        self.disconnect()
        return False

    def __repr__(self) -> str:
        """String representation of client."""
        status = "connected" if self._connected else "disconnected"
        return (
            f"LXBLinkClient(host='{self.host}', port={self.port}, "
            f"timeout={self.timeout}, status='{status}')"
        )
