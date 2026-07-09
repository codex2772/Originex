# ADR-004: Apache Kafka as Event Backbone over Alternatives

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board, Platform Team  
**Technical Story:** ORIG-ARCH-004  

---

## Context & Problem Statement

The platform requires an event backbone that can:
- Handle 500M+ messages per day (sustained throughput)
- Support exactly-once semantics for financial events
- Provide event replay capability (consumer offset reset)
- Enable log compaction for state snapshots
- Support multi-datacenter replication
- Guarantee event ordering within a partition
- Scale horizontally to handle peak loads (EOD processing bursts)

## Decision Drivers

* Throughput: 500M+ events/day (6K/s average, 50K/s peak)
* Exactly-once semantics required for financial correctness
* Event replay for disaster recovery and new consumer bootstrapping
* Ordering guarantees per aggregate (loan, account)
* Multi-region replication for DR
* Operational maturity and team expertise
* Ecosystem richness (Connect, Streams, Schema Registry)

## Considered Options

1. **Apache Kafka (self-managed via Strimzi on EKS)**
2. **Apache Kafka (Confluent Cloud managed)**
3. **Apache Pulsar**
4. **RabbitMQ**
5. **AWS MSK (Managed Streaming for Kafka)**
6. **AWS Kinesis**

## Decision Outcome

**Chosen option:** "Apache Kafka, self-managed via Strimzi Operator on EKS" because it provides the highest degree of control, proven exactly-once semantics, log-based replay, and ecosystem richness (Connect, Streams, Schema Registry) required for a Tier-1 financial platform. Self-managed via Strimzi gives full control over configuration, tuning, and security while maintaining Kubernetes-native operational simplicity.

### Consequences

**Good:**
* Full control over cluster configuration and tuning
* Exactly-once semantics (idempotent producers + transactional consumers)
* Log retention enables event replay (disaster recovery, new consumers)
* Log compaction for state snapshots
* MirrorMaker 2 for cross-region replication
* Rich ecosystem: Connect (CDC), Streams (lightweight processing), Schema Registry
* Strimzi operator: Kubernetes-native, auto-healing, rolling upgrades
* No vendor lock-in; can migrate to managed if needed later

**Bad:**
* Operational responsibility for cluster management
* Requires dedicated SRE expertise for Kafka operations
* Self-managed ZooKeeper (until KRaft fully stable at scale) — UPDATE: KRaft GA
* Storage management on EKS (EBS volume lifecycle)
* Upgrade planning and execution responsibility

## Pros & Cons of Other Options

### Confluent Cloud (Managed Kafka)
* Good: Zero operational overhead
* Good: Enterprise features (Schema Registry, ksqlDB)
* Good: Multi-tenant built-in
* Bad: Significantly higher cost at 500M msgs/day ($15K-$30K/month)
* Bad: Less control over cluster configuration
* Bad: Vendor lock-in to Confluent-specific features
* Bad: Data residency concerns (limited India availability historically)
* **Decision: Revisit if operational burden becomes unsustainable**

### Apache Pulsar
* Good: Built-in multi-tenancy
* Good: Tiered storage (automatic offload to S3)
* Good: Separate compute and storage
* Bad: Smaller community and ecosystem than Kafka
* Bad: Fewer experienced operators available for hire
* Bad: Schema Registry less mature than Confluent's
* Bad: Exactly-once semantics less battle-tested at this scale
* Bad: Fewer connector ecosystem options

### RabbitMQ
* Good: Excellent for task queues and RPC patterns
* Good: Simple to operate for small scale
* Bad: No persistent log (messages deleted after consumption)
* Bad: No replay capability
* Bad: Limited throughput at 500M+/day scale
* Bad: No log compaction
* Bad: Clustering reliability issues at scale
* **Decision: Rejected — fundamentally wrong architecture for event streaming**

### AWS MSK (Managed Kafka)
* Good: Managed service (reduced ops)
* Good: Native AWS integration (IAM, VPC)
* Good: Apache Kafka compatible
* Bad: Limited configuration flexibility
* Bad: Slower feature adoption (behind Apache Kafka releases)
* Bad: Higher cost than self-managed
* Bad: Vendor lock-in to AWS-specific IAM auth
* **Decision: Viable alternative if Strimzi ops become problematic**

### AWS Kinesis
* Good: Fully managed, zero ops
* Good: Native AWS integration
* Bad: Shard-based scaling model (hard limits, manual resharding)
* Bad: No exactly-once consumer semantics (requires DynamoDB checkpointing)
* Bad: No log compaction
* Bad: 7-day max retention (365 days with extended retention at high cost)
* Bad: No Schema Registry equivalent
* Bad: 1 MB record size limit
* Bad: Heavy vendor lock-in
* **Decision: Rejected — too many limitations for financial platform**

## Operational Considerations

### Strimzi Operator Benefits
* Kubernetes-native (CRDs for Kafka resources)
* Automated rolling upgrades
* Rack-aware replica placement (cross-AZ)
* Auto-healing (failed broker recreation)
* TLS and SCRAM authentication built-in
* Cruise Control integration for partition rebalancing

### Monitoring & Alerting
* JMX metrics exported to Prometheus
* Under-replicated partitions (P1 alert)
* Consumer group lag monitoring
* Disk space (80% threshold alert)
* Network throughput per broker

## Links

* [Event Architecture](../06-event-architecture/kafka-topology.md)
* [Service Interactions](../04-service-interactions/sync-patterns.md)
* [Strimzi Documentation](https://strimzi.io/documentation/)
