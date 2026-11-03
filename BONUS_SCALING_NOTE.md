# Architectural Blueprint: Scaling to 10M–15M Residential Gateways
**Target Domain:** AT&T Fiber & Fixed Wireless Broadband Blueprint  
**Role Scope:** Principal Software Engineer — Broadband Device Management  

---

## 1. Executive Summary: What Breaks at 15 Million Devices?

When managing 15,000,000 gateways, the simplified TR-069 HTTP "Connection Request" nudge and single-instance controller architecture fail across multiple dimensions:

1. **The Inbound Nudge Problem**: Gateways reside behind Carrier-Grade NAT (CGNAT) and firewalls. Initiating 15M outbound HTTP connection requests requires opening millions of concurrent outbound TCP connections, triggers stateful firewall connection tracking table exhaustion, and suffers high drop rates.
2. **Thundering Herd Inform Storms**: If 500,000 devices wake up in a 1-minute window, the controller ingress faces 500,000 concurrent TLS handshakes and database write contentions.
3. **Database Write Bottlenecks**: A single relational database cannot withstand 100k state transitions per second without horizontal sharding or streaming log aggregation.

---

## 2. Replacing the REST "Nudge" with Modern Persistent Push Protocols

In modern broadband architectures (e.g. Broadband Forum TR-369 / USP), HTTP polling/nudging is replaced by **persistent, multiplexed bi-directional edge connections**:

```
+-----------------------------------------------------------------------------------------+
|                                15M Broadband Gateways (CPE)                             |
+-----------------------------------------------------------------------------------------+
                                    │  Persistent TLS Sessions
                                    ▼
+-----------------------------------------------------------------------------------------+
|                  Edge Connection Gateways (Stateless Go / Rust / Envoy)                 |
|             (Terminates MQTT over TLS / WebSockets / gRPC Bidirectional Streams)         |
+-----------------------------------------------------------------------------------------+
                                    │  Kafka Event Ingestion (Partitioned by Device ID)
                                    ▼
+-----------------------------------------------------------------------------------------+
|                         Apache Kafka Distributed Event Backbone                         |
|                    Topic: gateway.informs (1,024 partitions, keyed by deviceId)          |
+-----------------------------------------------------------------------------------------+
                                    │
                                    ▼
+-----------------------------------------------------------------------------------------+
|                      Stateless Campaign Orchestrator Worker Fleet                       |
|           (Consumes Kafka Partitions via Consumer Groups; Local In-Memory FSM)          |
+-----------------------------------------------------------------------------------------+
```

### Why Persistent Push over REST Nudge?
- **NAT Traversal**: Gateways initiate outbound TLS connections to the Edge Broker and maintain light TCP/TLS keep-alives (e.g., ping every 60s). No inbound firewall holes or STUN/TURN needed.
- **Microsecond Command Latency**: Controller publishes to `devices/{deviceId}/commands`; the device receives it within milliseconds without needing an inform round-trip handshake.
- **Edge Sharding**: 15M devices can be terminated by 150 stateless Edge Proxy instances (100,000 idle TLS connections per 8GB Go/Rust container).

---

## 3. Distributed Campaign Partitioning & Orchestration

To run multiple controller instances without locking conflicts or split-brain states:

1. **Hash Partitioning by `deviceId`**:
   - Device IDs are hashed into 1,024 or 4,096 deterministic partitions across Kafka topics.
   - Each controller instance is assigned an exclusive subset of partitions via standard Kafka consumer group rebalancing.
   - **Zero Distributed Locks**: Because all events for a given device always route to the exact same partition and controller replica, state machine transitions occur strictly in-memory or with localized row locks without requiring heavy two-phase commit (2PC) or distributed ZooKeeper locks.

2. **Progressive Canary Waves & Blast-Radius Management**:
   Never upgrade 15 million devices simultaneously. Implement phased **Canary Rings**:
   - **Ring 0 (Internal / Labs)**: 1,000 devices (SLA soak: 24 hours).
   - **Ring 1 (Canary 1%)**: 150,000 devices across diverse hardware chipsets (Broadcom, Mediatek, Qualcomm).
   - **Ring 2 (Regional 10%)**: 1,500,000 devices rolled out during low-traffic maintenance windows (2:00 AM – 5:00 AM local time).
   - **Ring 3 (Broad Fleet 100%)**: Remaining fleet rolled out with strict auto-abort circuit breakers.

3. **Circuit Breaker Algorithm at Scale**:
   - Evaluated as a sliding-window binomial proportion test with statistical confidence intervals (Wilson Score Interval).
   - If early failure rate in Ring 1 exceeds 0.5% with $p < 0.001$, the campaign automatically halts and triggers auto-rollback across affected rings.

---

## 4. Telemetry, Recording & Real-Time Fleet Status Reporting

### During the Campaign (Hot Path)
- **State Ingestion**: State transitions emit lightweight Protobuf events to Kafka topic `campaign.events`.
- **Real-Time Aggregation (Apache Flink / Kafka Streams)**:
  - Flink continuously computes 5-second tumbling and sliding window metrics:
    - `active_upgrades_by_region`, `failure_rate_by_hardware_revision`, `reboot_duration_p99`.
  - Publishes aggregated rollups to **ClickHouse / Apache Pinot** for real-time sub-second analytics dashboards.

### After the Campaign (Cold Path & Audit)
- **Immutable Audit Ledger**: Long-term storage in Apache Iceberg / Parquet on Amazon S3 or Azure ADLS.
- **Fleet Version Consistency**: Fleet state converges in a high-speed distributed document store (e.g. ScyllaDB or Cassandra), queried via GraphQL / REST for field technicians and customer care.

