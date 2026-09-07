package aura.app;

import aura.agents.ClaudeSession;
import aura.agents.CodexSession;
import aura.agents.SessionSupervisor;
import aura.core.AgentEvent;
import aura.core.NarrationPolicy;
import aura.core.Verbosity;
import aura.ipc.SpeechClient;
import aura.core.Project;
import aura.core.ProjectRegistry;
import aura.ipc.HookResponse;
import aura.ipc.HookServer;
import aura.policy.ConfirmationProvider;
import aura.policy.Decision;
import aura.policy.PermissionPolicy;
import aura.policy.ToolRequest;
import aura.policy.TrayConfirmationProvider;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: brings up the hook server, the supervisor, and the tray, and wires them together. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    // Aura normally starts from a shortcut with nobody watching it. A plain modal
    // dialog blocks the calling thread until someone clicks OK, so an unattended
    // launch would never reach System.exit and would sit forever looking alive.
    // Bounding the dialog keeps it useful for whoever is at the keyboard while
    // guaranteeing the process still terminates on its own.
    private static final int STARTUP_DIALOG_TIMEOUT_MS = 30_000;

    public static void main(String[] args) {
        Path configFile = Path.of(System.getenv().getOrDefault("APPDATA",
            System.getProperty("user.home")), "Aura", "config.yaml");
        try {
            AuraConfig config = AuraConfig.load(configFile);
            ProjectRegistry registry = ProjectRegistryLoader.load(config.projectsFile());
            log.info("projects in registry: {}", registry.all().size());

            TrayApp[] tray = new TrayApp[1];

            SessionSupervisor supervisor = new SessionSupervisor((sessionConfig, sink) -> {
                boolean codex = sessionConfig.command().stream().anyMatch("exec"::equals);
                if (codex) {
                    return CodexSession.start(sessionConfig, sink);
                }
                // An agent that dies on startup must say so where the user is
                // looking. Without this the task simply does nothing, which is
                // indistinguishable from an agent that is thinking.
                return ClaudeSession.start(sessionConfig, sink, complaint -> {
                    if (tray[0] != null) {
                        tray[0].alert("The agent stopped: " + complaint);
                        tray[0].state(TrayIconArt.State.ERROR);
                    }
                });
            }, config.idleTimeout(), Clock.systemUTC());

            ScheduledExecutorService idleSweeper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aura-idle-sweeper");
                t.setDaemon(true);
                return t;
            });
            // Sweep several times per timeout so a session is closed near its deadline
            // rather than up to a whole timeout late.
            long sweepSeconds = Math.max(30, config.idleTimeout().toSeconds() / 4);
            idleSweeper.scheduleWithFixedDelay(() -> {
                try {
                    int closed = supervisor.closeIdle();
                    if (closed > 0) {
                        log.info("closed {} idle session(s)", closed);
                    }
                } catch (Exception e) {
                    log.warn("idle sweep failed", e);
                }
            }, sweepSeconds, sweepSeconds, TimeUnit.SECONDS);

            ConfirmationProvider confirmation =
                ConfirmationProvider.guarded(new TrayConfirmationProvider());

            // A hook jar that is not there does not fail loudly: the generated command
            // simply never answers, and tool calls stop being gated while everything
            // still looks normal. Better to refuse to start than to run unguarded.
            if (!Files.isRegularFile(config.hookJar())) {
                throw new IllegalStateException(
                    "hook jar not found at " + config.hookJar()
                        + " — build it with `mvn -q clean package` or set hookJar in "
                        + configFile);
            }

            // javaExe is the other half of the same generated hook command: if it does
            // not resolve either, bash reports command-not-found for the hook, the hook
            // never answers, and the CLI silently falls back to its own permission flow
            // instead of Aura's. Same hazard as a missing hook jar, same fix.
            if (!Files.isRegularFile(config.javaExe())) {
                throw new IllegalStateException(
                    "java executable not found at " + config.javaExe()
                        + " — set javaExe in " + configFile);
            }

            HookServer hookServer = new HookServer(
                config.socketPath(),
                request -> {
                    // The longest match wins, not the first one: if the registry has both
                    // C:\work and C:\work\backend, the policy to apply is backend's.
                    Path cwd = Path.of(request.cwd()).normalize();
                    Optional<Project> project = registry.all().stream()
                        .filter(p -> cwd.startsWith(p.path().normalize()))
                        .max(java.util.Comparator.comparingInt(
                            p -> p.path().normalize().toString().length()));
                    if (project.isEmpty()) {
                        return HookResponse.deny("directory outside Aura's project registry");
                    }
                    ToolRequest toolRequest = new ToolRequest(
                        request.toolName(), request.toolInputJson(), Path.of(request.cwd()));
                    Decision decision = new PermissionPolicy(project.get()).decide(toolRequest);
                    if (decision == Decision.ALLOW) {
                        return new HookResponse("allow", "allowed by project policy");
                    }
                    if (decision == Decision.DENY) {
                        return HookResponse.deny("denied by project policy");
                    }
                    Decision answer = confirmation.confirm(toolRequest, config.confirmTimeout());
                    return answer == Decision.ALLOW
                        ? new HookResponse("allow", "confirmed by the user")
                        : HookResponse.deny("user did not confirm");
                });
            hookServer.start();

            // The narration that closes a task arrives a moment after the DONE event
            // itself, so the event sets a flag the narration handler consumes.
            java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean();

            // The sidecar is optional on purpose. Without it the application still
            // dispatches tasks, gates tool calls and shows progress in the tray —
            // it simply does not speak. Refusing to start because a voice is
            // missing would trade the whole product for one of its features.
            java.util.function.Consumer<String>[] spoken = new java.util.function.Consumer[1];
            // NO_SPEAKER_REFERENCE is the sidecar's first event: emitted before serve()
            // and so ahead of `ready`, while this thread is still inside
            // `new TrayApp(...)` and the AWT/SystemTray initialisation it performs.
            // Dropping the alert because the tray is not up yet loses the one notice
            // that says every voice in the room is being accepted as the owner, and
            // the manual test plan asks the owner to see it. Held here instead, and
            // drained the moment there is a tray to show it on.
            java.util.concurrent.atomic.AtomicReference<String> heldAlert =
                new java.util.concurrent.atomic.AtomicReference<>();
            SpeechClient speech = startSidecar(config, tray, finished, heldAlert,
                phrase -> {
                    if (spoken[0] != null) {
                        spoken[0].accept(phrase);
                    }
                });
            NarrationPolicy narrationPolicy = new NarrationPolicy(Verbosity.NORMAL, Instant::now);
            NarrationBridge narration = speech == null ? null
                : new NarrationBridge(narrationPolicy, speech::send,
                    config.profile(), !config.voice().isBlank());

            java.util.function.Consumer<AgentEvent> sink = event -> {
                log.info("[{}] {} {} {}", event.agent(), event.kind(), event.toolClass(), event.target());
                if (event.kind() == aura.core.EventKind.DONE) {
                    finished.set(true);
                    // A task that failed must not look like one that succeeded. The
                    // agent reports the reason in the final result and the parser
                    // already carries it here; without this the log line above says
                    // "DONE OTHER" either way, and the only trace of the failure is
                    // a narrated sentence that paraphrases it into something else.
                    if (Boolean.FALSE.equals(event.ok())) {
                        String why = event.summaryHint().isBlank()
                            ? "the agent gave no reason" : event.summaryHint();
                        log.warn("agent task failed: {}", why);
                        if (tray[0] != null) {
                            tray[0].alert("Task failed: " + why);
                        }
                    }
                }
                if (tray[0] != null) {
                    tray[0].status(event.kind() + " " + event.target());
                    TrayIconArt.State state = TrayIconArt.stateFor(event.kind());
                    if (state != null) {
                        tray[0].state(state);
                    }
                }
                if (narration != null) {
                    narration.accept(event);
                }
            };

            if (narration != null) {
                // The ceiling is measured in seconds, so the check has to be finer
                // than the ceiling itself or a silence outlives its own limit.
                idleSweeper.scheduleWithFixedDelay(() -> {
                    try {
                        narration.heartbeat();
                    } catch (Exception e) {
                        log.warn("narration heartbeat failed", e);
                    }
                }, 5, 5, TimeUnit.SECONDS);
            }

            TaskDispatcher dispatcher = new TaskDispatcher(registry, supervisor, config, sink);

            Path logDir = Path.of(System.getenv().getOrDefault("LOCALAPPDATA",
                System.getProperty("user.home")), "Aura", "logs");

            // One path for a task, whether it was typed or spoken. A phrase that
            // arrived through the microphone is not a different kind of request,
            // and giving it its own handler is how the two drift apart.
            java.util.function.Consumer<String> dispatch = phrase -> {
                try {
                    var result = dispatcher.dispatch(phrase);
                    if (result instanceof TaskDispatcher.ProjectUnknown) {
                        tray[0].alert("Could not tell which project. Name the project in the phrase.");
                    } else if (result instanceof TaskDispatcher.Sent sent) {
                        tray[0].message("Sent to project " + sent.projectName());
                    }
                } catch (IllegalStateException e) {
                    // The commonest first cause is claude/codex not resolving on the
                    // GUI process's PATH, since the default is the bare binary name.
                    // Without this, the exception reaches the event thread's default
                    // handler and prints a stack trace to a console that does not
                    // exist when the app is launched from a shortcut — total silence.
                    log.warn("task dispatch failed", e);
                    tray[0].alert("Could not start the task: " + e.getMessage());
                }
            };
            spoken[0] = dispatch;

            tray[0] = new TrayApp(
                dispatch,
                supervisor::close,
                () -> {
                    idleSweeper.shutdownNow();
                    supervisor.close();
                    hookServer.close();
                    closeQuietly(speech);
                    tray[0].remove();
                    System.exit(0);
                },
                level -> {
                    narrationPolicy.verbosity(level);
                    // The sidecar chooses its model from this: the quiet level
                    // narrates with the smaller one, as the model registry says.
                    configure(speech, config.profile(), level);
                    log.info("narration level set to {}", level);
                },
                narrationPolicy.verbosity(),
                logDir);

            // Drained the instant there is a tray to drain it onto — see heldAlert
            // where it is declared for why the alert can arrive before this line.
            String heldNotice = heldAlert.getAndSet(null);
            if (heldNotice != null) {
                tray[0].alert(heldNotice);
            }

            configure(speech, config.profile(), narrationPolicy.verbosity());
            tray[0].status("ready");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                idleSweeper.shutdownNow();
                supervisor.close();
                hookServer.close();
                closeQuietly(speech);
                if (tray[0] != null) {
                    tray[0].remove();
                }
            }));
            log.info("Aura is running, hook socket: {}", hookServer.socketPath());
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("Aura could not start", e);
            if (!GraphicsEnvironment.isHeadless()) {
                showStartupFailureDialog(e.getMessage());
            }
            System.exit(1);
        }
    }

    /**
     * Shows the failure dialog without letting it block forever. The dialog itself
     * disposes after {@value #STARTUP_DIALOG_TIMEOUT_MS} ms if nobody dismisses it,
     * which unblocks {@code setVisible} below and lets startup fail through to the
     * exit code either way.
     */
    /** Tells the sidecar which language to narrate in and which model to use. */
    private static void configure(SpeechClient speech, String profile, Verbosity level) {
        if (speech == null) {
            return;
        }
        try {
            speech.send(java.util.Map.of(
                "id", "configure", "cmd", "configure",
                "profile", profile,
                "verbosity", level.name().toLowerCase(java.util.Locale.ROOT)));
        } catch (Exception e) {
            log.warn("could not configure the sidecar", e);
        }
    }

    /**
     * Starts the speech sidecar, or returns null when it cannot be started.
     *
     * <p>Null rather than an exception: a missing interpreter, a missing sidecar or a
     * Python that dies on import are all reasons to run without a voice, not reasons
     * to deny the user an application that otherwise works. The reason is logged and
     * shown once in the tray, so the silence is explained rather than mysterious.
     */
    private static SpeechClient startSidecar(AuraConfig config, TrayApp[] tray,
                                             java.util.concurrent.atomic.AtomicBoolean finished,
                                             java.util.concurrent.atomic.AtomicReference<String>
                                                 heldAlert,
                                             java.util.function.Consumer<String> onSpoken) {
        if (!Files.isDirectory(config.sidecarDir())) {
            log.warn("no sidecar at {} — running without a voice", config.sidecarDir());
            return null;
        }
        SidecarEvents events = new SidecarEvents();
        // Main is the first subscriber, with exactly the behaviour this used to be
        // an inline if-chain for. A later reader (the desktop window) subscribes
        // separately instead of copying this chain or reaching into Main.
        events.subscribe(event -> {
            String kind = event.kind();
            if ("wake".equals(kind)) {
                log.info("wake word");
                if (tray[0] != null) {
                    tray[0].status("listening");
                }
            } else if ("rejected".equals(kind)) {
                // Deliberately quiet. Somebody spoke and was not the owner —
                // which is the feature working, not a fault, and a balloon
                // for every refusal would make the room's conversation the
                // application's business.
                log.info("voice not recognised as the owner");
            } else if ("utterance".equals(kind)) {
                // A task said out loud. From here it is indistinguishable from
                // one typed into the tray, which is the point of the whole
                // milestone: the voice is another way in, not another product.
                String text = event.text("text");
                log.info("heard: {}", text);
                onSpoken.accept(text);
            } else if ("speak.started".equals(kind)) {
                if (tray[0] != null) {
                    tray[0].state(TrayIconArt.State.SPEAKING);
                }
            } else if ("narration".equals(kind)) {
                // What the user would have heard. Shown even when no voice is
                // configured, so the narrator can be judged before it is audible.
                String text = event.text("text");
                log.info("narration: {}", text);
                if (tray[0] != null) {
                    // Only the line that closes the task interrupts. Windows
                    // coalesces balloons that arrive close together, so raising
                    // one per step loses most of them and teaches the user to
                    // dismiss the rest unread — the habit that must be absent
                    // when a permission question finally arrives.
                    if (finished.compareAndSet(true, false)) {
                        tray[0].alert(text);
                    } else {
                        tray[0].message(text);
                    }
                }
            } else if ("error".equals(kind)) {
                String code = event.text("code");
                log.warn("sidecar error {}: {}", code, event.text("detail"));
                // The state this reports — a trained wake word, no enrolled voice,
                // so every voice in the room is accepted as the owner — is one the
                // owner has to know they are in. A log file nobody is watching is
                // not how this application tells somebody something that matters.
                if ("NO_SPEAKER_REFERENCE".equals(code)) {
                    String notice = "No enrolled voice: every voice is accepted as "
                        + "the owner until enrol-speaker.py is run.";
                    if (tray[0] != null) {
                        tray[0].alert(notice);
                    } else {
                        // Held, not dropped. This event wins the race against the
                        // tray far more often than not; main() drains it as soon
                        // as there is one.
                        heldAlert.set(notice);
                    }
                }
            } else {
                log.info("sidecar: {}", event.body());
            }
        });
        try {
            // SpeechClient already parses each line into a JsonNode before this
            // callback runs. SidecarEvents' contract takes the raw line instead, so
            // every subscriber — Main today, the window later — shares one parse
            // and one place where the protocol is understood; the node is
            // serialised back to text here rather than teaching SpeechClient a
            // second, competing notion of "line".
            return SpeechClient.start(config.sidecarCommand(), config.sidecarDir(),
                parsed -> events.onLine(parsed.toString()));
        } catch (Exception e) {
            log.warn("speech sidecar did not start — running without a voice", e);
            return null;
        }
    }

    private static void closeQuietly(SpeechClient speech) {
        if (speech == null) {
            return;
        }
        try {
            speech.send(java.util.Map.of("id", "shutdown", "cmd", "shutdown"));
            speech.close();
        } catch (Exception e) {
            log.debug("sidecar did not close cleanly", e);
        }
    }

    private static void showStartupFailureDialog(String message) {
        try {
            JOptionPane pane = new JOptionPane(
                "Aura could not start:\n\n" + message, JOptionPane.ERROR_MESSAGE);
            JDialog dialog = pane.createDialog("Aura");
            Timer timeout = new Timer(STARTUP_DIALOG_TIMEOUT_MS, e -> dialog.dispose());
            timeout.setRepeats(false);
            timeout.start();
            dialog.setVisible(true);
            timeout.stop();
        } catch (RuntimeException e) {
            // Best-effort notice only: startup still fails via the exit code below
            // regardless of whether the dialog itself could be shown.
            log.warn("could not show the startup failure dialog", e);
        }
    }

    private Main() {
    }
}
