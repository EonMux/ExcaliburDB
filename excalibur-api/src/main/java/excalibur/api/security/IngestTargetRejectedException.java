package excalibur.api.security;

/**
 * Signals that an ingest target was refused by the security policy.
 *
 * <p>The message carried by this exception is the one that may safely reach the
 * client: it names the reason class only. The rejected path, host or resolved
 * address is logged server-side by {@link IngestTargetValidator} and never
 * travels back over the wire, so a caller cannot use rejections to map the
 * server's filesystem or internal network.</p>
 *
 * @author ExcaliburDB
 */
public class IngestTargetRejectedException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a rejection with a client-safe message.
     *
     * @param clientSafeMessage Message that may be returned to the caller
     */
    public IngestTargetRejectedException(String clientSafeMessage) {
        super(clientSafeMessage);
    }
}
