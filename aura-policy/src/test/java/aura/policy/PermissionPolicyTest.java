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
        // Could not parse input - we cannot tell where writes are going. We ask.
        assertThat(policy.decide(req("Edit", "{это не json"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void relativePathIsResolvedAgainstWorkingDirectory() {
        assertThat(policy.decide(new ToolRequest("Edit", "{\"file_path\":\"src/A.java\"}", ROOT)))
            .isEqualTo(Decision.ALLOW);
    }

    @Test
    void siblingDirectorySharingThePrefixIsNotInsideTheProject() {
        // C:\work\backend-secrets is not inside C:\work\backend, however similar
        // the two look as strings.
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\backend-secrets\\\\A.java\"}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void traversalOutOfTheProjectEscalates() {
        assertThat(policy.decide(new ToolRequest("Edit", "{\"file_path\":\"..\\\\elsewhere\\\\A.java\"}", ROOT)))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void readingOutsideTheProjectEscalatesEvenThoughReadIsAllowed() {
        // The permission was granted to a project, not to the disk. An allowed read
        // that can open any absolute path is an exfiltration channel needing no write.
        assertThat(policy.decide(req("Read", "{\"file_path\":\"C:\\\\Users\\\\someone\\\\.ssh\\\\id_rsa\"}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void searchWithoutAPathStaysAllowed() {
        assertThat(policy.decide(req("Grep", "{\"pattern\":\"TODO\"}"))).isEqualTo(Decision.ALLOW);
    }

    @Test
    void writingToolWithoutAPathAsksBecauseTheTargetIsUnknown() {
        assertThat(policy.decide(req("Edit", "{\"content\":\"x\"}"))).isEqualTo(Decision.CONFIRM);
    }

    @Test
    void osInvalidPathStringEscalatesInsteadOfThrowing() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":\"C:\\\\bad<>|name.java\"}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void nonStringPathFieldEscalates() {
        assertThat(policy.decide(req("Edit", "{\"file_path\":{\"unexpected\":1}}")))
            .isEqualTo(Decision.CONFIRM);
    }

    @Test
    void deniedWritingToolIsStillDenied() {
        Project denyingEdit = new Project(
            "backend", List.of("бэкенд"), ROOT, Agent.CLAUDE, List.of(EXTRA),
            Set.of("Read"), Set.of("Bash"), Set.of("Edit"));
        assertThat(new PermissionPolicy(denyingEdit)
            .decide(req("Edit", "{\"file_path\":\"C:\\\\work\\\\backend\\\\A.java\"}")))
            .isEqualTo(Decision.DENY);
    }
}
