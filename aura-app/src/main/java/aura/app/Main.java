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

            SessionSupervisor supervisor = new SessionSupervisor((sessionConfig, sink) -> {
                boolean codex = sessionConfig.command().stream().anyMatch("exec"::equals);
                return codex
                    ? CodexSession.start(sessionConfig, sink)
                    : ClaudeSession.start(sessionConfig, sink);
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

            TrayApp[] tray = new TrayApp[1];

            // The sidecar is optional on purpose. Without it the application still
            // dispatches tasks, gates tool calls and shows progress in the tray —
            // it simply does not speak. Refusing to start because a voice is
            // missing would trade the whole product for one of its features.
            SpeechClient speech = startSidecar(config, tray);
            NarrationPolicy narrationPolicy = new NarrationPolicy(Verbosity.NORMAL, Instant::now);
            NarrationBridge narration = speech == null ? null
                : new NarrationBridge(narrationPolicy, speech::send, "ru");

            java.util.function.Consumer<AgentEvent> sink = event -> {
                log.info("[{}] {} {} {}", event.agent(), event.kind(), event.toolClass(), event.target());
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

            tray[0] = new TrayApp(
                phrase -> {
                    try {
                        var result = dispatcher.dispatch(phrase);
                        if (result instanceof TaskDispatcher.ProjectUnknown) {
                            tray[0].notice("Could not tell which project. Name the project in the phrase.");
                        } else if (result instanceof TaskDispatcher.Sent sent) {
                            tray[0].notice("Sent to project " + sent.projectName());
                        }
                    } catch (IllegalStateException e) {
                        // The commonest first cause is claude/codex not resolving on the
                        // GUI process's PATH, since the default is the bare binary name.
                        // Without this, the exception reaches the event thread's default
                        // handler and prints a stack trace to a console that does not
                        // exist when the app is launched from a shortcut — total silence.
                        log.warn("task dispatch failed", e);
                        tray[0].notice("Could not start the task: " + e.getMessage());
                    }
                },
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
                    log.info("narration level set to {}", level);
                },
                narrationPolicy.verbosity(),
                logDir);

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
    /**
     * Starts the speech sidecar, or returns null when it cannot be started.
     *
     * <p>Null rather than an exception: a missing interpreter, a missing sidecar or a
     * Python that dies on import are all reasons to run without a voice, not reasons
     * to deny the user an application that otherwise works. The reason is logged and
     * shown once in the tray, so the silence is explained rather than mysterious.
     */
    private static SpeechClient startSidecar(AuraConfig config, TrayApp[] tray) {
        if (!Files.isDirectory(config.sidecarDir())) {
            log.warn("no sidecar at {} — running without a voice", config.sidecarDir());
            return null;
        }
        try {
            return SpeechClient.start(config.sidecarCommand(), config.sidecarDir(), event -> {
                String kind = event.path("ev").asText();
                if ("speak.started".equals(kind)) {
                    if (tray[0] != null) {
                        tray[0].state(TrayIconArt.State.SPEAKING);
                    }
                } else if ("narration".equals(kind)) {
                    // What the user would have heard. Shown even when no voice is
                    // configured, so the narrator can be judged before it is audible.
                    String text = event.path("text").asText();
                    log.info("narration: {}", text);
                    if (tray[0] != null) {
                        tray[0].notice(text);
                    }
                } else if ("error".equals(kind)) {
                    log.warn("sidecar error {}: {}",
                        event.path("code").asText(), event.path("detail").asText());
                } else {
                    log.info("sidecar: {}", event);
                }
            });
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
