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
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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

    /** A stalled peer must not be able to hold the acceptor thread. */
    private static final Duration READ_DEADLINE = Duration.ofSeconds(30);

    private final Path socketPath;
    private final Function<HookRequest, HookResponse> handler;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "aura-hook-connection");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService deadlines =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aura-hook-deadline");
            t.setDaemon(true);
            return t;
        });
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
        if (running) {
            throw new IllegalStateException("hook server is already listening on " + socketPath);
        }

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
            try {
                SocketChannel connection = channel.accept();
                workers.submit(() -> handle(connection));
            } catch (Exception e) {
                if (running) {
                    log.debug("hook connection could not be accepted: {}", e.toString());
                }
            }
        }
    }

    /**
     * Serves one connection, with a deadline that closes it if the peer never speaks.
     *
     * <p>Closing the channel from another thread unblocks the read: this is the only
     * thing standing between a frozen hook process and a permanently deaf server.
     */
    private void handle(SocketChannel connection) {
        ScheduledFuture<?> deadline = deadlines.schedule(
            () -> closeQuietly(connection), READ_DEADLINE.toSeconds(), TimeUnit.SECONDS);
        try (connection) {
            serve(connection);
        } catch (Exception e) {
            log.debug("hook connection ended early: {}", e.toString());
        } finally {
            deadline.cancel(false);
        }
    }

    private static void closeQuietly(SocketChannel connection) {
        try {
            connection.close();
        } catch (Exception ignored) {
            // the peer is already gone, which is the outcome we wanted anyway
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

        HookRequest request;
        try {
            request = MAPPER.readValue(line, HookRequest.class);
        } catch (Exception e) {
            log.debug("malformed hook request, denying: {}", abbreviate(line));
            writeResponse(writer, HookResponse.deny("malformed request"));
            return;
        }

        HookResponse response;
        try {
            response = handler.apply(request);
            if (response == null) {
                response = HookResponse.deny("handler returned no response");
            }
        } catch (Exception e) {
            log.warn("hook handler failed - answering with a refusal", e);
            response = HookResponse.deny("internal Aura error");
        }

        writeResponse(writer, response);
    }

    private static void writeResponse(BufferedWriter writer, HookResponse response)
            throws Exception {
        writer.write(MAPPER.writeValueAsString(response));
        writer.write('\n');
        writer.flush();
    }

    /** Keeps a malformed line out of the log at full length; it could be arbitrarily large. */
    private static String abbreviate(String text) {
        int limit = 200;
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }

    @Override
    public void close() {
        running = false;
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
            // channel is already closed - nothing to worry about
        }
        workers.shutdownNow();
        deadlines.shutdownNow();
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
