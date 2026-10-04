package eu.neydev.birthday.core.api;

/**
 * An error executing a platform call. Adapters wrap their own here
 * exceptions, so the pipeline can uniformly decide: retry / slow down /
 * give up.
 */
public class PlatformException extends RuntimeException {

    private final int errorCode;

    public PlatformException(String message) {
        this(message, null, 0);
    }

    public PlatformException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    /**
     * @param errorCode the platform's own numeric code (VK: 15 = no scope for the
     *                  method, Telegram: 401 = bad token); {@code 0} when there is none
     *                  (network failures, timeouts). Adapters classify by it, so the
     *                  operator gets an exact fix instead of a generic retry loop.
     */
    public PlatformException(String message, Throwable cause, int errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public int errorCode() {
        return errorCode;
    }

    /**
     * The platform answered 429 / flood control: retry not before {@code retryAfterMillis}.
     * The outbound dispatcher honors this value instead of its own exponential backoff.
     */
    public static class RateLimitedException extends PlatformException {

        private final long retryAfterMillis;

        public RateLimitedException(String message, long retryAfterMillis) {
            super(message);
            this.retryAfterMillis = retryAfterMillis;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }

    }

    /**
     * The payload itself is invalid (a keyboard the platform refuses, a bad block):
     * no retry can ever succeed, yet the chat is healthy, so unlike a permanent
     * delivery failure this must NOT disable the user's reminders - the bug is ours.
     */
    public static class InvalidMessageException extends PlatformException {
        public InvalidMessageException(String message) {
            super(message, null, 0);
        }
    }

    /** The message can never be delivered (bot deleted, chat blocked). Retries are pointless. */
    public static class PermanentDeliveryException extends PlatformException {

        public PermanentDeliveryException(String message) {
            super(message);
        }

        public PermanentDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
