package aura.ipc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Test
    void aStalledClientDoesNotStopTheServerFromServingOthers(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();

            // Connect and say nothing at all - the shape that used to wedge the acceptor.
            SocketChannel stalled = SocketChannel.open(StandardProtocolFamily.UNIX);
            stalled.connect(UnixDomainSocketAddress.of(socket));
            try {
                HookResponse response = HookClient.ask(socket, request(), Duration.ofSeconds(5));
                assertThat(response.permissionDecision()).isEqualTo("allow");
            } finally {
                stalled.close();
            }
        }
    }

    @Test
    void serverKeepsServingAfterAHandlerThrows(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        AtomicBoolean firstCall = new AtomicBoolean(true);
        try (HookServer server = new HookServer(socket, req -> {
            if (firstCall.getAndSet(false)) {
                throw new IllegalStateException("handler blew up");
            }
            return new HookResponse("allow", "");
        })) {
            server.start();
            assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                .isEqualTo("deny");
            assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                .isEqualTo("allow");
        }
    }

    @Test
    void aStaleSocketFileDoesNotPreventBinding(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        Files.writeString(socket, "left over from a crash");
        assertThat(Files.exists(socket)).isTrue();

        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();
            assertThat(HookClient.ask(socket, request(), Duration.ofSeconds(5)).permissionDecision())
                .isEqualTo("allow");
        }
    }

    @Test
    void malformedRequestIsAnsweredAsSuchRatherThanAsAnInternalError(@TempDir Path tmp) throws Exception {
        Path socket = tmp.resolve("aura.sock");
        try (HookServer server = new HookServer(socket, req -> new HookResponse("allow", ""))) {
            server.start();

            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(socket));
                var writer = new BufferedWriter(new OutputStreamWriter(
                    Channels.newOutputStream(client), StandardCharsets.UTF_8));
                writer.write("this is not json\n");
                writer.flush();

                var reader = new BufferedReader(new InputStreamReader(
                    Channels.newInputStream(client), StandardCharsets.UTF_8));
                String reply = reader.readLine();
                assertThat(reply).contains("\"deny\"").contains("malformed");
            }
        }
    }
}
