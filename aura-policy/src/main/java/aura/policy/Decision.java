package aura.policy;

/**
 * Verdict on a tool call. {@link #CONFIRM} means "ask the user".
 */
public enum Decision {
    ALLOW,
    CONFIRM,
    DENY
}
