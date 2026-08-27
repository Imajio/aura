package aura.app;

import aura.core.Agent;
import aura.core.Project;
import aura.core.ProjectRegistry;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Reads {@code projects.yaml} into the domain registry. The only place that knows about YAML. */
public final class ProjectRegistryLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectRegistryLoader.class);

    private ProjectRegistryLoader() {
    }

    public static ProjectRegistry load(Path yamlFile) {
        if (!Files.isRegularFile(yamlFile)) {
            log.warn("project registry not found: {} - falling back to an empty list", yamlFile);
            return new ProjectRegistry(List.of());
        }
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Map<String, Object> root = new Yaml().load(in);
            List<Project> projects = new ArrayList<>();
            Object raw = root == null ? null : root.get("projects");
            if (raw instanceof List<?> list) {
                for (Object element : list) {
                    if (element instanceof Map<?, ?> map) {
                        projects.add(toProject(map));
                    }
                }
            }
            return new ProjectRegistry(projects);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("failed to read project registry: " + yamlFile, e);
        }
    }

    private static Project toProject(Map<?, ?> map) {
        String name = str(map.get("name"), "<unnamed>");
        Object path = map.get("path");
        if (path == null) {
            throw new IllegalArgumentException("project " + name + " has no path");
        }
        return new Project(
            name,
            strings(map.get("aliases")).stream().toList(),
            Path.of(String.valueOf(path)),
            "codex".equals(str(map.get("agent"), "claude").toLowerCase(Locale.ROOT))
                ? Agent.CODEX : Agent.CLAUDE,
            strings(map.get("addDirs")).stream().map(Path::of).toList(),
            strings(map.get("allow")),
            strings(map.get("confirm")),
            strings(map.get("deny")));
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
