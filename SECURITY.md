# PrivPrint Security Specification & Threat Model

## 1. Cryptographic Primitive Specifications

| Component | Standard / Algorithm | Details |
| :--- | :--- | :--- |
| **Symmetric Cipher** | `AES/GCM/NoPadding` | 256-bit key length, 128-bit authentication tag |
| **Key Generation** | `KeyGenerator.getInstance("AES")` | Initialized with 256 bits via CSPRNG (`SecureRandom`) |
| **Initialization Vector** | 96-bit (12 bytes) | Fresh nonces generated using `SecureRandom` per job |
| **Integrity / Fingerprint** | `SHA-256` | Truncated hexadecimal fingerprint of symmetric key |
| **Memory Sanitization** | Explicit Zeroization | Decryption keys zeroized (`fill(0)`) upon job completion |

---

## 2. Trust Boundaries & Data Visibility

| Subsystem | Plaintext Document Access | Decryption Key Access | Ciphertext Access | Role Requirement |
| :--- | :---: | :---: | :---: | :--- |
| **Customer App** | YES (Local source) | YES (Generated client-side) | YES (Generated) | `USER` |
| **API Backend / Transit** | NO | NO | YES | Authenticated TLS + Bearer |
| **Storage (Object / S3)** | NO | NO | YES | Sealed ciphertext only |
| **Shop Terminal UI** | NO | NO | YES (Metadata only) | `SHOP_OPERATOR` |
| **Print Daemon / Spooler** | YES (During rasterization) | Ephemeral (Held in memory only) | YES | `PRINT_DEVICE` |
| **Laser Drum / Output** | Physical Paper | N/A | N/A | Physical custody |

---

## 3. Threat Model & Mitigations

### Threat A: Document Snooping / WhatsApp Archival
* **Risk**: When customers send PDFs via WhatsApp/Telegram to Xerox shop staff, files remain in "Media Received" galleries, cloud backups, and local hard drives indefinitely.
* **PrivPrint Mitigation**: Documents are encrypted client-side with AES-256-GCM. Decryption keys are never stored on persistent storage. The payload resides strictly in volatile memory.

### Threat B: Unauthorized Extra Copies ("Rogue Print Run")
* **Risk**: A shop operator or compromised terminal attempts to print extra copies of a confidential document (e.g. contracts, diplomas, bank statements).
* **PrivPrint Mitigation**: Atomic SQLite transactions (`@Transaction`) enforce copy caps with concurrency protection. Every copy attempt increments the counter; if `copiesPrinted >= copiesAuthorized`, the query returns `CopyIncrementResult.LimitReached` and rejects spooling. 10 concurrent requests for 2 copies will allow exactly 2 and reject 8.

### Threat C: In-Transit Interception
* **Risk**: Man-in-the-middle sniffing on shop Wi-Fi or public network.
* **PrivPrint Mitigation**: Authenticated GCM encryption guarantees both confidentiality and ciphertext integrity. Tampered payloads fail decryption with an AEAD tag mismatch.

### Threat D: Long-Lived Session Hijacking
* **Risk**: A customer leaves the shop but their connection remains open.
* **PrivPrint Mitigation**: Every paired session is bound to a strict 15-minute Time-To-Live (TTL). Once expired, session tokens are invalidated and discarded. Customers also have an **Emergency Revoke** button to terminate transmission immediately.

### Threat E: Premature Storage Shredding Claim
* **Risk**: Storage deletion fails silently on the backend while the client is falsely notified that data was destroyed.
* **PrivPrint Mitigation**: The `VerifiedCleanupEngine` mandates two-phase verified deletion (`CLEANUP_PENDING` → `CLEANUP_RETRY` → `CLEANUP_COMPLETED`). Deletion is actively confirmed against the storage subsystem before audit events and status reflect completion.

---

## 4. Honest Security Boundary

1. **Decryption at Print Boundary**: PrivPrint provides authenticated end-to-end encryption from customer phone to the authorized print spooler. Because physical printers require rasterized or PostScript/PDF data to deposit toner on paper, decryption necessarily takes place within the trusted print device environment.
2. **Volatile Memory**: While sensitive byte arrays are explicitly filled with zeros (`keyBytes.fill(0)`), JVM garbage collection timing means total memory wipe cannot be guaranteed at the kernel level without native C/Rust secure memory allocators (`mlock`). PrivPrint implements best-effort secure memory wiping for the Android platform.
3. **Physical Custody**: Once sheets exit the output tray, physical security controls (shredding discarded drafts, collecting prints promptly) govern physical access.
