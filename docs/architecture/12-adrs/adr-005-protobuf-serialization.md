# ADR-005: Protobuf for Event Serialization over JSON/Avro

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board  
**Technical Story:** ORIG-ARCH-005  

---

## Context & Problem Statement

With 500M+ events per day flowing through Kafka, the serialization format significantly impacts:
- Network bandwidth (wire size)
- Serialization/deserialization CPU cost
- Schema evolution safety
- Developer experience (code generation vs manual mapping)
- Cross-language compatibility (future polyglot services)

Additionally, internal gRPC communication requires a serialization format, and unifying on a single format for both events and gRPC reduces cognitive overhead.

## Decision Drivers

* Performance: 500M+ msgs/day — even small size differences compound
* Schema safety: Breaking changes must be caught before deployment
* Evolution: Adding fields must not break existing consumers
* Code generation: Reduce boilerplate, ensure type safety
* Unified format: Same serialization for events and gRPC
* Tooling: Schema Registry compatibility
* Developer experience: Ease of defining and evolving schemas

## Considered Options

1. **Protocol Buffers (Protobuf) v3**
2. **Apache Avro**
3. **JSON (Jackson)**
4. **FlatBuffers**
5. **MessagePack**

## Decision Outcome

**Chosen option:** "Protocol Buffers v3" because it provides the best combination of compact wire format, strong schema evolution guarantees, code generation, and natural alignment with gRPC for internal communication. Using Protobuf for both Kafka events and gRPC APIs creates a unified, type-safe contract layer across the entire platform.

### Consequences

**Good:**
* ~10x smaller wire size than JSON (significant at 500M msgs/day)
* Strong backward/forward compatibility by design (proto3)
* Code generation: Java classes from .proto files (no manual mapping)
* Same .proto files used for Kafka events AND gRPC service definitions
* Schema Registry validates compatibility before deployment
* Binary format: faster serialization than JSON
* Well-defined evolution rules (never remove fields, never reuse numbers)
* Cross-language support (if future services use different languages)

**Bad:**
* Not human-readable (cannot inspect messages without tooling)
* Debugging requires protobuf decoder (kafka-console-consumer doesn't show plain text)
* Learning curve for developers unfamiliar with proto definitions
* Build pipeline requires protobuf compilation step
* Proto file management across services requires shared repository

**Neutral:**
* JSON still used for external REST APIs (human-readable requirement)
* Kafka UI and monitoring tools support Protobuf via Schema Registry
* Proto files serve as living documentation of event contracts

## Size Comparison Example

```
Event: LoanDisbursed (loan_id, amount, currency, timestamp, tenant_id)

JSON:        ~350 bytes
Avro:        ~120 bytes
Protobuf:    ~85 bytes
FlatBuffers: ~95 bytes

At 500M events/day:
JSON:        ~162 TB/year network + storage
Protobuf:    ~39 TB/year network + storage
Savings:     ~123 TB/year (76% reduction)
```

## Pros & Cons of Other Options

### Apache Avro
* Good: Confluent Schema Registry native support
* Good: Schema evolution (backward/forward)
* Good: Compact binary format
* Bad: Requires schema at read-time (schema must be fetched from registry)
* Bad: No natural alignment with gRPC (two formats to maintain)
* Bad: Container format adds overhead per message
* Bad: Code generation less clean than Protobuf (Specific vs Generic record)
* Bad: Cannot generate gRPC stubs from Avro schemas

### JSON (Jackson)
* Good: Human-readable (debugging friendly)
* Good: Universally understood, no compilation step
* Good: Every developer knows JSON
* Bad: 10x larger than Protobuf (massive cost at 500M msgs/day)
* Bad: No schema enforcement (any field can be any type)
* Bad: Schema evolution not enforced — relies on developer discipline
* Bad: Slower serialization/deserialization
* Bad: No code generation — manual DTO maintenance

### FlatBuffers
* Good: Zero-copy deserialization (fastest read)
* Good: Compact binary format
* Bad: Complex schema evolution model
* Bad: No gRPC integration
* Bad: Limited tooling ecosystem
* Bad: Fewer developers familiar with it
* Bad: Buffer access pattern unfamiliar (not POJO-like)

### MessagePack
* Good: Compact binary JSON
* Good: Human-inspectable (with tooling)
* Bad: No schema — same weakness as JSON
* Bad: No code generation
* Bad: No schema evolution guarantees
* Bad: No Schema Registry support

## Implementation Strategy

```
protobuf/
├── shared/
│   ├── money.proto           # Money value object
│   ├── metadata.proto        # Event envelope metadata
│   ├── common.proto          # Shared types (Timestamp, UUID)
│   └── pagination.proto      # Pagination types
├── events/
│   ├── los/
│   │   └── application_events.proto
│   ├── lms/
│   │   └── loan_events.proto
│   ├── ledger/
│   │   └── ledger_events.proto
│   └── payments/
│       └── payment_events.proto
└── services/
    ├── los/
    │   └── application_service.proto   # gRPC service definition
    ├── lms/
    │   └── loan_service.proto
    └── bre/
        └── rule_service.proto
```

## Links

* [Technology Decisions](../01-tech-stack/technology-decisions.md)
* [Event Architecture](../06-event-architecture/kafka-topology.md)
* [API Strategy](../07-api-strategy/external-api-design.md)
