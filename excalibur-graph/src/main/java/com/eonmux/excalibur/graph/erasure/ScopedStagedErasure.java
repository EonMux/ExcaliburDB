package com.eonmux.excalibur.graph.erasure;

import com.eonmux.excalibur.graph.provenance.Provenance;
import com.eonmux.excalibur.graph.staging.StagedErasure;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * What a set of erasure scopes covers, as the staging store asks about it.
 *
 * <p>The one definition of which staged record a scope reaches. The coordinator discharging an
 * erasure obligation asks it, and so does ingestion withdrawing the facts of a document version
 * it has just replaced: a second matcher would be a second answer the day either changed, and
 * the symptom would be a withdrawal that reported success and removed nothing.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>Immutable.</p>
 *
 * @author ExcaliburDB
 */
public final class ScopedStagedErasure implements StagedErasure {

    private final List<ErasureScope> scopes;

    private ScopedStagedErasure(List<ErasureScope> scopes) {
        this.scopes = scopes;
    }

    /**
     * What the given scopes cover together.
     *
     * @param scopes The scopes; a record is erased when any of them covers it
     * @return The matcher
     * @throws NullPointerException if {@code scopes} or any scope in it is null
     */
    public static ScopedStagedErasure of(Collection<? extends ErasureScope> scopes) {
        Objects.requireNonNull(scopes, "scopes must not be null");
        return new ScopedStagedErasure(List.copyOf(scopes));
    }

    /**
     * What one scope covers.
     *
     * @param scope The scope
     * @return The matcher
     * @throws NullPointerException if {@code scope} is null
     */
    public static ScopedStagedErasure of(ErasureScope scope) {
        return new ScopedStagedErasure(List.of(Objects.requireNonNull(scope, "scope")));
    }

    @Override
    public boolean erasesFact(int subject, int relation, int object, int validFrom, int validTo,
                              Provenance provenance) {
        for (ErasureScope scope : scopes) {
            if (covers(scope, subject, object, provenance)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean erasesSnippet(int entityId, Provenance provenance) {
        for (ErasureScope scope : scopes) {
            if (covers(scope, entityId, entityId, provenance)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean erasesLabel(int entityId) {
        for (ErasureScope scope : scopes) {
            // Only an entity scope replaces a name. A withdrawn document must not take the names
            // of everyone it happened to mention with it: those people did not ask to be
            // forgotten, and their ids are referenced by facts from other sources.
            if (scope instanceof ErasureScope.Entity entity && entity.entityId() == entityId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether one scope covers one staged record.
     *
     * @param scope      The scope
     * @param subject    Subject entity id, or the entity a snippet belongs to
     * @param object     Object entity id, or the entity a snippet belongs to
     * @param provenance Where the record came from
     * @return true when the record must be removed
     */
    private static boolean covers(ErasureScope scope, int subject, int object,
                                  Provenance provenance) {
        return switch (scope) {
            // Both endpoints, for the same reason the store's own matcher takes both: keeping
            // the incoming edges leaves the erased entity reconstructible from its neighbours.
            case ErasureScope.Entity entity ->
                subject == entity.entityId() || object == entity.entityId();
            case ErasureScope.Source source ->
                provenance.sourceType() == source.sourceType()
                    && provenance.sourceId() == source.sourceId();
            case ErasureScope.ExtractorVersion version ->
                provenance.extractorVersion() == version.version();
        };
    }
}
