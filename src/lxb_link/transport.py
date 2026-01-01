"""
LXB-Link Transport Layer

This module implements the reliable UDP transport layer using Stop-and-Wait ARQ
(Automatic Repeat Request) protocol for guaranteed delivery in unreliable networks.

Features:
- UDP socket management with configurable timeout
- Automatic retry mechanism with exponential backoff
- Sequence number management for packet ordering
- State machine for reliable frame transmission
"""

import socket
import logging
import time
from typing import Tuple, Optional

from .constants import (
    DEFAULT_TIMEOUT,
    MAX_RETRIES,
    SOCKET_BUFFER_SIZE,
    CMD_ACK,
    CMD_IMG_REQ,
    CMD_IMG_META,
    CMD_IMG_CHUNK,
    CMD_IMG_MISSING,
    CMD_IMG_FIN,
    CHUNK_RECV_TIMEOUT,
    MAX_MISSING_RETRIES,
    ERR_MAX_RETRIES,
    ERR_SEQ_MISMATCH,
    ERR_INVALID_ACK,
    LXBTimeoutError,
    LXBProtocolError,
    LXBChecksumError,
)
from .protocol import ProtocolFrame


# Configure logging
logger = logging.getLogger(__name__)


class Transport:
    """
    Reliable UDP transport layer implementing Stop-and-Wait ARQ protocol.

    This class manages UDP socket operations, sequence numbering, and automatic
    retry logic to ensure reliable delivery over unreliable networks.

    State Machine:
        IDLE -> SEND -> WAIT -> [ACK_OK -> IDLE | TIMEOUT -> RETRY -> SEND]
    """

    def __init__(
        self,
        remote_host: str,
        remote_port: int,
        timeout: float = DEFAULT_TIMEOUT,
        max_retries: int = MAX_RETRIES
    ):
        """
        Initialize transport layer.

        Args:
            remote_host: Remote device IP address or hostname
            remote_port: Remote device UDP port
            timeout: Socket timeout in seconds (default: 1.0s)
            max_retries: Maximum retry attempts (default: 3)
        """
        self.remote_host = remote_host
        self.remote_port = remote_port
        self.timeout = timeout
        self.max_retries = max_retries

        # Sequence number counter (wraps at 2^32)
        self._seq = 0

        # UDP socket
        self._sock: Optional[socket.socket] = None

        # Connection state
        self._connected = False

        logger.info(
            f"Transport initialized: {remote_host}:{remote_port}, "
            f"timeout={timeout}s, max_retries={max_retries}"
        )

    def connect(self) -> None:
        """
        Initialize UDP socket and bind to local address.

        Raises:
            OSError: If socket creation or configuration fails
        """
        if self._connected:
            logger.warning("Transport already connected")
            return

        # Create UDP socket
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

        # Set socket timeout for receive operations
        self._sock.settimeout(self.timeout)

        # Set socket buffer size
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, SOCKET_BUFFER_SIZE)

        self._connected = True
        logger.info(f"Connected to {self.remote_host}:{self.remote_port}")

    def disconnect(self) -> None:
        """Close UDP socket and release resources."""
        if self._sock:
            self._sock.close()
            self._sock = None
            self._connected = False
            logger.info("Transport disconnected")

    def _next_seq(self) -> int:
        """
        Get next sequence number and increment counter.

        Returns:
            Current sequence number (auto-increments for next call)
        """
        current_seq = self._seq
        self._seq = (self._seq + 1) & 0xFFFFFFFF  # Wrap at 2^32
        return current_seq

    def _send_frame(self, frame: bytes) -> None:
        """
        Send raw frame over UDP socket.

        Args:
            frame: Binary frame to send

        Raises:
            OSError: If socket send operation fails
        """
        if not self._connected or not self._sock:
            raise LXBProtocolError("Transport not connected", 0)

        self._sock.sendto(frame, (self.remote_host, self.remote_port))
        logger.debug(f"Sent frame: {len(frame)} bytes")

    def _recv_frame(self) -> Tuple[bytes, Tuple[str, int]]:
        """
        Receive raw frame from UDP socket with timeout.

        Returns:
            Tuple of (frame_data, remote_address)

        Raises:
            socket.timeout: If no data received within timeout period
            OSError: If socket receive operation fails
        """
        if not self._connected or not self._sock:
            raise LXBProtocolError("Transport not connected", 0)

        data, addr = self._sock.recvfrom(SOCKET_BUFFER_SIZE)
        logger.debug(f"Received frame: {len(data)} bytes from {addr}")
        return data, addr

    def send_reliable(self, cmd: int, payload: bytes = b'') -> bytes:
        """
        Send command with reliable delivery using Stop-and-Wait ARQ.

        State Machine Logic:
        1. Pack frame with auto-incremented sequence number
        2. SEND: Transmit frame over UDP
        3. WAIT: Block and wait for ACK with timeout
        4. On receive:
           - Validate magic, CRC32, command type, and sequence number
           - If valid ACK with matching seq -> SUCCESS (return payload)
           - If invalid or mismatched -> DISCARD and continue waiting
        5. On timeout:
           - RETRY: Increment retry counter and retransmit
           - If max retries exceeded -> FAIL (raise LXBTimeoutError)

        Args:
            cmd: Command ID to send
            payload: Optional command payload

        Returns:
            ACK payload (typically empty for simple ACKs)

        Raises:
            LXBTimeoutError: If maximum retry attempts exceeded
            LXBProtocolError: If protocol validation fails
            OSError: If socket operation fails
        """
        # Get sequence number for this transmission
        seq = self._next_seq()

        # Pack frame
        frame = ProtocolFrame.pack(seq, cmd, payload)

        retry_count = 0
        last_error = None

        logger.info(f"Sending reliable frame: seq={seq}, cmd=0x{cmd:02X}, "
                    f"payload={len(payload)} bytes")

        while retry_count <= self.max_retries:
            try:
                # STATE: SEND
                self._send_frame(frame)
                send_time = time.time()

                # STATE: WAIT
                while True:
                    try:
                        # Receive response with timeout
                        recv_data, recv_addr = self._recv_frame()

                        # Validate and unpack response
                        try:
                            recv_seq, recv_cmd, recv_payload = ProtocolFrame.unpack(recv_data)

                            # Check if this is the ACK we're waiting for
                            if recv_cmd == CMD_ACK:
                                if recv_seq == seq:
                                    # SUCCESS: Valid ACK with matching sequence
                                    elapsed = time.time() - send_time
                                    logger.info(
                                        f"ACK received: seq={seq}, "
                                        f"retry={retry_count}, "
                                        f"rtt={elapsed*1000:.1f}ms"
                                    )
                                    return recv_payload
                                else:
                                    # Sequence mismatch - discard and continue waiting
                                    logger.warning(
                                        f"ACK sequence mismatch: expected {seq}, "
                                        f"received {recv_seq}"
                                    )
                                    continue
                            else:
                                # Not an ACK - discard and continue waiting
                                logger.warning(
                                    f"Unexpected command: 0x{recv_cmd:02X} "
                                    f"(expected ACK=0x{CMD_ACK:02X})"
                                )
                                continue

                        except (LXBProtocolError, LXBChecksumError) as e:
                            # Frame validation failed - discard and continue waiting
                            logger.warning(f"Invalid frame received: {e}")
                            continue

                    except socket.timeout:
                        # TIMEOUT: No valid ACK received within timeout period
                        logger.warning(
                            f"Timeout waiting for ACK: seq={seq}, "
                            f"retry={retry_count}/{self.max_retries}"
                        )
                        break  # Exit inner loop to retry

            except Exception as e:
                last_error = e
                logger.error(f"Error in send_reliable: {e}")

            # STATE: RETRY
            retry_count += 1

            if retry_count <= self.max_retries:
                # Exponential backoff (optional enhancement)
                # backoff = min(self.timeout * (2 ** (retry_count - 1)), 5.0)
                # time.sleep(backoff)
                logger.info(f"Retrying transmission: attempt {retry_count}")
            else:
                # Maximum retries exceeded
                error_msg = (
                    f"Maximum retries ({self.max_retries}) exceeded for "
                    f"seq={seq}, cmd=0x{cmd:02X}"
                )
                logger.error(error_msg)
                raise LXBTimeoutError(error_msg)

        # Should not reach here, but raise timeout error as fallback
        raise LXBTimeoutError(f"Failed to send reliable frame after {retry_count} attempts")

    def send_and_forget(self, cmd: int, payload: bytes = b'') -> None:
        """
        Send command without waiting for acknowledgment (unreliable).

        This method is useful for non-critical commands where delivery
        confirmation is not required.

        Args:
            cmd: Command ID to send
            payload: Optional command payload

        Raises:
            OSError: If socket send operation fails
        """
        seq = self._next_seq()
        frame = ProtocolFrame.pack(seq, cmd, payload)
        self._send_frame(frame)

        logger.info(f"Sent unreliable frame: seq={seq}, cmd=0x{cmd:02X}")

    def __enter__(self):
        """Context manager entry: establish connection."""
        self.connect()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        """Context manager exit: close connection."""
        self.disconnect()
        return False

    def __del__(self):
        """Destructor: ensure socket is closed."""
        self.disconnect()

    # =========================================================================
    # Fragmented Screenshot Transfer (Server-Pull Model)
    # =========================================================================

    def request_screenshot_fragmented(self) -> bytes:
        """
        Request screenshot using fragmented transfer with selective repeat.

        This is an independent blocking flow that doesn't use send_reliable().
        It implements a "Server-Pull" model where:
        1. Client sends IMG_REQ
        2. Server responds with IMG_META (metadata: img_id, total_size, num_chunks)
        3. Server bursts all IMG_CHUNK packets without waiting for ACKs
        4. Client receives chunks with timeout, then requests missing ones
        5. Client sends IMG_FIN when complete

        Returns:
            Complete screenshot image data as bytes

        Raises:
            LXBTimeoutError: If transfer fails after maximum retries
            LXBProtocolError: If protocol validation fails
        """
        if not self._connected or not self._sock:
            raise LXBProtocolError("Transport not connected", 0)

        logger.info("Starting fragmented screenshot transfer...")

        # Step 1: Send IMG_REQ
        seq_req = self._next_seq()
        req_frame = ProtocolFrame.pack(seq_req, CMD_IMG_REQ, b'')
        self._send_frame(req_frame)
        logger.info(f"Sent IMG_REQ (seq={seq_req})")

        # Step 2: Wait for IMG_META
        img_id, total_size, num_chunks = self._wait_for_img_meta()
        logger.info(
            f"Received IMG_META: img_id={img_id}, total_size={total_size}, "
            f"num_chunks={num_chunks}"
        )

        # Step 3: Receive all chunks with selective repeat
        chunks = self._receive_chunks_with_retries(num_chunks)

        # Step 4: Send IMG_FIN to acknowledge completion
        seq_fin = self._next_seq()
        fin_frame = ProtocolFrame.pack(seq_fin, CMD_IMG_FIN, b'')
        self._send_frame(fin_frame)
        logger.info(f"Sent IMG_FIN (seq={seq_fin})")

        # Step 5: Assemble and return complete image
        complete_image = b''.join(chunks)
        logger.info(
            f"Screenshot transfer complete: {len(complete_image)} bytes "
            f"({len(complete_image) / 1024:.1f} KB)"
        )

        return complete_image

    def _wait_for_img_meta(self, timeout: float = 2.0) -> Tuple[int, int, int]:
        """
        Wait for IMG_META response from server.

        Args:
            timeout: Maximum time to wait for metadata

        Returns:
            Tuple of (img_id, total_size, num_chunks)

        Raises:
            LXBTimeoutError: If metadata not received within timeout
            LXBProtocolError: If received frame is invalid
        """
        original_timeout = self._sock.gettimeout() # type: ignore
        self._sock.settimeout(timeout) # type: ignore

        try:
            while True:
                try:
                    recv_data, recv_addr = self._recv_frame()
                    seq, cmd, payload = ProtocolFrame.unpack(recv_data)

                    if cmd == CMD_IMG_META:
                        img_id, total_size, num_chunks = ProtocolFrame.unpack_img_meta(
                            payload
                        )
                        return img_id, total_size, num_chunks
                    else:
                        logger.warning(
                            f"Unexpected command while waiting for IMG_META: "
                            f"0x{cmd:02X}"
                        )
                        continue

                except (LXBProtocolError, LXBChecksumError) as e:
                    logger.warning(f"Invalid frame received: {e}")
                    continue

        except socket.timeout:
            raise LXBTimeoutError("Timeout waiting for IMG_META")

        finally:
            self._sock.settimeout(original_timeout) # type: ignore

    def _receive_chunks_with_retries(
        self,
        num_chunks: int,
        chunk_timeout: float = CHUNK_RECV_TIMEOUT,
        max_retries: int = MAX_MISSING_RETRIES
    ) -> list:
        """
        Receive all chunks with selective repeat mechanism.

        This method implements the core selective repeat logic:
        1. Receive chunks in burst mode (no ACK per chunk)
        2. Track missing chunks after timeout
        3. Request missing chunks with IMG_MISSING
        4. Retry until all chunks received or max retries exceeded

        Args:
            num_chunks: Total number of expected chunks
            chunk_timeout: Timeout for receiving chunk bursts
            max_retries: Maximum retry attempts for missing chunks

        Returns:
            List of chunk data in correct order (indexed 0 to num_chunks-1)

        Raises:
            LXBTimeoutError: If not all chunks received after max retries
        """
        # Initialize buffer for chunks (None = not yet received)
        chunks: list = [None] * num_chunks
        retry_count = 0

        logger.info(f"Receiving {num_chunks} chunks...")

        while retry_count <= max_retries:
            # Receive chunks until timeout
            received_count = self._receive_chunk_burst(chunks, chunk_timeout)

            # Check if all chunks received
            missing_indices = [
                i for i in range(num_chunks) if chunks[i] is None
            ]

            if not missing_indices:
                logger.info(f"All {num_chunks} chunks received successfully!")
                return chunks

            # Some chunks are missing
            logger.warning(
                f"Missing {len(missing_indices)}/{num_chunks} chunks: "
                f"{missing_indices[:10]}{'...' if len(missing_indices) > 10 else ''}"
            )

            if retry_count >= max_retries:
                raise LXBTimeoutError(
                    f"Failed to receive all chunks after {retry_count} retries. "
                    f"Missing: {len(missing_indices)}/{num_chunks}"
                )

            # Request missing chunks
            self._request_missing_chunks(missing_indices)
            retry_count += 1

        # Should not reach here
        raise LXBTimeoutError(
            f"Screenshot transfer failed after {retry_count} retry attempts"
        )

    def _receive_chunk_burst(
        self,
        chunks: list,
        timeout: float
    ) -> int:
        """
        Receive chunk burst with timeout.

        Args:
            chunks: Buffer to store received chunks
            timeout: Timeout for receiving chunks

        Returns:
            Number of chunks received in this burst
        """
        original_timeout = self._sock.gettimeout() # type: ignore
        self._sock.settimeout(timeout) # type: ignore

        received_in_burst = 0

        try:
            while True:
                try:
                    recv_data, recv_addr = self._recv_frame()
                    seq, cmd, payload = ProtocolFrame.unpack(recv_data)

                    if cmd == CMD_IMG_CHUNK:
                        chunk_index, chunk_data = ProtocolFrame.unpack_img_chunk(
                            payload
                        )

                        # Validate chunk index
                        if 0 <= chunk_index < len(chunks):
                            if chunks[chunk_index] is None:
                                chunks[chunk_index] = chunk_data
                                received_in_burst += 1
                                logger.debug(
                                    f"Received chunk {chunk_index}: "
                                    f"{len(chunk_data)} bytes"
                                )
                            else:
                                logger.debug(
                                    f"Duplicate chunk {chunk_index} ignored"
                                )
                        else:
                            logger.warning(
                                f"Invalid chunk index {chunk_index} "
                                f"(expected 0-{len(chunks) - 1})"
                            )

                    else:
                        logger.warning(
                            f"Unexpected command during chunk reception: "
                            f"0x{cmd:02X}"
                        )

                except (LXBProtocolError, LXBChecksumError) as e:
                    logger.warning(f"Invalid chunk frame: {e}")
                    continue

        except socket.timeout:
            # Timeout is expected - end of burst
            logger.debug(
                f"Chunk burst timeout (received {received_in_burst} chunks)"
            )

        finally:
            self._sock.settimeout(original_timeout) # type: ignore

        return received_in_burst

    def _request_missing_chunks(self, missing_indices: list) -> None:
        """
        Send IMG_MISSING request for missing chunks.

        Args:
            missing_indices: List of missing chunk indices
        """
        seq_missing = self._next_seq()
        missing_frame = ProtocolFrame.pack_img_missing(seq_missing, missing_indices)
        self._send_frame(missing_frame)

        logger.info(
            f"Sent IMG_MISSING (seq={seq_missing}): {len(missing_indices)} chunks"
        )
