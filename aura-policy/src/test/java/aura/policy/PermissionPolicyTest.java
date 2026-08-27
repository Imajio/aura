package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import aura.core.Agent;
import aura.core.Project;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PermissionPolicyTest {

    private static final Path ROOT = Path.of("C:", "work", "backend");
    private static final Path EXTRA = Path.of("C:", "work", "shared");

    private final Project project = new Project(
        "backend", List.of("бэкенд"), ROOT, Agent.CLAUDE, List.of(EXTRA),
        Set.of("Read", "Grep", "Glob", "Edit"),
        Set.of("Bash", "Write"),
        Set.of("WebSearch"));

    private final PermissionPolicy policy = new PermissionPolicy(project);

    private static ToolRequest req(String tool, String inputJson) {
        return new ToolRequest(tool, inputJson, ROOT);
    }

    @Test
    void deniedToolIsAlwaysDeniedEvenIfAlsoListedElsewhere() {
        assertThat(policy.decide(req("WebSearch", "{}"))).isEqualTo(Decision.DENY);
    }

    @Test
    void allowedReadOnlyToolPasses() {
        assertThat(policy.decide(req("Grep", "{\"pattern\":\"TODO\"}"))).isEqualTo(Decision.ALLOW);
        assertThat(policy.decide(req("Read", "{\"file_path\":\"C:\\\\work\\\\backend\\\\a.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editInsideProjectIsAllowed() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\backend\\\\src\\\\A.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editInsideAdditionalDirectoryIsAllowed() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\shared\\\\B.java\"}")))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void editOutsideEveryAllowedDirectoryEscalatesToConfirm() {
        // Permission is granted to the project, not to the entire filesystem.
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\Windows\\\\System32\\\\drivers\\\\etc\\\\hosts\"}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void confirmClassAlwaysAsks() {
        assertThat(policy.decide(req("Bash", "{\"command\":\"rm -rf build\"}"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void unlistedToolAsksRatherThanAllows() {
        assertThat(policy.decide(req("СовершенноНовыйИнструмент", "{}"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void malformedInputJsonEscalatesToConfirm() {
        // Could not parse input — we cannot tell where writes are going. We ask.
        assertThat(policy.decide(req("Edit", "{это не json"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void relativePathIsResolvedAgainstWorkingDirectory() {
        assertThat(policy.decide(new ToolRequest("Edit", "{\"file_path\":\"src/A.java\"}", ROOT)))
            .isEqualTo(Decision.ALLOW);
    }
}
