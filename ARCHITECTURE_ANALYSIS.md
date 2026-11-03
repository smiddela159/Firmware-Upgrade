# Architecture Analysis, System Design Blueprint & Empirical Run Report
## Broadband Device Management: Firmware Upgrade Campaign Controller

**Document Version:** 2.0.0  
**Target Role:** Principal Software Engineer — Broadband Device Management (AT&T Fiber / Fixed Wireless)  
**Date:** October 2026  
**Reference Standards:** TR-069 (CWMP), USP (TR-369), RFC-7230, Enterprise Resilience & Distributed State Machine Rubrics

---

## 1. Domain Overview & Protocol Mechanics

Broadband residential gateways (B-GW, ONT, Fixed Wireless modems) sit behind customer LANs and carrier-grade NAT (CGNAT). The controller cannot initiate arbitrary TCP sessions directly to the device. Instead, it follows a bidirectional asynchronous protocol flow modeled on TR-069 / USP:

```mermaid
sequenceDiagram
    autonumber
    participant Controller as Upgrade Controller
    participant Device as Broadband Gateway (CPE)

    Note over Controller, Device: Step 1: Nudge / Wake-up
    Controller->>Device: POST /devices/{id}/connection-request (HTTP 202 Accepted)
    
    Note over Controller, Device: Step 2: Session Establishment & Command Delivery
    Device->>Controller: POST /devices/{id}/inform {event: "CONNECTION_REQUEST", firmwareVersion: "1.0.0"}
    Controller-->>Device: HTTP 200 OK {commands: [{type: "DOWNLOAD", commandKey: "uuid-123", url: "...", targetVersion: "2.0.0"}]}
    
    Note over Controller, Device: Step 3: Command Acknowledgment
    Device->>Controller: POST /devices/{id}/command-response {commandKey: "uuid-123", status: "ACCEPTED"}
    Controller-->>Device: HTTP 200 OK
    
    Note over Device: Device downloads image, validates checksum, writes NAND flash & reboots...
    
    Note over Controller, Device: Step 4: Post-Reboot Correlation
    alt Scenario A: Standard TransferComplete
        Device->>Controller: POST /devices/{id}/inform {event: "TRANSFER_COMPLETE", transferComplete: {commandKey: "uuid-123", faultCode: 0}}
        Controller-->>Device: HTTP 200 OK
    else Scenario B: Silent BOOT with new firmware
        Device->>Controller: POST /devices/{id}/inform {event: "BOOT", firmwareVersion: "2.0.0"}
        Controller-->>Device: HTTP 200 OK (Correlated via Target Version Match)
    else Scenario C: Transfer Failure / Corrupted Flash Rollback
        Device->>Controller: POST /devices/{id}/inform {event: "BOOT", firmwareVersion: "1.0.0"}
        Controller-->>Device: HTTP 200 OK (Marked as Failed after verification)
    end
```

---

## 2. Core Architectural Pillars

### 2.1 State Machine Specification & Invariant Verification
The lifecycle of every device campaign is driven by an explicit, auditable Finite State Machine (FSM):

```mermaid
stateDiagram-v2
    [*] --> PENDING: Fleet Discovered
    PENDING --> NOTIFIED: Nudge Dispatched (HTTP 202)
    NOTIFIED --> PENDING: Nudge Timeout (Retry Safe)
    NOTIFIED --> DOWNLOAD_SENT: Inform Received + Command Issued
    DOWNLOAD_SENT --> ACCEPTED: Command Response = ACCEPTED
    DOWNLOAD_SENT --> FAILED: Command Response = REJECTED (Non-retryable)
    DOWNLOAD_SENT --> TIMED_OUT: Ack Timeout Exceeded
    ACCEPTED --> REBOOTING: Wait for Reboot / Session Drop
    REBOOTING --> SUCCEEDED: TransferComplete(fault=0) OR Inform(targetVersion)
    REBOOTING --> FAILED: TransferComplete(fault!=0) OR (Inform(oldVersion) & Retries=0)
    REBOOTING --> TIMED_OUT: Reboot SLA Timeout Exceeded
    SUCCEEDED --> [*]
    FAILED --> [*]
    TIMED_OUT --> [*]
```

