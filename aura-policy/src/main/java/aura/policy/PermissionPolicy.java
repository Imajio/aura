package aura.policy;

import aura.core.Project;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a tool call against the project's registry.
 *
 * <p>The order of checks is determined by security, not convenience: first denial, then
 * allow-listed tools with path verification, then the default. The default is
 * {@link Decision#CONFIRM}: a tool the policy knows nothing about is not executed silently.
 * Permissions are granted to a project and its additional directories, not to the entire
 * filesystem.
 */
public final class PermissionPolicy {

    private static final Logger log = LoggerFactory.getLogger(PermissionPolicy.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Why a target path could not be confirmed as living inside the project. */
    private enum PathCheck { CONTAINED, OUTSIDE, NO_PATH, UNREADABLE }

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

        boolean allowed = contains(project.allow(), tool);
        boolean writing = WRITING_TOOLS.contains(tool.toLowerCase(Locale.ROOT));

        if (allowed) {
            PathCheck check = targetLocation(request);
            if (check == PathCheck.CONTAINED) {
                return Decision.ALLOW;
            }
            // A tool that names no path at all is judged by its own nature: a search
            // rooted at the working directory stays in the project, but a write whose
            // destination we cannot read is exactly the case worth asking about.
            if (check == PathCheck.NO_PATH) {
                return writing ? Decision.CONFIRM : Decision.ALLOW;
            }
            return Decision.CONFIRM;
        }

        // Everything else asks: an explicitly listed confirm tool, and equally a tool
        // the policy has never heard of. Silence is not permission.
        return Decision.CONFIRM;
    }

    private PathCheck targetLocation(ToolRequest request) {
        JsonNode input;
        try {
            input = MAPPER.readTree(request.toolInputJson());
        } catch (Exception e) {
            log.debug("could not parse arguments for {} — escalating to confirmation",
                request.toolName());
            return PathCheck.UNREADABLE;
        }

        for (String field : PATH_FIELDS) {
            if (!input.hasNonNull(field)) {
                continue;
            }
            JsonNode value = input.get(field);
            if (!value.isTextual()) {
                return PathCheck.UNREADABLE;
            }
            Path target;
            try {
                Path named = Path.of(value.asText());
                target = named.isAbsolute() ? named : request.cwd().resolve(named);
            } catch (InvalidPathException e) {
                return PathCheck.UNREADABLE;
            }

            Path resolved = realOrNearest(target);
            if (isUnder(resolved, project.path())) {
                return PathCheck.CONTAINED;
            }
            for (Path extra : project.addDirs()) {
                if (isUnder(resolved, extra)) {
                    return PathCheck.CONTAINED;
                }
            }
            log.info("{} targets {}, outside the project and its additional directories",
                request.toolName(), resolved);
            return PathCheck.OUTSIDE;
        }
        return PathCheck.NO_PATH;
    }

    private static boolean isUnder(Path candidate, Path root) {
        return candidate.startsWith(realOrNearest(root));
    }

    /**
     * Resolves symlinks as far as the filesystem allows, then re-appends whatever
     * does not exist yet.
     *
     * <p>A lexical check alone is not containment: a symlink planted inside the
     * project can point anywhere on disk and still look contained. The target of a
     * write often does not exist yet, so resolving the whole path is not an option —
     * the nearest existing ancestor is.
     */
    private static Path realOrNearest(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path probe = absolute;
        Deque<Path> missing = new ArrayDeque<>();
        while (probe != null) {
            try {
                Path real = probe.toRealPath();
                for (Path segment : missing) {
                    real = real.resolve(segment);
                }
                return real.normalize();
            } catch (IOException e) {
                Path name = probe.getFileName();
                if (name == null) {
                    return absolute;
                }
                missing.addFirst(name);
                probe = probe.getParent();
            }
        }
        return absolute;
    }

    private static boolean contains(Set<String> set, String tool) {
        return set.stream().anyMatch(t -> t.equalsIgnoreCase(tool));
    }
}
