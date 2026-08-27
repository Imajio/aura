package aura.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aura.core.Agent;
import aura.core.Project;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectRegistryLoaderTest {

    @Test
    void loadsProjectsWithClassesAndDefaults(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("projects.yaml");
        Files.writeString(yaml, """
            projects:
              - name: backend
                aliases: ["бэкенд", "сервер"]
                path: C:\\work\\backend
                agent: claude
                allow: ["Read", "Grep"]
                confirm: ["Bash"]
                deny: ["WebSearch"]
              - name: scratch
                path: C:\\work\\scratch
                agent: codex
            """, StandardCharsets.UTF_8);

        var registry = ProjectRegistryLoader.load(yaml);

        assertThat(registry.all()).hasSize(2);
        Project backend = registry.byName("backend").orElseThrow();
        assertThat(backend.aliases()).containsExactly("бэкенд", "сервер");
        assertThat(backend.agent()).isEqualTo(Agent.CLAUDE);
        assertThat(backend.confirm()).containsExactly("Bash");

        Project scratch = registry.byName("scratch").orElseThrow();
        assertThat(scratch.agent()).isEqualTo(Agent.CODEX);
        // The default is safe: nothing is allowed, so everything gets asked about.
        assertThat(scratch.allow()).isEmpty();
        assertThat(scratch.aliases()).isEmpty();
    }

    @Test
    void missingFileYieldsEmptyRegistryRatherThanCrash(@TempDir Path tmp) {
        var registry = ProjectRegistryLoader.load(tmp.resolve("no-such-file.yaml"));
        assertThat(registry.all()).isEmpty();
    }

    @Test
    void projectWithoutPathIsRejectedLoudly(@TempDir Path tmp) throws Exception {
        Path yaml = tmp.resolve("projects.yaml");
        Files.writeString(yaml, """
            projects:
              - name: broken
                agent: claude
            """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ProjectRegistryLoader.load(yaml))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("broken");
    }
}
