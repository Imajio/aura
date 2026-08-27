package aura.policy;

import aura.core.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a tool call against the project's registry.
 *
 * <p>The order of checks is determined by security, not convenience: first denial, then
 * writing tools with path verification, then explicit allow, and finally the default.
 * The default is {@link Decision#CONFIRM}: a tool the policy knows nothing about is not
 * executed silently.
 */
public final class PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(PermissionPolicy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Tools that write: not only the tool itself matters, but the target path. */
    private static final Set<String> WRITING_TOOLS =
        Set.of("edit", "write", "multiedit", "notebookedit");

    private static final List<String> PATH_FIELDS = List.of("file_path", "path", "notebook_path");

    private final Project project;

    public PermissionPolicy(Project project) {
        this.project = project;
    }

    public Decision decide(ToolRequest request) {
        String tool = request.toolName();

        if (contains(project.deny(), tool)) {
            return Decision.DENY;
        }

        if (WRITING_TOOLS.contains(tool.toLowerCase(java.util.Locale.ROOT))) {
            if (!contains(project.allow(), tool)) {
                return Decision.CONFIRM;
            }
            return writesInsideAllowedRoots(request) ? Decision.ALLOW : Decision.CONFIRM;
        }

        if (contains(project.allow(), tool)) {
            return Decision.ALLOW;
        }

        if (contains(project.confirm(), tool)) {
            return Decision.CONFIRM;
        }

        return Decision.CONFIRM;
    }

    private boolean writesInsideAllowedRoots(ToolRequest request) {
        JsonNode input;
        try {
            input = MAPPER.readTree(request.toolInputJson());
        } catch (Exception e) {
            log.debug("Could not parse arguments {} — escalation to confirmation", request.toolName());
            return false;
        }

        for (String field : PATH_FIELDS) {
            if (input.hasNonNull(field)) {
                Path target = Path.of(input.get(field).asText());
                Path resolved = (target.isAbsolute() ? target : request.cwd().resolve(target))
                    .normalize();
                if (isUnder(resolved, project.path())) {
                    return true;
                }
                for (Path extra : project.addDirs()) {
                    if (isUnder(resolved, extra)) {
                        return true;
                    }
                }
                return false;
            }
        }
        // A writing tool with no path in arguments — an opaque case, we ask.
        return false;
    }

    private static boolean isUnder(Path candidate, Path root) {
        return candidate.normalize().startsWith(root.normalize());
    }

    private static boolean contains(Set<String> set, String tool) {
        return set.stream().anyMatch(t -> t.equalsIgnoreCase(tool));
    }
}
