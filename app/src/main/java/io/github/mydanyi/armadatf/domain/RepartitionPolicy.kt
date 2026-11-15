package io.github.mydanyi.armadatf.domain

/**
 * Pure pre-format guard for a card selected for repartitioning.
 *
 * Formatting destroys every volume on the card, so it is refused while the
 * card still carries primary storage or any installed application. Unlike the
 * migration inventory this check is neither user-scoped nor filtered to
 * movable user apps: a system application, or an application of another
 * Android profile, still keeps the card in use.
 *
 * [MigrationErrors.APP_QUERY_UNAVAILABLE] is reused for identities that cannot
 * be verified: formatting without knowing which volume is on the card would
 * silently destroy data that cannot be enumerated.
 */
object RepartitionPolicy {

    /**
     * Refusal for the card's private volumes before any occupancy query.
     *
     * [privateFsUuids] holds one entry per `TYPE_PRIVATE` volume on the card,
     * exactly as the platform reports it (possibly `null`). An empty list is
     * safe: a card without private volumes holds no adoptable Android data.
     * Any unreadable or malformed identifier makes occupancy unverifiable, and
     * a known + unknown pair is just as unsafe as a single unknown one.
     */
    fun privateIdentityRefusal(privateFsUuids: List<String?>): String? {
        if (privateFsUuids.isEmpty()) return null
        val usable = privateFsUuids.all { Identifiers.normalizeUuid(it) != null }
        return if (usable) null else MigrationErrors.APP_QUERY_UNAVAILABLE
    }

    /** Canonical UUIDs of the card's private volumes; the caller checked them first. */
    fun privateUuids(privateFsUuids: List<String?>): Set<String> =
        privateFsUuids.mapNotNull { Identifiers.normalizeUuid(it) }.toSet()

    /**
     * Occupancy verdict for a card whose private volumes were verified.
     *
     * [appSourceUuids] is every installed application of every Android user
     * (system apps included), so a profile-only or system-app occupant blocks
     * formatting. Comparisons always go through [Identifiers.normalizeUuid], so
     * hyphenated, compact and case variants match.
     *
     * @return [MigrationErrors.MOVE_BACK_REQUIRED] when the card is in use,
     *   `null` when it is safe.
     */
    fun occupancyRefusal(
        privateUuids: Set<String>,
        primaryUuid: String?,
        appSourceUuids: Iterable<String?>,
    ): String? {
        if (privateUuids.isEmpty()) return null
        val canonical = privateUuids.mapNotNull { Identifiers.normalizeUuid(it) }.toSet()
        if (canonical.size != privateUuids.size) return MigrationErrors.APP_QUERY_UNAVAILABLE
        val primary = Identifiers.normalizeUuid(primaryUuid)
        if (primary != null && primary in canonical) return MigrationErrors.MOVE_BACK_REQUIRED
        val occupied = appSourceUuids.any { source ->
            val normalized = Identifiers.normalizeUuid(source)
            normalized != null && normalized in canonical
        }
        return if (occupied) MigrationErrors.MOVE_BACK_REQUIRED else null
    }
}
