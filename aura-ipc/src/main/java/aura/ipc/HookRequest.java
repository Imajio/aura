package aura.ipc;

/**
 * A tool call as sent by the hook process. All fields are strings: the module
 * does not interpret them.
 */
public record HookRequest(String sessionId, String toolName, String toolInputJson, String cwd) {
}
