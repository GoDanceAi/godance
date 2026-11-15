package ai.godance.domain;

/**
 * How a generation job was made (V28). The server decides it from the
 * caller's credentials, or from the batch that made the job; a request
 * cannot set it.
 */
public enum JobChannel {
    /** A signed-in session: the Studio in the web app. */
    STUDIO,
    /** An API key. */
    API,
    /**
     * A row of a recipe batch (V36). How the batch itself was made, in the
     * Studio or with an API key, is the job's {@code batchChannel}.
     */
    BATCH
}
