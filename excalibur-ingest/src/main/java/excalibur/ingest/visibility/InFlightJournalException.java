package excalibur.ingest.visibility;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Thrown when the record of which documents were being ingested cannot be read or written.
 *
 * <p>Carrying on without that record is not a lesser failure, which is why this is thrown rather
 * than logged. The journal is what keeps a document that was half written when the node died from
 * being served after the restart; a node that cannot read it does not know which documents those
 * are, and a node that cannot write it is about to write chunks it could not promise to hide.
 * Either way the guarantee is gone, and the only honest thing to do is say so where the caller
 * can act on it.</p>
 */
public final class InFlightJournalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InFlightJournalException(Path path, IOException cause) {
        super("Failed to use the in-flight document journal at " + path, cause);
    }

    InFlightJournalException(Path path, String reason) {
        super("Failed to read the in-flight document journal at " + path + ": " + reason);
    }
}
