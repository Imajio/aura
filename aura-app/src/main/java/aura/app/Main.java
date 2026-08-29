package aura.app;

import aura.agents.ClaudeSession;
import aura.agents.CodexSession;
import aura.agents.SessionSupervisor;
import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import aura.ipc.HookResponse;
import aura.ipc.HookServer;
import aura.policy.ConfirmationProvider;
import aura.policy.Decision;
import aura.policy.PermissionPolicy;
import aura.policy.ToolRequest;
import aura.policy.TrayConfirmationProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point: brings up the hook server, the supervisor, and the tray, and wires them together. */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Path configFile = Path.of(System.getenv().getOrDefault("APPDATA",
            System.getProperty("user.home")), "Aura", "config.yaml");
        AuraConfig config = AuraConfig.load(configFile);
        ProjectRegistry registry = ProjectRegistryLoader.load(config.projectsFile());
        log.info("projects in registry: {}", registry.all().size());

        SessionSupervisor supervisor = new SessionSupervisor((sessionConfig, sink) -> {
            boolean codex = sessionConfig.command().stream().anyMatch("exec"::equals);
            return codex
                ? CodexSession.start(sessionConfig, sink)
                : ClaudeSession.start(sessionConfig, sink);
        }, config.idleTimeout(), Clock.systemUTC());

        ConfirmationProvider confirmation =
            ConfirmationProvider.guarded(new TrayConfirmationProvider());

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
        java.util.function.Consumer<AgentEvent> sink = event -> {
            log.info("[{}] {} {} {}", event.agent(), event.kind(), event.toolClass(), event.target());
            if (tray[0] != null) {
                tray[0].status(event.kind() + " " + event.target());
            }
        };

        TaskDispatcher dispatcher = new TaskDispatcher(registry, supervisor, config, sink);

        tray[0] = new TrayApp(
            phrase -> {
                var result = dispatcher.dispatch(phrase);
                if (result instanceof TaskDispatcher.ProjectUnknown) {
                    tray[0].notice("Could not tell which project. Name the project in the phrase.");
                } else if (result instanceof TaskDispatcher.Sent sent) {
                    tray[0].notice("Sent to project " + sent.projectName());
                }
            },
            supervisor::close,
            () -> {
                supervisor.close();
                hookServer.close();
                tray[0].remove();
                System.exit(0);
            });

        tray[0].status("ready");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            supervisor.close();
            hookServer.close();
        }));
        log.info("Aura is running, hook socket: {}", hookServer.socketPath());
        Thread.currentThread().join();
    }

    private Main() {
    }
}
