# Changelog

Format - [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versions - [Semantic Versioning](https://semver.org/).

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
- `aura-app`: the tray application itself - typed task entry, dispatch of a
  task to the right project's session, and the generated agent settings that
  wire the hook in.
- Event-stream fixtures captured from live `claude` and `codex` processes.
- `aura-core`: a narration policy - a line is born from a change in the state
  of the work, bounded by a floor so it does not chatter and a ceiling so it
  does not seem to have died - and a three-position chattiness dial.
- `sidecar`: a real speech process. Agent events become one spoken sentence in
  English or Russian, produced by a local model; Russian speech is synthesised
  on the CPU. The model-free stub stays as the sidecar that starts anywhere.
- `aura-app`: the tray shows what Aura is doing, the chattiness dial is in its
  menu, and the log is written to `%LOCALAPPDATA%\Aura\logs` - Aura is started
  from a shortcut, where there is no console to read.
- Benchmarks that answer the open risks with numbers rather than opinion:
  recognition and narration latency per device, the cost of unloading a model,
  and a word-error-rate comparison of recognition models on Russian commands.
- `sidecar`: the rest of the listening cascade. A wake word trained on the
  owner's own takes arms the listener; speaker verification then refuses a voice
  that is not theirs before it reaches recognition, so a stranger's words never
  travel through a model or into a log. A spoken task reaches the agent by the
  same path a typed one does.
- `sidecar`: the two scripts the owner runs once. `train-wake-word.py` fits a
  classifier on their recordings and reports both how many of their takes it
  recognised and how often it fired on audio that was not the wake word;
  `enrol-speaker.py` averages reference takes into the one embedding every later
  utterance is compared against.
- `aura-app`: `listen` in the configuration, off by default, and the events the
  cascade produces - the wake word, a recognised utterance, and a refused voice,
  which is logged and deliberately not announced.

*None of the voice path has been exercised end to end: it needs a wake-word
model and a voice reference, and both are recordings only the owner can make.
What ships is code covered by 210 tests on the Java side and 102 on the sidecar.*

- `aura-app`: a desktop window, opened from the tray alongside it rather than
  in place of it. Sections live in a rail on the left. `Status` answers,
  without being asked, whether the sidecar is up, what it can do, what voice
  setup is missing, and where the log is.
- `aura-app`: `Voice setup`, turning the sidecar's record, enrol and train
  commands into buttons - a spinner and a count-in before the microphone ever
  opens, a live level per take while it records, and the two numbers a
  trained wake word is judged by. The same section switches listening on and
  off.
- `aura-app`: `Choose a voice`, a blind audition of the hundred pre-rendered
  Russian narrator samples from the M2 benchmarks - by voice, then by line,
  names hidden until asked for - ending in one button that writes the choice
  to `config.yaml`.
- `aura-app`: `Tasks`, the one thing the tray could not do. A text box takes a
  phrase the same way the tray's dialog does, a card lists the projects the
  registry knows with their aliases, and a transcript shows what happens next
  as it happens: which project the phrase routed to, every tool the agent
  runs with its class and target, the narration lines, and how the task
  ended - capped at the last 500 rows so a day-long run does not grow it
  without bound.

*None of the window has been exercised end to end either: every section above
was verified by its own tests and by rendering its states to an image, never
by running Aura itself against a live sidecar. What ships is code covered by
282 tests on the Java side and 144 on the sidecar.*

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
- A command sent to the speech sidecar while another was already in flight
  could interleave into one unparseable line, losing both.
- Sending a task from the window froze it for as long as the agent process
  took to start; the send now runs in the background and the button shows it
  is working instead.
- An unrelated microphone or recognition hiccup arriving while enrolment or
  wake-word training was running could paint that still-running command as
  failed.

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
  Whisper tiny is twice as fast and turns the project name - the word routing
  matches on - into something else entirely.
- The sidecar carries torch for Russian speech. Converting Silero to OpenVINO
  IR is not possible: it is one TorchScript system taking strings, with accent
  placement inside the graph.
- The wake word is trained here rather than through openWakeWord's own pipeline.
  That package pulls scipy and scikit-learn, about 150 MB, into a process meant
  to sit idle all day; only its two ONNX feature files are used. The trainer and
  the live detector call one shared `wake_features`, so a classifier is always
  scored on exactly the numbers it was trained on.
- Speaker verification fails closed. A verifier that throws, a reference that
  will not load, a model that went away - each is a refusal, not a warning. The
  stage exists to keep a stranger from reaching an agent, so it may not resolve
  any other way.
- Listening is asked for explicitly and never inferred from a file being on
  disk. Asking to listen without a trained wake word reaches the sidecar and
  earns its `NO_WAKE_WORD` refusal out loud, instead of being dropped quietly on
  the Java side; a wake word with no enrolled voice raises a balloon saying every
  voice is currently accepted.
- The desktop window is Swing and AWT, not JavaFX or a local web view. Both
  ship with the JDK Aura already requires; JavaFX would add tens of megabytes
  of platform natives to a single-jar launch, and a web view would add a
  server and a browser to a process whose whole promise is to idle cheaply.
- The window never opens a microphone. The sidecar owns capture and is the
  only thing that may; the window sends a command and renders what comes
  back. The one device it touches directly is the default output, to play an
  audition sample the owner asked to hear.
- Recording adds takes instead of replacing them. The owner's voice is the one
  artefact on this machine that cannot be regenerated, so a second recording
  session numbers on from what is already on disk rather than starting over
  it.
