package com.riskauditor.hashintel

/**
 * Where the consent decision is persisted.
 *
 * An interface so the module stays pure Kotlin; Android wires a
 * SharedPreferences-backed implementation in `:app`.
 */
interface ConsentStore {
    fun isGranted(): Boolean
    fun setGranted(granted: Boolean)
    /** Null until the user has made a choice either way. */
    fun grantedAtMillis(): Long?
    fun setGrantedAt(millis: Long?)
}

class InMemoryConsentStore(private var granted: Boolean = false) : ConsentStore {
    private var at: Long? = null
    override fun isGranted(): Boolean = granted
    override fun setGranted(granted: Boolean) {
        this.granted = granted
    }
    override fun grantedAtMillis(): Long? = at
    override fun setGrantedAt(millis: Long?) {
        at = millis
    }
}

/**
 * Gate on every network request this app can make.
 *
 * PS-01's privacy constraint: "No user data leaves the device, except optional
 * hash lookups after explicit consent."
 *
 * Three properties this class exists to guarantee:
 *
 *  - **Default OFF.** [InMemoryConsentStore] and every production store start at
 *    false. A fresh install performs zero lookups until the user says yes.
 *  - **Explicit.** [grant] takes the timestamp of the moment the user agreed, so
 *    the UI can say when and the user can audit it.
 *  - **Revocable.** [revoke] takes effect on the very next call, with no
 *    in-flight-request grace period.
 *
 * The gate is enforced in [ThreatIntelService], not left to callers to remember.
 */
class ConsentManager(private val store: ConsentStore = InMemoryConsentStore()) {

    val isGranted: Boolean get() = store.isGranted()

    val grantedAt: Long? get() = store.grantedAtMillis()

    fun grant(nowMillis: Long) {
        store.setGranted(true)
        store.setGrantedAt(nowMillis)
    }

    fun revoke() {
        store.setGranted(false)
        store.setGrantedAt(null)
    }

    /**
     * Exactly what the user is agreeing to, in plain language. Shown on the
     * consent dialog. Deliberately specific about what is and is not sent,
     * because "allow online checks?" tells someone nothing useful.
     */
    val disclosure: String = """
        If you turn this on, this app will send only a fingerprint of each app's
        installer file — a 64-character code — to a security service to ask whether
        it is already known to be harmful.

        What is sent: the fingerprint, and nothing else.
        What is never sent: the app itself, its name, your phone's identity,
        your location, or anything about you.

        A fingerprint cannot be turned back into the app or traced to you.
        You can turn this off again at any time, and everything else in this app
        keeps working without it.
    """.trimIndent()
}
