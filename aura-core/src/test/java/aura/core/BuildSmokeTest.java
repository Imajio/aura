package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void javaVersionIs21OrHigher() {
        int major = Runtime.version().feature();
        assertThat(major).isGreaterThanOrEqualTo(21);
    }

    @Test
    void afUnixSocketsAreAvailableOnThisPlatform() throws Exception {
        // Вся связь с процессом хука построена на AF_UNIX. Если платформа его не
        // поддерживает, узнать об этом надо на сборке, а не в проде.
        try (var ch = java.nio.channels.ServerSocketChannel.open(
                java.net.StandardProtocolFamily.UNIX)) {
            assertThat(ch.isOpen()).isTrue();
        }
    }
}
