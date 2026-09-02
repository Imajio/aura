# Changelog

Format — [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versions — [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Project documents: PRD, architecture design, four ADRs, risk register (now
  kept alongside the repository, not inside it).
- Implementation plan for milestone M1.
- `aura-core`: the canonical `AgentEvent` model, tool classification, and a
  project registry resolved by name or by spoken phrase.
- `aura-agents`: adapters that normalize `claude stream-json` and
  `codex exec --json` into `AgentEvent`, a test-outcome detector, and a
  supervisor that holds one long-lived agent session per project with an idle
  timeout.
- `aura-policy`: a permission policy that classifies each tool call as allow,
  confirm, or deny, and a confirmation gate that defaults to deny.
- `aura-ipc` and `aura-hook`: a `PreToolUse` hook that asks the running
  application over a local AF_UNIX socket before every tool call, and denies
  the call when the application is unreachable.
- `aura-app`: the tray application itself — typed task entry, dispatch of a
  task to the right project's session, and the generated agent settings that
  wire the hook in.
- Event-stream fixtures captured from live `claude` and `codex` processes.

### Decisions

- Execution device is assigned per stage, not per application: static shapes
  on the NPU, autoregressive decoding on integrated graphics (ADR 0001).
- Narration is triggered by a change of work state, not a timer, and is
  governed by a three-position chattiness setting (ADR 0002).
- Dangerous calls go through the `PreToolUse` hook with a default deny
  (ADR 0003).
- An agent session is a long-lived process per project (ADR 0004).
- The channel between the hook and the application is an AF_UNIX socket, not
  a named pipe: JDK 21 on Windows 11 opens one without native dependencies.
