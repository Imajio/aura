package aura.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProjectRegistryTest {

    private static Project project(String name, String... aliases) {
        return new Project(name, List.of(aliases), Path.of("C:", "work", name),
            Agent.CLAUDE, List.of(),
            Set.of("Read", "Grep", "Glob", "Edit"),
            Set.of("Bash", "Write"),
            Set.of("WebSearch"));
    }

    private final ProjectRegistry registry = new ProjectRegistry(List.of(
        project("backend", "бэкенд", "сервер"),
        project("frontend", "фронтенд", "морда")));

    @Test
    void findsByExactName() {
        assertThat(registry.byName("backend")).map(Project::name).contains("backend");
        assertThat(registry.byName("no such project")).isEmpty();
    }

    @Test
    void findsByAliasInsideSpokenPhrase() {
        assertThat(registry.resolveFromSpeech("в проекте бэкенд почини тесты"))
            .map(Project::name).contains("backend");
        assertThat(registry.resolveFromSpeech("подними фронтенд и проверь сборку"))
            .map(Project::name).contains("frontend");
    }

    @Test
    void matchingIsCaseAndPunctuationInsensitive() {
        assertThat(registry.resolveFromSpeech("В ПРОЕКТЕ, Бэкенд: почини тесты!"))
            .map(Project::name).contains("backend");
    }

    @Test
    void toleratesOneRecognitionErrorInTheAlias() {
        // Speech recognition regularly drops one letter; the project must not
        // get lost because of it.
        assertThat(registry.resolveFromSpeech("в проекте бэкент почини тесты"))
            .map(Project::name).contains("backend");
    }

    @Test
    void returnsEmptyWhenNoProjectIsNamed() {
        assertThat(registry.resolveFromSpeech("почини падающие тесты")).isEmpty();
    }

    @Test
    void ambiguousPhraseWithTwoProjectsResolvesToNothing() {
        // Two named projects is not a reason to guess. Let it ask instead.
        assertThat(registry.resolveFromSpeech("перенеси из бэкенд во фронтенд")).isEmpty();
    }
}
