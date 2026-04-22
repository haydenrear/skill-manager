# Validation Graph Constitution

## Purpose

This system exists to make validation persistent, compositional, inspectable, and reproducible.

Validation logic must not live only in transient agent behavior or one-off shell commands. It must be encoded as a reusable graph of executable nodes that can be inspected, composed, reported on, and re-run later.

## Core Model

The primary abstraction is a **validation graph**.

A validation graph is composed of **nodes** and **dependencies**.

A node is a unit of executable validation-related work, such as:

- provisioning a testbed
- seeding fixtures
- performing an action
- asserting an invariant
- collecting evidence
- aggregating reports

A dependency is an explicit relationship between nodes that determines execution order and composition.

## Design Principles

### 1. Graph-first, not script-first

Scripts are implementations of nodes.
Scripts are not the primary abstraction.

The system must preserve a graph-level model that can be understood without reverse engineering arbitrary source files.

### 2. Metadata and DSL must express meaning

The system must expose node meaning explicitly:

- node identity
- node kind
- runtime
- dependencies
- inputs and outputs
- side effects
- cacheability
- reporting behavior

Meaning must not be hidden in conventions alone.

### 3. Compositionality is mandatory

Nodes must be small, reusable, and composable.

Shared logic must be factored into supporting source files or libraries rather than duplicated across executable node scripts.

Graphs must be built by composition of smaller nodes rather than by monolithic workflows.

### 4. Polyglot execution is allowed, but orchestration is unified

Nodes may be implemented in multiple runtimes, beginning with:

- Java via JBang
- Python via uv

Execution semantics must still be unified through a single graph model and Gradle-based orchestration surface.

### 5. Reproducibility over convenience

Every graph run must be reproducible enough to understand:

- what nodes ran
- in what order
- with what inputs
- against what repository state
- under what runtime/tool versions
- with what outputs and artifacts

### 6. Reporting is first-class

Every node must be able to return structured result data suitable for unified reporting.

Human-readable reports are important, but machine-readable structured reports are required.

### 7. Dry-run and introspection are required

The system must support graph inspection without execution.

Agents and humans must be able to ask:

- what nodes exist
- what a node depends on
- what would run for a target
- what runtimes are required
- what reports would be produced

### 8. Execution engines are adapters, not the model

Gradle, JBang, uv, Selenium, Cucumber, and similar tools are execution or reporting adapters.

They are not the conceptual center of the system.

### 9. Skill ergonomics matter

The system must be easy for an agent to extend safely.

Documentation, starter structure, naming conventions, and SDKs must be designed so an agent can:

- add a node
- compose it into a graph
- reuse shared logic
- expose reporting
- avoid unnecessary duplication

### 10. Local-first iteration

The default operating mode should optimize for fast local iteration and low overhead.

The architecture should support promotion to heavier CI or shared infrastructure later, but the core workflow must remain lightweight enough for frequent experimentation.

## Required Invariants

The following must remain true:

1. Every executable node has a stable node identity.
2. Every node declares its dependencies explicitly.
3. Every node has exactly one declared runtime adapter.
4. Every node can emit a structured result envelope.
5. Every graph can be rendered without executing it.
6. Shared logic can be imported without forcing every node to reimplement common operations.
7. The Gradle DSL must map to the same underlying graph model as imported skill metadata.
8. Reports must unify across runtimes through a common schema.
9. Starter repositories and skill docs must teach composition, not script sprawl.
10. The system must remain understandable to both humans and agents.

## Evolution Policy

This system should evolve by strengthening:

- graph semantics
- SDK ergonomics
- reporting consistency
- reuse of shared logic
- portability of starter projects

It should avoid evolving toward:

- opaque imperative build scripts
- runtime-specific silos
- duplicated validation logic
- hidden dependencies
- unstructured reporting
