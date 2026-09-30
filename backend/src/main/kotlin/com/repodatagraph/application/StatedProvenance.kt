package com.repodatagraph.application

import com.repodatagraph.domain.exception.UnknownSourceSystemException
import com.repodatagraph.domain.model.Provenance
import com.repodatagraph.domain.ontology.OntologyRegistry
import com.repodatagraph.domain.port.out.CurrentPrincipal
import com.repodatagraph.domain.port.out.SourceWriteAuthorization
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The provenance of a fact a principal states through the API (#117): the source system the write
 * names, and who named it.
 *
 * Two checks, in this order. The source must be one the registry declares, or the write is
 * malformed (400) - a scope for a source nobody declared would mean nothing, so this does not depend
 * on who is asking. Then the principal must be allowed
 * to speak for it ([SourceWriteAuthorization], 403). Both run before the write touches the store.
 */
@Component
class StatedProvenance(
    private val registry: OntologyRegistry,
    private val authorization: SourceWriteAuthorization,
    private val currentPrincipal: CurrentPrincipal,
) {
    fun forWrite(
        sourceSystem: String,
        now: Instant = Instant.now(),
    ): Provenance {
        if (!registry.isKnownSource(sourceSystem)) throw UnknownSourceSystemException(sourceSystem, registry.knownSources())
        authorization.authorize(sourceSystem)
        return Provenance.stated(sourceSystem, now, currentPrincipal.current())
    }
}
