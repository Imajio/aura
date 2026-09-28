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

- `aura-app`: a design system for the window, ported from
  `jet-swing-design-system`, a Swing design system in the style of JetBrains'
  IDEs. Every colour is a semantic token - `surface.primary`,
  `text.secondary`, `warning` - read from a dark or a light palette, never a
  literal in a panel; spacing sits on a 4 px grid, controls are 28, 32 or 36 px
  high, and one type scale sets every size. A button at the foot of the
  section rail switches between dark and light at once, repainting every
  section in place, and `theme` in `config.yaml` keeps the choice across
  restarts. Dark is the default, and any value but `dark` or `light` is refused
  at startup with the key and the value named.
- `aura-app`: `JetControls`, the design system's controls as Aura uses them -
  primary and secondary buttons, a text field with a placeholder, list rows
  whose hover is lighter than their selection and whose selection carries a
  stripe as well as a tint, a check box, a spinner and a progress bar. Each
  paints its own face from the tokens, so a theme switch reaches all of it.
- `aura-app`: scrollbars painted from the tokens in both themes, with a 14 px
  hit area round a 6 px thumb, and spinner arrows widened to 24 px.
- `aura-app`: keyboard focus that can be seen on every control - a ring round
  the focused row of the rail, an inner ring on a card's primary button, an
  outline round the `Activity` list. Tab scrolls a section to the control it
  reaches. In `Voice setup`, arming a recording puts focus on `Cancel` rather
  than on the card's primary action, and Escape cancels the arm or the
  count-in before the microphone opens.
- `aura-app`: Ctrl+1 to Ctrl+4 select the window's four sections from anywhere
  in it.
- `aura-app`: the window keeps its size across restarts, as `windowWidth` and
  `windowHeight` in `config.yaml`, fitted to the screen it opens on. It still
  opens in the middle of the screen, on `Status`. On a large window each
  section's column stops widening at a readable width.
- `aura-app`: empty states that say what to do next. With no sidecar, `Status`
  says so and points at the log instead of offering buttons that cannot work.
  An empty project registry names its file and says a restart reads it.
  Recorded takes point at the step that uses them, and a missing speaker model
  is named before the takes, since nothing in the window can produce it. The
  `Tasks` text box shows an example phrase, and an empty `Activity` says how to
  start it.
- `aura-app`: errors said under the control that caused them, in the error
  colour, and scrolled into view. A phrase that could not be dispatched comes
  back into its field with the reason under it. A failed theme save, a log
  folder that will not open and a voice choice that cannot be saved each say
  why under their own button.
- `aura-app`: the Aura logo, on the tray icon and on every window and dialog
  Aura opens, where Java's default icon used to show. The tray shows its state
  as a badge in the logo's bottom right corner: none when ready, violet while
  an agent works or a line is spoken, amber and largest while a permission
  question waits, red after an error.

*None of the design system has met a live sidecar either. Every section was
verified by its own tests and by rendering both themes at this machine's 2.0
display scale, and those renders were checked once against a capture of the
real screen; Aura itself was not started, and key presses were posted in
software rather than typed. What ships is code covered by 391 tests in the five modules
`aura-app` is built from; `aura-hook` and the sidecar, which this work did not
change, are not in that count. Not tested at all: Windows' Text size setting,
the tray icon on a real taskbar, and hover under a real pointer. Known
limitations: Exit from the tray saves no window size unless the window was
closed first, and nothing in the window names Ctrl+1 to Ctrl+4.*

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
- `Reveal names` in `Choose a voice` took clicks across the whole width of its
  card, so a stray click revealed every name and spoilt blind listening. It
  now ends where its label ends.
- `Status` called any takes short of a trained model "not enough to train
  on", with `Enrol from all takes` live beside the same takes in `Voice setup`.
  Both sections now read one rule.
- With no sidecar, `Status` said listening was off because it had not been
  switched on, beside a greyed `Set this up` whose tooltip claimed
  `Voice setup` was not in this build. It now says the sidecar is not running,
  and the buttons follow the sections actually in the window.

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
- The design system is ported without FlatLaf. `jet-swing-design-system`
  itself calls FlatLaf optional, and its tokens are installed as `UIManager`
  keys, which apply just as well to the Windows look and feel Aura already
  uses. FlatLaf would add a dependency of several hundred kilobytes, for
  rendering polish, to a process whose whole promise is to idle cheaply in a
  tray; the design system arrives without a change to any `pom.xml`. HiDPI,
  the usual argument for it, was measured instead: at this machine's 2.0
  display scale, a capture of the real screen matches the window's own
  rendering pixel for pixel, apart from Windows' rounded window corners. If
  HiDPI ever does need FlatLaf, that is an argument to make with measurements,
  not a default.
- The design system is copied into `aura.app.ui`, not depended on. Its
  artifact is unpublished, lives outside this repository, and ships a demo IDE
  shell Aura has no use for. The token layer (`UiTheme`, `Theme`) and the
  controls that earn their place (`JetControls`) are ported under this
  project's names, with their provenance in their javadoc, so the build needs
  nothing the repository does not hold.
- The design system's token layer is adopted, not its IDE shell. Its layout
  describes a header, a toolbar, a left navigation, a dominant editor, a
  bottom tool window and a status bar: an IDE. Aura's window is four
  settings-shaped sections. What transfers is everything else - the tokens,
  the type scale, the density, the geometry, the focus, hover and selection
  rules, the component rules, and the release checklist, which was walked
  line by line. The shell, a command palette and a high-contrast theme are not
  built; the tokens leave room for a third palette.
- In the light palette, `success`, `warning` and `error` are darker than the
  design system's values. It gives one value for both themes: success
  `#4CAF73`, warning `#D9A441`, error `#E35B5B`. On the white card these
  measure 2.73:1, 2.25:1 and 3.55:1, and Aura uses every one of them as the
  colour of 13 px text - a state word, a failure's reason - which needs 4.5:1.
  Warning is the colour of "missing", the word that says what to do next. The
  light palette keeps the hues and darkens them to `#367B51`, `#8D661C` and
  `#D52424`: 5.11:1, 5.19:1 and 5.11:1 on the white card. The dark palette
  keeps the document's values, which read 6.03:1, 7.33:1 and 4.64:1 on the
  dark card, and the token names are unchanged. A design system whose status
  words fail contrast on its own light surface is not implemented by copying
  the failure.
