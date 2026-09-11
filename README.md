# Aura

Aura is a Windows tray application and desktop window for Claude Code and
Codex. It takes a typed task, hands it to the coding-agent CLI, normalises
the CLI's event stream into one canonical form, and refuses a dangerous tool
call unless a human confirms it.

**Status:** M1 is complete and accepted (2026-09-04) - tray task entry, a
long-lived agent session per project, event normalisation for both CLIs, and
tool-call permission enforcement through a `PreToolUse` hook, verified against
the live Claude Code CLI. Voice (wake word, recognition, narration, speaker
verification) is not part of M1; it arrived in a later milestone, alongside
the desktop window the tray now opens. A typed phrase reaches Aura from the
tray menu or from the window's `Tasks` section, which is also where a
dispatched task can be watched running.

## Design documents

The requirements, architecture, decision records and risk register are **not
in this repository**. They live in a `docs/` directory next to the
repository, not inside it, and are not published with the code - ask the
project owner for them if you need them.

| Document | About |
|---|---|
| `product-requirements.md` | why this exists, for whom, scope, non-functional requirements, acceptance criteria |
| `architecture.md` | components, protocols, models, failure modes, tests, build order |
| `risk-register.md` | open risks and the experiments that close them |
| `adr/0001-execution-device-per-stage.md` | which stage runs on which device |
| `adr/0002-narration-trigger-policy.md` | when the application opens its mouth |
| `adr/0003-tool-permissions-and-voice-confirmation.md` | permissions and voice confirmation |
| `adr/0004-agent-session-topology.md` | how an agent session is held |
| `plans/m1-skeleton-and-events.md` | this milestone, step by step |
| `m1-manual-acceptance.md` | the seven steps only a human at the screen can run |
| `owner-experiments.md` | the four measurements that need a person, speakers or a battery |
| `interface-design.md` | the mark, the five states it shows, and the one dialog that matters |
| `manual-test-plan.md` | every check that needs a person at the keyboard |
| [CONTRIBUTING.md](CONTRIBUTING.md) | branches, commits, tests, what not to commit |

## Target platform

Windows 11, Intel Core Ultra with an NPU, integrated Arc graphics. Verified on
a Core Ultra 9 285H: NPU architecture 3720, Arc 140T, OpenVINO 2026.3.

## Layout

```
pom.xml         parent POM, Java 21, Maven multi-module
aura-core/      canonical domain model: AgentEvent, ToolClass, EventKind, Project
aura-agents/    turns each CLI's event stream into AgentEvent; supervises sessions
aura-policy/    classifies a tool call as allow, confirm, or deny
aura-ipc/       the channel between the PreToolUse hook and the running app
aura-hook/      the hook process Claude Code / Codex invoke before a tool call
aura-app/       entry point: tray icon, task dispatch, permission wiring
sidecar/        Python, OpenVINO - speech, arriving in a later milestone
testdata/       event streams and audio fixtures captured from live processes
```

## Build and run

```bash
mvn clean package        # everything, including tests
mvn -q -pl aura-core test
java -jar aura-app/target/aura-app.jar
```

Work on the code follows the rules in [CONTRIBUTING.md](CONTRIBUTING.md).
