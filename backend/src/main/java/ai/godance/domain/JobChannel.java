package ai.godance.domain;

/**
 * How a generation job was made (V28). The server decides it from the
 * caller's credentials; a request cannot set it.
 */
public enum JobChannel {
    /** A signed-in session: the Studio in the web app. */
    STUDIO,
    /** An API key. */
    API
}
