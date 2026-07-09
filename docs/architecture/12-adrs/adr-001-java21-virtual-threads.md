# ADR-001: Java 21 with Virtual Threads as Primary Runtime

**Status:** ACCEPTED  
**Date:** 2026-07-08  
**Deciders:** Architecture Board  
**Technical Story:** ORIG-ARCH-001  

---

## Context & Problem Statement

The Originex platform must handle 100K+ loan applications per day, tens of thousands of repayments per minute, and 500M+ ledger transactions per day. The runtime must support high-concurrency I/O-bound workloads (database calls, Kafka interactions, gRPC calls, external API integrations) while maintaining code readability and developer productivity.

## Decision Drivers

* High concurrency requirement (thousands of simultaneous operations)
* I/O-bound workloads (DB, Kafka, external APIs)
* Developer productivity and code readability
* Financial system correctness (BigDecimal, strong typing)
* Hiring availability in India fintech market
* Ecosystem maturity for enterprise financial systems
* Long-term support and stability

## Considered Options

1. **Java 21 with Virtual Threads (Project Loom)**
2. **Java 21 with Project Reactor (Reactive Streams)**
3. **Kotlin with Coroutines**
4. **Go**
5. **Node.js with TypeScript**

## Decision Outcome

**Chosen option:** "Java 21 with Virtual Threads" because it provides high-concurrency performance equivalent to reactive programming while maintaining imperative, easy-to-read code. Combined with Spring Boot 3.x virtual thread support, it offers the best balance of throughput, correctness, and developer productivity for a financial platform.

### Consequences

**Good:**
* Imperative code style — easier to debug, reason about, and maintain
* Millions of concurrent virtual threads possible without thread pool exhaustion
* Full backward compatibility with existing Java libraries
* Strong type system catches errors at compile time
* BigDecimal and mature financial libraries available
* Largest developer pool in India fintech
* 25+ year ecosystem maturity

**Bad:**
* Virtual threads are relatively new (GA in Java 21) — potential edge cases
* Pinning issues with synchronized blocks (mitigated by using ReentrantLock)
* Some libraries not yet optimized for virtual threads (rare, monitored)
* Cannot use thread-local storage carelessly (must use scoped values)

**Neutral:**
* GraalVM native compilation available if startup time becomes critical
* Structured concurrency (preview) will further improve the model

## Pros & Cons of Options

### Java 21 with Virtual Threads
* Good: Imperative code — easy to debug stack traces
* Good: 1:1 mapping to OS threads not required — millions of VTs possible
* Good: Spring Boot 3.4+ native support (`spring.threads.virtual.enabled=true`)
* Good: BigDecimal + strong typing for financial correctness
* Good: Largest Java developer pool; proven in banking
* Bad: Newer feature (Java 21 GA Sept 2023) — 3 years in production by others
* Bad: Thread-pinning with `synchronized` blocks (use ReentrantLock instead)
* Bad: ThreadLocal replaced by ScopedValues (still preview)

### Java 21 with Project Reactor (Reactive)
* Good: Proven high-throughput non-blocking I/O
* Good: Mature (5+ years in production)
* Bad: Reactive code is hard to read, debug, and maintain
* Bad: Stack traces are meaningless in reactive chains
* Bad: Steep learning curve — most developers struggle
* Bad: Error handling is complex (onErrorResume, onErrorReturn chains)
* Bad: Testing reactive code is significantly harder
* Bad: With virtual threads available, reactive complexity is unnecessary

### Kotlin with Coroutines
* Good: Excellent concurrency model
* Good: Null safety, extension functions, DSLs
* Good: Spring Boot support mature
* Bad: Smaller hiring pool in India fintech (Java dominates)
* Bad: Coroutines + virtual threads interaction still evolving
* Bad: Build tool complexity (Kotlin compiler + Gradle KTS)
* Bad: Runtime is still JVM — same deployment profile

### Go
* Good: Excellent concurrency (goroutines)
* Good: Small binary, fast startup
* Good: Simple language, fast to learn
* Bad: No mature ORM for complex domain models
* Bad: No DI framework comparable to Spring
* Bad: Weak type system for complex financial logic
* Bad: No BigDecimal equivalent (must use third-party)
* Bad: Smaller financial system ecosystem

### Node.js with TypeScript
* Good: Fast development for simple APIs
* Bad: Single-threaded event loop — CPU-bound operations block
* Bad: No native BigDecimal — financial precision issues
* Bad: Weak runtime type safety despite TypeScript
* Bad: npm dependency hell — security concerns
* Bad: Not proven for Tier-1 financial systems at this scale

## Links

* [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
* [Spring Boot Virtual Threads](https://docs.spring.io/spring-boot/reference/features/threading.html)
* [Technology Decisions](../01-tech-stack/technology-decisions.md)
