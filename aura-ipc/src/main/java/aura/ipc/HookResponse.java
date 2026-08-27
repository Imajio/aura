package aura.ipc;

/**
 * The verdict for the hook. {@code permissionDecision} values are the ones Claude Code
 * understands: {@code allow}, {@code deny}, {@code ask}.
 */
public record HookResponse(String permissionDecision, String reason) {

    public static HookResponse deny(String reason) {
        return new HookResponse("deny", reason);
    }
}
