package eu.neydev.birthday.core.api;

/**
 * The platform adapter contract. The implementation lives in its own module
 * (platform-telegram / platform-vk / platform-discord) and is a THIN layer:
 * mapping updates into {@link IncomingUpdate}, transliterating {@link OutboundMessage}
 * into API calls and honest {@link PlatformException} errors.
 *
 * <p>Lifecycle: {@link #start(PlatformContext)} starts the receiving threads,
 * {@link #stop()} - stops them cleanly (graceful shutdown).
 * {@link #execute(OutboundMessage)} is called by the outbound dispatcher threads.
 */
public interface PlatformAdapter {

    Platform platform();

    /** Start receiving updates; events are handed to {@code sink}. */
    void start(PlatformContext context);

    /** Stop receiving (idempotent). */
    void stop();

    /**
     * Execute an outbound command synchronously (a blocking platform API call).
     *
     * @throws PlatformException.RateLimitedException    on platform flood-control;
     * @throws PlatformException.PermanentDeliveryException if delivery is impossible forever;
     * @throws PlatformException                            on other failures (network, 5xx).
     */
    void execute(OutboundMessage message);

    /** The platform is enabled in the configuration and ready to start. */
    boolean isEnabled();

}
