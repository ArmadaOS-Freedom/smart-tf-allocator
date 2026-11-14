package io.github.mydanyi.armadatf.ui

/**
 * Pure, JVM-testable coordinator for the handoff publish/recovery lifecycle.
 *
 * The Activity owns all Android work; this class only answers three questions:
 * which payload is current, whether that payload still has to be published
 * (dedup), and whether an async write/recovery result still belongs to it.
 * Completion is bound to BOTH the generation and the payload identity, so a
 * stale callback can never mark a different payload ready. Selecting a new
 * payload supersedes the previous one instead of queueing both.
 */
class HandoffLifecycle {

    enum class PublishDecision {
        /** This payload is not proven complete and must be published. */
        PUBLISH,

        /** This exact payload was already published/verified. */
        ALREADY_COMPLETE,
    }

    enum class RecoveryDisposition {
        /** The pass result belongs to the current payload; apply it. */
        APPLY,

        /** The captured snapshot was superseded: reload the newest pending copy. */
        RELOAD,

        /** The generation is stale: drop the result and let newer work drain. */
        IGNORE,
    }

    /** True while the current payload (or all-Android layout) is proven. */
    var ready: Boolean = false
        private set

    /** Identity of the payload currently proven complete, if any. */
    var completedKey: String? = null
        private set

    /**
     * Identity of the payload the Activity currently wants published, or
     * `null` when none is selected. Selecting a different payload supersedes
     * the previous one instead of queueing both.
     */
    var currentKey: String? = null
        private set

    // Read from the serial handoff executor as well as the UI thread.
    @Volatile
    private var generation: Long = 0L

    fun currentGeneration(): Long = generation

    fun isCurrent(operationGeneration: Long): Boolean =
        operationGeneration == generation

    /**
     * Selects [key] as the payload to publish.
     *
     * @return `true` when this selection superseded older async work, so every
     *   callback captured under the previous generation must be ignored.
     *   Re-selecting the payload that is already current, or one that is
     *   already proven complete, changes nothing: a repeated status poll must
     *   never invalidate work that is already correct.
     */
    fun selectPayload(key: String): Boolean {
        if (key == currentKey) return false
        if (ready && completedKey == key) {
            currentKey = key
            return false
        }
        generation++
        currentKey = key
        ready = false
        completedKey = null
        return true
    }

    /**
     * A confirmed new start: drop the previous completion and invalidate every
     * async callback captured under an older generation. The durable pending
     * file is intentionally not touched here.
     */
    fun begin() {
        generation++
        ready = false
        completedKey = null
        currentKey = null
    }

    /**
     * A verified all-Android layout: no Linux payload exists, so any in-flight
     * recovery/write for an older payload is stale.
     */
    fun androidReady() {
        generation++
        ready = true
        completedKey = ANDROID_KEY
        currentKey = null
    }

    /** Whether [linuxKey] must still be published. */
    fun decisionFor(linuxKey: String): PublishDecision =
        if (ready && completedKey == linuxKey) {
            PublishDecision.ALREADY_COMPLETE
        } else {
            PublishDecision.PUBLISH
        }

    /** True when the currently selected payload still has to be published. */
    fun currentNeedsPublication(): Boolean {
        val key = currentKey ?: return false
        return decisionFor(key) == PublishDecision.PUBLISH
    }

    /**
     * Decides what a finished pending-recovery pass may do with its result.
     * A stale generation is never applied, and a superseded snapshot is never
     * adopted: it only asks for one fresh read of the current pending payload.
     */
    fun recoveryDisposition(
        operationGeneration: Long,
        superseded: Boolean,
    ): RecoveryDisposition = when {
        !isCurrent(operationGeneration) -> RecoveryDisposition.IGNORE
        superseded -> RecoveryDisposition.RELOAD
        else -> RecoveryDisposition.APPLY
    }

    /**
     * Records an async write/recovery outcome for [key]. Returns `false` when
     * [operationGeneration] belongs to an older allocation or when [key] is no
     * longer the selected payload, in which case the caller must ignore the
     * result. A failed write records nothing, so the same payload stays
     * retryable.
     */
    fun writeCompleted(operationGeneration: Long, key: String, success: Boolean): Boolean {
        if (operationGeneration != generation) return false
        if (currentKey != null && currentKey != key) return false
        if (success) {
            ready = true
            completedKey = key
            currentKey = key
        }
        return true
    }

    companion object {
        /** Sentinel key for the verified all-Android completion. */
        const val ANDROID_KEY = "android_only"
    }
}
