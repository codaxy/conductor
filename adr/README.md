# Architecture Decision Records

This directory records significant design decisions made in this fork of Conductor, one
decision per file, using the format from Michael Nygard's
[Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

Files are named `NNNN-short-title.md` with a sequential number. A record is not rewritten
once accepted; if a decision changes, add a new record that supersedes it and update the old
record's status.

Each record has these sections:

- **Status**: Proposed, Accepted, Deprecated, or Superseded by ADR-NNNN.
- **Context**: the problem and the forces at play.
- **Decision**: what was decided.
- **Alternatives considered**: options rejected, and why.
- **Consequences**: what becomes easier or harder as a result.

## Index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-kafka-status-producer-security.md) | Secure connections for the Kafka status-event producer | Accepted |
