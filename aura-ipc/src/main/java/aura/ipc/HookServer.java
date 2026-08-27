package aura.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An AF_UNIX server that accepts requests from hook processes.
 *
 * <p>The protocol is as simple as it gets: one line of JSON request, one line of JSON
 * response, then the connection closes. The hook is a short-lived process; there is
 * nothing to keep a session for.
 */
public final class HookServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HookServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path socketPath;
    private final Function<HookRequest, HookResponse> handler;
    private ServerSocketChannel channel;
    private Thread acceptor;
    private volatile boolean running;

    public HookServer(Path socketPath, Function<HookRequest, HookResponse> handler) {
        this.socketPath = socketPath;
        this.handler = handler;
    }

    public Path socketPath() {
        return socketPath;
    }

    public void start() throws Exception {
        Files.createDirectories(socketPath.getParent());
        Files.deleteIfExists(socketPath);

        channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socketPath));
        running = true;

        acceptor = new Thread(this::acceptLoop, "aura-hook-server");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("hook server listening on {}", socketPath);
    }

    private void acceptLoop() {
        while (running) {
            try (SocketChannel connection = channel.accept()) {
                serve(connection);
            } catch (Exception e) {
                if (running) {
                    log.debug("hook connection dropped: {}", e.toString());
                }
            }
        }
    }

    private void serve(SocketChannel connection) throws Exception {
        var reader = new BufferedReader(
            new InputStreamReader(Channels.newInputStream(connection), StandardCharsets.UTF_8));
        var writer = new BufferedWriter(
            new OutputStreamWriter(Channels.newOutputStream(connection), StandardCharsets.UTF_8));

        String line = reader.readLine();
        if (line == null) {
            return;
        }

        HookResponse response;
        try {
            HookRequest request = MAPPER.readValue(line, HookRequest.class);
            response = handler.apply(request);
            if (response == null) {
                response = HookResponse.deny("handler returned no response");
            }
        } catch (Exception e) {
            log.warn("hook handler failed — answering with a refusal", e);
            response = HookResponse.deny("internal Aura error");
        }

        writer.write(MAPPER.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    @Override
    public void close() {
        running = false;
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // channel is already closed — nothing to worry about
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (Exception e) {
            log.debug("failed to delete socket file {}", socketPath);
        }
        if (acceptor != null) {
            acceptor.interrupt();
        }
    }
}
