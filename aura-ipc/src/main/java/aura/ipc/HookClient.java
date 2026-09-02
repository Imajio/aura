package aura.ipc;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * The client used by the hook process.
 *
 * <p>Any trouble — no server, a dropped connection, garbage in the response — turns
 * into a refusal. A hook that got no answer has no right to permit the call.
 */
public final class HookClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HookClient() {
    }

    public static HookResponse ask(Path socketPath, HookRequest request, Duration timeout) {
        Thread caller = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeout.toMillis());
                caller.interrupt();
            } catch (InterruptedException ignored) {
                // the answer arrived in time
            }
        }, "aura-hook-client-timeout");
        watchdog.setDaemon(true);
        watchdog.start();

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));

            var writer = new BufferedWriter(
                new OutputStreamWriter(Channels.newOutputStream(channel), StandardCharsets.UTF_8));
            writer.write(MAPPER.writeValueAsString(request));
            writer.write('\n');
            writer.flush();

            var reader = new BufferedReader(
                new InputStreamReader(Channels.newInputStream(channel), StandardCharsets.UTF_8));
            String line = reader.readLine();
            if (line == null || line.isBlank()) {
                return HookResponse.deny("empty response from Aura");
            }
            return MAPPER.readValue(line, HookResponse.class);
        } catch (Exception e) {
            return HookResponse.deny("Aura is unreachable: " + e.getClass().getSimpleName());
        } finally {
            // Wait briefly for the watchdog to actually die before clearing our flag: if it
            // fired at the exact edge of the timeout, this keeps its caller.interrupt() from
            // landing after ask() has already returned to its caller.
            watchdog.interrupt();
            try {
                watchdog.join(100);
            } catch (InterruptedException ignored) {
                // the watchdog itself raced us here — fall through and clear the flag below
            }
            Thread.interrupted();
        }
    }
}
