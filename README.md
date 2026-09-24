# PrivPrint — Privacy-First Secure Xerox & Print Platform

[![Android](https://img.shields.io/badge/Platform-Android%2014+-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Cryptography](https://img.shields.io/badge/Security-AES--256--GCM-blue.svg)](https://csrc.nist.gov)
[![Room Database](https://img.shields.io/badge/Persistence-Room%20KSP%20%2B%20Atomic%20Transactions-orange.svg)](https://developer.android.com/training/data-storage/room)

**PrivPrint** eliminates the pervasive privacy hazard of sharing confidential documents (employment contracts, ID cards, financial records, medical reports) via WhatsApp, Bluetooth, or USB drives with neighborhood Xerox shops and print centers.

---

## 🛡️ Production Security Architecture & Verification

### 1. Canonical API Specification (`/api/v1/...`)
All client interactions communicate through standard, authenticated endpoints:
- `POST /api/v1/auth/login` — Issues short-lived access tokens (15m) and rotating refresh tokens.
- `POST /api/v1/auth/refresh` — Single-use refresh token rotation with automated replay theft detection.
- `POST /api/v1/auth/logout` — Token revocation registry invalidating JTI and sessions.
- `GET /api/v1/shops` & `GET /api/v1/shops/{id}/permanent-qr` — Verified merchant directory and counter pairing codes.
- `POST /api/v1/sessions` — Ephemeral pairing session bounded to a 15-minute Time-To-Live (TTL).
- `POST /api/v1/documents/init-upload` & `complete-upload` — Ciphertext-only document ingestion.
- `POST /api/v1/jobs` — Protected job creation requiring `Idempotency-Key` headers.
- `GET /api/v1/print/jobs` — Dedicated shop/print-device queue; rejects unauthorized customer access (403).
- `POST /api/v1/print/jobs/{id}/increment-copy` — Atomic copy consumption.
- `GET /healthz` — Service health probe.

### 2. Client-Side AES-256-GCM Authenticated Encryption
- Documents are encrypted on the user's phone **before transmission** using standard `AES/GCM/NoPadding`.
- A fresh, cryptographically secure 256-bit symmetric key (`SecureRandom`) and 96-bit Initialization Vector (IV) are generated per job.
- Only the shop's volatile memory buffer receives the payload. No plaintext is ever written to persistent disk.

### 3. Atomic Hardware Copy Limits & Concurrency Safety
- When a user authorizes **N copies** (e.g., exactly 2 copies), the shop terminal cannot print 3 copies.
- Copy counts are enforced using **Room `@Transaction`** with atomic check-and-increment operations:
  ```kotlin
  @Transaction
  suspend fun incrementCopyCountAtomic(jobId: String): CopyIncrementResult {
      val job = getJobById(jobId) ?: return CopyIncrementResult.JobNotFound
      if (job.copiesPrinted >= job.copiesAuthorized) {
          insertAuditEvent(AuditEventEntity(..., eventType = "COPY_LIMIT_ENFORCED"))
          return CopyIncrementResult.LimitReached(job.copiesAuthorized)
      }
      val updated = job.copiesPrinted + 1
      updateJob(...)
      return CopyIncrementResult.Success(updated, job.copiesAuthorized, isCompleted = updated >= job.copiesAuthorized)
  }
  ```
- **Concurrency Test Verification**: 10 simultaneous racing threads attempting to print a 2-copy job results in exactly 2 successes and 8 rejections (`CopyIncrementResult.LimitReached`).

### 4. Real Printer Hardware Adapters (`PrinterInterface`)
No mock printers in production paths:
- `AndroidPrintAdapter`: Direct integration with Android's system `PrintManager` and native spooler for Wi-Fi, Bluetooth, and vendor print services (Mopria, Canon, HP).
- `NetworkPrinterAdapter`: Direct IPP / JetDirect Raw 9100 socket streaming for commercial Xerox and laser multi-function centers.
- `TestPrinterAdapter`: In-memory adapter strictly reserved for automated test suites.

### 5. Cross-Device Realtime Transport (`RealtimeTransportClient`)
Event-driven bi-directional status streaming with channel isolation, automatic reconnection, heartbeat ping/pong, and event deduplication:
- Events: `SESSION_CREATED`, `QR_SCANNED`, `PAIRED`, `AUTHORIZED`, `UPLOAD_STARTED`, `UPLOAD_COMPLETED`, `JOB_CREATED`, `JOB_QUEUED`, `PRINT_STARTED`, `PRINT_PROGRESS`, `COPY_STARTED`, `COPY_COMPLETED`, `PRINT_COMPLETED`, `PRINT_FAILED`, `JOB_CANCELLED`, `SESSION_REVOKED`, `SESSION_EXPIRED`, `CLEANUP_COMPLETED`.

### 6. Verified Two-Phase Storage Deletion (`VerifiedCleanupEngine`)
- Deletion is verified before declaring cleanup complete (`CLEANUP_PENDING` → `CLEANUP_RETRY` → `CLEANUP_COMPLETED`).
- Explicit zeroization of in-memory keys and plaintext byte buffers (`keyBytes.fill(0)`).

---

## 📱 App Highlights & User Journeys

### User Mode
1. **Home Screen**: Active job tracking card, nearby verified partner shops, quick print history.
2. **Scan Xerox QR**: Viewfinder with animated laser line, instant test chips for emulator testing, and manual Shop ID entry (`SHOP-101`).
3. **Shop Connected**: Verification checkmark, session TTL timer, and direct memory transfer guarantees.
4. **Document Picker**: System Storage Picker (SAF) + preloaded confidential sample documents (`Employment Contract`, `National ID`, `Medical Prescription`).
5. **Print Settings**: Copies stepper, Color vs B&W, Paper size (A4, Letter, Legal), and Duplex options.
6. **Cryptographic Confirmation**: Clear breakdown of encryption parameters, copy limits, and memory wipe policy before authorizing.
7. **Live Print Tracking**: Real-time 5-stage pipeline with progress percentage, page/copy counters, and cancellation controls.
8. **Privacy & Security Center**: Session status, countdown timer, emergency transmission revoker, and live immutable audit trail.
9. **Print History**: Past jobs with metadata preservation only (document payloads permanently shredded).

### Shop Operator Mode
1. **Operator Dashboard**: Counter QR shortcut, incoming memory queue count, active printer status, and one-tap "Print Job" action.
2. **Counter Signage**: High-contrast, scalable QR canvas designed for counter stands.
3. **Shop Queue**: Full list of waiting jobs with "Print" and "Test Copy Cap" buttons.
4. **Connected Printers**: Real-time status for laser hardware (toner percentages, paper tray levels, lifetime counters).
5. **Security Audit**: Shop-level audit log of transmissions and security events.

---

## 🧪 Testing & Verification

Comprehensive Robolectric & Local JVM Test Suites:
- `ApiContractTest`: Validates `/api/v1/...` models, endpoints, and error code taxonomy.
- `AuthSecurityTest`: Validates role enforcement, token expiration, single-use refresh rotation, and logout revocation.
- `CopyConcurrencyTest`: Validates atomic copy enforcement under multi-threaded contention (10 racing threads for 2 copies).
- `PrinterAdapterTest`: Validates `AndroidPrintAdapter`, `NetworkPrinterAdapter`, and `PrinterInterface` contracts.
- `CleanupStateMachineTest`: Validates two-phase verified storage deletion, retry backoff, and memory zeroization.
- `RealtimeTransportTest`: Validates realtime event streaming, deduplication, and channel isolation.
- `ExampleRobolectricTest`: Validates AES-256-GCM encryption/decryption and shop QR parsing.

Run all tests:
```bash
gradle :app:testDebugUnitTest
```