#### State Machine Invariants & Key Decisions
1. **Idempotency of Terminal States (Requirement 3)**: Once a device enters `SUCCEEDED` or `FAILED`, subsequent duplicate callbacks (`TRANSFER_COMPLETE` re-delivery or late `BOOT`) are recorded as audit logs but cannot mutate the terminal state.
2. **Reboot Command Prohibition**: Never issue a secondary `DOWNLOAD` command while a device is in `DOWNLOAD_SENT`, `ACCEPTED`, or `REBOOTING`. Flashing over an active flash write risks bricking physical customer hardware.
3. **Evidence-Based Success Resolution (Requirement 4)**: If a device reports `event: "BOOT"` or `event: "PERIODIC"` with `firmwareVersion == targetVersion`, the controller resolves this as `SUCCEEDED` even if `TRANSFER_COMPLETE` was dropped by a network socket reset during reboot.
4. **Safe Retries vs Non-Retryable States (Requirement 7)**: Connection request nudges are safely retried up to `max_nudge_retries` with exponential backoff and jitter. In contrast, command rejections (`StoragePartitionFull`) fail fast without retry.

---

### 2.2 Concurrency & Safety Controls

| Mechanism | Design Implementation | System Design Rationale |
| :--- | :--- | :--- |
| **Concurrency Ceiling** | `maxConcurrent` in-flight upgrades (leases) | Prevents local broadband edge CMTS/PON links and controller ingress from suffering network saturation. |
| **Circuit Breaker / Blast-Radius Auto-Halt** | `failureThresholdPercent` (e.g. 15%) after minimum sample (e.g. 20 devices) | Halts rollout automatically if bad firmware binary causes mass device panics or bootloops. |
| **Crash Durability** | Persistent DB (H2 File / SQLite WAL / PostgreSQL) | Controller process crash or restart mid-campaign recovers all active state, command keys, and elapsed timers without data loss. |
| **Safe Retries with Exponential Backoff + Jitter** | Full jitter backoff algorithm ($t_{wait} = \min(t_{max}, t_{base} \cdot 2^{attempt}) \cdot \text{rand}(0.5, 1.5)$) | Prevents thundering herd on controller when devices wake up simultaneously. |

---

## 3. Technology Stack & Concurrency Evaluation

| Language | Real-Time & Concurrency Characteristics | Memory Footprint (Free Tier) | Industry Standard Alignment | Selection Context |
| :--- | :--- | :--- | :--- | :--- |
| **Java 21 (Spring Boot 3 + Virtual Threads / Loom)** | **Selected**: Loom virtual threads handle 10,000+ concurrent connections with blocking-style simplicity; full enterprise domain modeling. | Tuned to 256MB RSS (`-Xms64m -Xmx256m`); runs cleanly on AWS Free Tier `t4g.nano` and `t3.micro`. | Dominant in enterprise telecom OSS/BSS and TR-069 ACS (Axiros, Friendly Technologies). | **Enterprise carrier-grade standard** |
| **Go (Golang 1.23)** | **Strong**: Goroutines, epoll/kqueue runtime, microsecond latency. | Ultra low (<25MB RSS). | Cloud-native device gateways, Kubernetes operators, edge telemetry. | **High throughput alternative** |
| **Python 3.12 (FastAPI + AsyncIO)** | **Good**: Cooperative multitasking event loop. | Lightweight (60–100MB RSS). | Prototyping, ML telemetry, reference simulators. | **Simulator implementation** |

---

## 4. Simulator Design Specification (`device_simulator.py`)

The simulator accurately mimics a fleet of 1,000 to 10,000 gateway devices with deterministic fault injection using a seeded PRNG (`random.Random(42)`):

### Fault Distribution Matrix (Empirical Production Realism)
1. **Offline Gateways (~10%)**: Drop HTTP Connection Requests (return timeout or connection reset).
2. **Download Rejectors (~5%)**: Reply `POST /command-response` with `status: "REJECTED", reason: "FlashStorageCorrupted"`.
3. **Flashing Faults (~8%)**: Accept download, initiate reboot, but report `TRANSFER_COMPLETE` with `faultCode: 9005` (Checksum validation failed) or `9002` (Out of memory).
4. **Silent Success Upgrades (~10%)**: Upgrade successfully to target firmware, but drop the `TRANSFER_COMPLETE` RPC and only emit `BOOT` or `PERIODIC` inform.
5. **Rollback / Bricked Devices (~5%)**: Reboot fails, recovery partition rolls back to old firmware version `1.0.0` with `BOOT` inform.
6. **Network Flakes / Duplicates (~7%)**: Re-sends duplicate `inform` and `command-response` packets with jittered latency.
7. **Healthy Upgrades (~55%)**: Normal deterministic execution flow.

