package io.github.mydanyi.armadatf.domain

import java.util.UUID

/**
 * Per-process witness of one live Android framework instance.
 *
 * Android 8 (`API 26`) does not publish `sys.system_server.start_count` or
 * `sys.system_server.start_elapsed`, so [FrameworkInstanceId.create] cannot
 * name that instance. The package-service binder is recreated on every
 * framework restart, so holding that exact binder object and checking that it
 * is alive witnesses the instance without any system property.
 *
 * A token is generated when the observed binder object or the canonical kernel
 * boot changes; the canonical id therefore stays stable for one live instance
 * and differs for a new one. A dead binder, a missing binder or an unreadable
 * boot clears the observation: the kernel UUID alone is never an identity.
 *
 * This is a correctness witness only: it names the framework instance that is
 * running, never that an old native task ended. Only a real kernel boot change
 * proves that.
 */
class BinderFrameworkIdentity(
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {

    private var binder: Any? = null
    private var bootId: String? = null
    private var witnessId: String? = null
    private var identity: String? = null

    /**
     * Returns the canonical binder-form identity for [binder] on [bootId], or
     * `null` when the binder is absent/dead or the boot id is invalid.
     *
     * A different binder object or a different boot rotates the witness token,
     * so an id from an earlier framework instance never names the current one.
     * The returned value stays the same while the same live binder and boot are
     * observed.
     */
    @Synchronized
    fun observe(bootId: String?, binder: Any?, alive: Boolean): String? {
        val canonicalBoot = FrameworkInstanceId.canonicalBootUuid(bootId)
        if (canonicalBoot == null || binder == null || !alive) {
            clear()
            return null
        }
        if (binder !== this.binder || canonicalBoot != this.bootId) {
            this.binder = binder
            this.bootId = canonicalBoot
            this.witnessId = tokenFactory()
            this.identity = FrameworkInstanceId.createBinder(canonicalBoot, witnessId)
        }
        return identity
    }

    private fun clear() {
        binder = null
        bootId = null
        witnessId = null
        identity = null
    }
}
