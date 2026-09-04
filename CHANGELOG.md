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
- `aura-core`: a narration policy — a line is born from a change in the state
  of the work, bounded by a floor so it does not chatter and a ceiling so it
  does not seem to have died — and a three-position chattiness dial.
- `sidecar`: a real speech process. Agent events become one spoken sentence in
  English or Russian, produced by a local model; Russian speech is synthesised
  on the CPU. The model-free stub stays as the sidecar that starts anywhere.
- `aura-app`: the tray shows what Aura is doing, the chattiness dial is in its
  menu, and the log is written to `%LOCALAPPDATA%\Aura\logs` — Aura is started
  from a shortcut, where there is no console to read.
- Benchmarks that answer the open risks with numbers rather than opinion:
  recognition and narration latency per device, the cost of unloading a model,
  and a word-error-rate comparison of recognition models on Russian commands.

### Changed

- The permission dialog names the tool, its arguments and the directory as
  labelled lines, and **No is the default button**: the design says silence is
  a refusal, and the keyboard now agrees with it.
- The tray icon reflects the state of the work. Only "waiting for you" is meant
  to catch the eye.
- Speech recognition runs on integrated graphics, not the NPU: the model
  compiles there and then fails to execute, with every model size tried
  (RISK-1).
- Models are no longer unloaded when idle. Bringing one back costs about six
  seconds against latency budgets of 1.2 s and 900 ms (RISK-9).

### Fixed

- A Codex error item no longer loses its message. The adapter had never seen
  the type, classified it as an unknown tool, and dropped the only field it
  carried.
- The confirmation dialog renders tool arguments as fields instead of raw JSON.

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
- Recognition uses `whisper-large-v3-turbo` rather than a smaller model.
  Whisper tiny is twice as fast and turns the project name — the word routing
  matches on — into something else entirely.
- The sidecar carries torch for Russian speech. Converting Silero to OpenVINO
  IR is not possible: it is one TorchScript system taking strings, with accent
  placement inside the graph.