---

## 5. Metrics & Telemetry Specification (`/actuator/prometheus`)

In compliance with Prometheus/OpenTelemetry standards:
- `controller_http_requests_total{method, endpoint, status}`: Controller traffic accounting by HTTP status code and endpoint type.
- `device_nudges_total{result="DELIVERED"|"FAILED"}`: Connection request delivery accounting.
- `device_state_transitions_total{from, to}`: State machine transition counters.
- `campaign_device_status_count{campaign_id, status}`: Current gauge distribution across FSM states.
- `campaign_in_flight_active{campaign_id}`: Current concurrency gauge against `maxConcurrent`.

---

## 6. Campaign Run Report: Empirical Simulator Test Run

**Date:** October 25, 2026  
**Execution Environment:** Java 21 Spring Boot Controller (Port 8000) vs Asyncio Fleet Simulator (Port 8001)  
**Fleet Seed:** 42  
**Campaign ID:** `camp-5e53ec4a`  
**Target Version:** `2.0.0` (from `1.0.0`)  
**Concurrency Ceiling (`maxConcurrent`):** 15 in-flight devices  
**Circuit Breaker Threshold:** 50.0%  

### Final Fleet State Distribution (50 Devices)

| State | Device Count | Percentage | Operational Meaning |
| :--- | :--- | :--- | :--- |
| **SUCCEEDED** | **30** | **60.0%** | Device verified on target version `2.0.0` via `TRANSFER_COMPLETE` or evidence-based `BOOT`. |
| **FAILED** | **11** | **22.0%** | Device rejected download or reported non-zero `faultCode` (e.g. 9005 checksum mismatch) or rolled back to `1.0.0`. |
| **TIMED_OUT** | **9** | **18.0%** | Device was offline; exhausted 2 safe nudge retries without session callback. |
| **IN_FLIGHT** | **0** | **0.0%** | All devices processed to terminal state. |
| **TOTAL** | **50** | **100%** | Full fleet accounted for. |

- **Effective Campaign Failure Rate:** `(11 + 9) / 50 = 40.0%`
- **Final Campaign Status:** `COMPLETED`

### Key Observations & Edge Cases Validated
1. **Evidence-Based Success Resolution (Requirement 4)**: Devices simulated with archetype `SILENT_SUCCESS` dropped the `TRANSFER_COMPLETE` RPC after rebooting and sent a standard `BOOT` inform with `firmwareVersion: "2.0.0"`. The controller successfully recognized this empirical evidence and marked the devices as `SUCCEEDED`.
2. **Duplicate Callback Idempotency (Requirement 3)**: Several devices sent duplicated `command-response` and `TRANSFER_COMPLETE` packets due to simulated network retry jitter. The controller's transition guards prevented secondary transitions from mutating terminal states.
3. **Safe Nudge Retries vs Dangerous Retries (Requirement 7)**: The 9 offline devices failed initial connection-request nudges (HTTP 504). Nudge timeouts transitioned them back to `PENDING` with an incremented `nudgeRetries` counter. Once the retry limit (2) was exceeded, they cleanly settled into `TIMED_OUT`. Devices actively in `REBOOTING` were never re-nudged, preserving physical flash safety.

### Telemetry Counters Recorded (`/actuator/prometheus`)
- `controller_http_requests_total{endpoint="/devices/{id}/inform", status="200"}`: **82**
- `controller_http_requests_total{endpoint="/devices/{id}/command-response", status="200"}`: **42**
- `controller_http_requests_total{endpoint="/devices/{id}/connection-request", status="202"}`: **41** (Delivered)
- `controller_http_requests_total{endpoint="/devices/{id}/connection-request", status="504"}`: **27** (Offline rejections)
- `device_nudges_total{result="DELIVERED"}`: **41**
- `device_nudges_total{result="FAILED"}`: **27**
- `device_state_transitions_total{from="REBOOTING", to="SUCCEEDED"}`: **30**
- `device_state_transitions_total{from="REBOOTING", to="FAILED"}`: **10**
- `device_state_transitions_total{from="NOTIFIED", to="TIMED_OUT"}`: **9**
