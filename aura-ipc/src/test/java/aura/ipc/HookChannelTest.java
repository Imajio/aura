package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookChannelTest {

    private static HookRequest request() {
        return new HookRequest("s1", "Bash", "{\"command\":\"rm -rf build\"}", "C:\\work\\backend");
    }

    @Test
    void requestReachesHandlerAndVerdictComesBack(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        AtomicReference<HookRequest> seen = new AtomicReference<>();

        try (HookServer server = new HookServer(socket, req -> {
            seen.set(req);
            return new HookResponse("deny", "dangerous command");
        })) {
            server.start();

            HookResponse response = HookClient.ask(socket, request(), Duration.ofSeconds(5));

            assertThat(response.permissionDecision()).isEqualTo("deny");
            assertThat(response.reason()).isEqualTo("dangerous command");
            assertThat(seen.get().toolName()).isEqualTo("Bash");
            assertThat(seen.get().toolInputJson()).contains("rm -rf build");
            assertThat(seen.get().cwd()).isEqualTo("C:\\work\\backend");
        }
    }

    @Test
    void severalHooksAreServedOneAfterAnother(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();
            for (int i = 0; i < 3; i++) {
                assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                    .isEqualTo("allow");
            }
        }
    }

    @Test
    void handlerFailureBecomesDenyRatherThanHang(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> {
            throw new IllegalStateException("handler crashed");
        })) {
            server.start();
            assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                .isEqualTo("deny");
        }
    }

    @Test
    void missingServerYieldsDenyWithoutThrowing(@TempDir Path tmp) {
        HookResponse response =
            HookClient.ask(tmp.resolve("no-server.sock"), request(), Duration.ofSeconds(1));
        assertThat(response.permissionDecision()).isEqualTo("deny");
    }

    @Test
    void socketFileIsRemovedOnClose(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();
            assertThat(Files.exists(socket)).isTrue();
        }
        assertThat(Files.exists(socket)).isFalse();
    }
}
