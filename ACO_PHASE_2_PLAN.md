# Phase 2: ACO Atomic Save Transfer Implementation

## 1. ACO Protocol Extensions (P1 layer)
- Added types to `AcoMessageType`: `SAVE_TRANSFER_BEGIN`, `SAVE_TRANSFER_CHUNK`, `SAVE_TRANSFER_END`, `SAVE_TRANSFER_ACK`.
- Messages carry a `transferId` (UUID string).

## 2. Transfer Metadata & Limits
- **Max Compressed Save Size:** 64MB (enforced via `MAX_COMPRESSED_SIZE`).
- **Max Chunk Size:** 32KB.
- **Timeout:** 30s idle timeout per chunk.
- **CRC32:** Computed over the compressed `byte[]` stream before transmission.

## 3. Atomic Commit Flow
- Guest writes to `temp` file.
- Post-transfer: Byte count and CRC32 verification.
- Atomic Swap: `Files.move(..., ATOMIC_MOVE | REPLACE_EXISTING)`.
- Cleanup: Failure triggers immediate deletion of the `temp` file.

## 4. Implementation Plan
- `AcoTransferSession`: Tracks transfer state (CRC, byte counter, file handle).
- `AcoTransferManager`: Registry of active transfers, prevents ID collisions.
- `NetworkCampaign` Update: Wire existing `SectorStore` logic into the new transfer pipeline.
