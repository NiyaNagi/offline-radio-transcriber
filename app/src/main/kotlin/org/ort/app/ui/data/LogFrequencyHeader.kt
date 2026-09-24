package org.ort.app.ui.data

import android.content.Context
import org.ort.pipeline.capture.CaptureState
import org.ort.pipeline.capture.RigStatus
import org.ort.pipeline.rig.CaptureConfigurationStore

/**
 * R-1167, D58, **AC-202**: the hand-entered frequency's new home. It used to be asked for during
 * first-run setup (`SetupStep.RADIO_USB`, `RadioUsbScreen.kt`), which D58 removes — *"one tap
 * demanded a parseable number with a disabled button while the tap immediately beside it advanced
 * with the value still null and capture ran perfectly well"*. **Only the prompt moves.** The answer
 * is genuinely consumed: `CaptureConfiguration.manualFrequencyHz` →
 * `SharedPreferencesCaptureConfigurationStore` → read once by `RealCaptureService` at session start
 * and handed to `RigSupervisor.setManualFrequencyOverrideHz` (FR-RIG-8), which is why nothing
 * downstream of the store changes here.
 *
 * It lives in the Log's own header because that is the one surface where the operator can see what
 * the value is *labelling*: every row beneath it carries the frequency this answer produced.
 *
 * **Why this is a seam and not a direct store read at the call site.** [LogFrequencyFacts] is a
 * plain snapshot with no Android in it, so [LogFrequencyHeaderMapper] — which holds every decision,
 * including the three that matter (when the row is absent, when it is editable, and what it says
 * about a rig that disagrees) — is unit-testable with no Robolectric and no `SharedPreferences`.
 * This is the same shape [CaptureModeFacts] already establishes in this file's own package for the
 * same store.
 */
public data class LogFrequencyFacts(
    /**
     * The frequency a rig is reporting **right now**, or `null` when none is. Only
     * [RigStatus.State.Connected] counts — [RigStatus.State.Stale]/[RigStatus.State.Absent] would be
     * a frequency the radio is not presently reporting, and this file never fabricates one
     * (constitution I). Exactly the rule [LogPolling]'s own `connectedFrequencies` already applies
     * for the quick-filter chips, so the header and the chips can never disagree.
     */
    public val rigReportedHz: Long?,
    /** [org.ort.pipeline.rig.CaptureConfigurationStore.current]'s own `manualFrequencyHz` — what the
     * *next* session will be started with, and what the current one was started with. */
    public val activeManualHz: Long?,
    /** [org.ort.pipeline.rig.CaptureConfigurationStore.pendingConfiguration]'s own `manualFrequencyHz`
     * — an edit recorded while a session was live, which AC-131 forbids applying mid-session. */
    public val pendingManualHz: Long?,
    /** `true` exactly when a pending configuration exists at all. Kept separate from
     * [pendingManualHz] because a pending write can be about the *mode* and carry the same (or a
     * `null`) manual frequency — "there is a pending write" and "the pending write changes the
     * frequency" are two different facts. */
    public val hasPendingConfiguration: Boolean,
    /** [org.ort.pipeline.capture.CaptureState.isCapturing] — decides which of the two honest
     * "when does this take effect" sentences the editor carries. */
    public val isCapturing: Boolean,
)

/** What tapping the header's own action does. Two genuinely different actions, never one control
 * that silently means both: [EDIT] opens the in-place editor, [CLEAR_OVERRIDE] stops a
 * hand-entered value overriding a radio that is reporting its own (see
 * [LogFrequencyHeaderMapper.from]'s own kdoc for why that case must not offer an edit). */
public enum class LogFrequencyHeaderAction { EDIT, CLEAR_OVERRIDE }

/**
 * Everything the Log header's frequency row renders. A `null` view state (never an "empty" one) is
 * how the row is *absent* — see [LogFrequencyHeaderMapper.from].
 */
public data class LogFrequencyHeaderViewState(
    /** `"145.230 MHz"`, or [NOT_SET_LABEL] — never a fabricated number for a value nobody entered. */
    public val valueLabel: String,
    /** `"by hand"` / `"from the radio"`, or `null` when there is no value to attribute. */
    public val sourceLabel: String?,
    public val actionLabel: String,
    public val action: LogFrequencyHeaderAction,
    /** A second line stating something the value alone does not: a frozen mid-session edit
     * (AC-131), or a hand-entered value overriding a radio. `null` when there is nothing extra that
     * is true. */
    public val note: String?,
    /**
     * `true` when [note] describes something actually wrong or in flight — a hand-entered value
     * overriding a reporting radio, or an edit that has not taken effect yet — and `false` when it
     * is merely an honest statement of fact (no radio is reporting one, so overs carry no
     * frequency).
     *
     * The distinction is not decoration. Capture runs perfectly well with no frequency at all, which
     * is precisely what R-1167 found; colouring that amber would warn about a state the product is
     * fine with, and the guide reserves amber for a stated, recoverable *problem*. A conflicting
     * override is a problem — every over is being labelled with a number the radio disagrees with.
     */
    public val noteIsWarning: Boolean,
    /** The line the *editor* carries, so the operator is told when a save takes effect at the
     * moment of saving rather than after the fact. */
    public val editorNote: String,
    /** `true` exactly when a value is currently set (active or pending) — the editor's own
     * `Clear` action exists only then, so "never delete quietly" has a visible, deliberate control
     * rather than a blank field that might mean either. */
    public val hasValue: Boolean,
) {
    public companion object {
        public const val NOT_SET_LABEL: String = "not set"
    }
}

/**
 * The whole decision, pure. Three judgements are worth naming because each one is a place this
 * could have lied:
 *
 * 1. **The row is absent exactly when a rig is reporting a frequency and nothing is overriding it.**
 *    There is then nothing for this header to add — the radio's own reading is the answer, and
 *    Settings › Rig / the capture-status surface already report it per band. The brief's "visible
 *    whenever the session has no rig-reported frequency" is this clause.
 * 2. **A rig that *is* reporting is never offered a silent hand-override.** This is not symmetry for
 *    its own sake: `RigSupervisor.frequencyForTransmission` gives the manual override precedence
 *    over the rig (FR-RIG-8, provenance `manual`), so an editable field beside a live radio would
 *    let one tap silently relabel every over with a number the radio disagrees with. The action in
 *    that case is [LogFrequencyHeaderAction.CLEAR_OVERRIDE] and nothing else.
 * 3. **The conflicting case is surfaced, not hidden.** A hand-entered value *plus* a reporting rig
 *    is exactly the silent wrongness constitution I exists for — every over labelled 145.230 while
 *    the radio says 146.520, with nothing on screen saying so. [note] says it in words and names
 *    both numbers.
 *
 * **When a change takes effect is stated, never implied.** [CaptureConfigurationStore] freezes
 * configuration at session start (AC-131) and records a mid-session write as
 * `pendingConfiguration`; this mapper does not work around that, it reports it.
 */
public object LogFrequencyHeaderMapper {

    /** The two halves of AC-131, in words, at the moment of editing. */
    private const val EDITOR_NOTE_WHILE_CAPTURING: String =
        "This session keeps the frequency it started with. A change applies when you start the next session."
    private const val EDITOR_NOTE_WHILE_IDLE: String = "Applies to the next session you start."

    private const val NO_RIG_NOTE: String =
        "No radio is reporting a frequency. Overs are logged without one until you enter it."

    public fun from(facts: LogFrequencyFacts): LogFrequencyHeaderViewState? {
        val rigHz = facts.rigReportedHz
        val activeHz = facts.activeManualHz
        val pendingHz = facts.pendingManualHz.takeIf { facts.hasPendingConfiguration }
        val hasValue = activeHz != null || pendingHz != null
        // Clause 1 (see this object's own kdoc): the rig is answering and nothing overrides it.
        if (rigHz != null && !hasValue) return null

        val pendingNote = pendingChangeNote(facts, activeHz, pendingHz)
        val overrideNote = if (rigHz != null && activeHz != null) {
            "The radio is reporting ${megahertzLabel(rigHz)}. Your hand-entered value is overriding it " +
                "on every over."
        } else {
            null
        }
        val warnings = listOfNotNull(pendingNote, overrideNote)
        val note = warnings
            .ifEmpty { listOfNotNull(if (rigHz == null && !hasValue) NO_RIG_NOTE else null) }
            .joinToString(" ")
            .ifEmpty { null }

        // Clause 2: a reporting rig gets the clear action, never an edit.
        val offerClear = rigHz != null && activeHz != null
        return LogFrequencyHeaderViewState(
            valueLabel = activeHz?.let(::megahertzLabel) ?: LogFrequencyHeaderViewState.NOT_SET_LABEL,
            sourceLabel = when {
                activeHz != null -> "by hand"
                rigHz != null -> "from the radio"
                else -> null
            },
            actionLabel = when {
                offerClear -> "Use the radio's frequency"
                hasValue -> "Change"
                else -> "Set the frequency"
            },
            action = if (offerClear) LogFrequencyHeaderAction.CLEAR_OVERRIDE else LogFrequencyHeaderAction.EDIT,
            note = note,
            noteIsWarning = warnings.isNotEmpty(),
            editorNote = if (facts.isCapturing) EDITOR_NOTE_WHILE_CAPTURING else EDITOR_NOTE_WHILE_IDLE,
            hasValue = hasValue,
        )
    }

    /** AC-131 in words: a write made while capture was running did not touch this session. `null`
     * whenever the pending configuration does not actually change the frequency (a pending *mode*
     * change carries the same value, and reporting that as a frequency change would be a fabricated
     * fact). */
    private fun pendingChangeNote(facts: LogFrequencyFacts, activeHz: Long?, pendingHz: Long?): String? {
        if (!facts.hasPendingConfiguration || pendingHz == activeHz) return null
        val changedTo = pendingHz?.let(::megahertzLabel) ?: LogFrequencyHeaderViewState.NOT_SET_LABEL
        val keeping = activeHz?.let(::megahertzLabel) ?: LogFrequencyHeaderViewState.NOT_SET_LABEL
        return "Changed to $changedTo. This session keeps $keeping; the change applies when the next " +
            "session starts."
    }
}

/** `"145.230 MHz"` — three decimals like `SettingsRigFacts`/`SettingsPolling`'s own `"%.3f"`
 * rendering, so the surfaces agree on every frequency either of them can already show. */
public fun megahertzLabel(hz: Long): String = "${megahertzFieldText(hz)} MHz"

/**
 * The same number without its unit, for seeding the editor's own field.
 *
 * **Three decimals is the floor, not the cap, and that is the whole point.** `"%.3f"` — what every
 * other frequency label in this app uses — is 1 kHz resolution, and a real 12.5 kHz-step channel
 * (446.00625 MHz on PMR446, say) does not survive it. That is harmless for a label; it is *not*
 * harmless here, because this exact text is what the editor re-opens with, so a rounded seed plus
 * an untouched `Save` would silently rewrite the operator's own entry to a frequency they never
 * typed (constitution I). [java.math.BigDecimal] keeps every digit the stored [Long] actually has,
 * padded up to three decimals so the common case still reads `"145.230"` and never `"145.23"`.
 * [parseMegahertzToHz] reads back every string this produces, exactly — `LogFrequencyHeaderTest`
 * proves the round trip on a value that `"%.3f"` loses.
 */
public fun megahertzFieldText(hz: Long): String {
    val mhz = java.math.BigDecimal(hz).movePointLeft(MHZ_DECIMAL_SHIFT).stripTrailingZeros()
    return (if (mhz.scale() < MIN_MHZ_DECIMALS) mhz.setScale(MIN_MHZ_DECIMALS) else mhz).toPlainString()
}

private const val MHZ_DECIMAL_SHIFT = 6
private const val MIN_MHZ_DECIMALS = 3

/**
 * `null` for a blank or unparseable entry — never a fabricated frequency (constitution I). Carried
 * over from `RadioUsbScreen.parseMegahertzToHz`, which D58 deletes along with that screen, and
 * deliberately re-stated here rather than imported: this package must keep working once
 * `ui/setup/RadioUsbScreen.kt` is gone.
 */
public fun parseMegahertzToHz(text: String): Long? {
    val mhz = text.trim().toDoubleOrNull() ?: return null
    if (mhz <= 0.0) return null
    return Math.round(mhz * MHZ_TO_HZ)
}

private const val MHZ_TO_HZ = 1_000_000.0

/**
 * The read/write seam for the header. **Writes go through
 * [CaptureConfigurationStore.update] and nothing else**, so AC-131's freeze rule cannot be
 * bypassed from here: that method itself decides whether the write lands on `current` or becomes
 * `pendingConfiguration`, and this interface has no way to reach past it.
 */
public interface LogFrequencyEditor {
    public fun facts(): LogFrequencyFacts

    /** [hz] `null` clears the hand-entered value outright (constitution III: the clear is an
     * explicit, operator-initiated action with its own control, never a side effect of a blank
     * field). */
    public fun save(hz: Long?)
}

/**
 * The real editor, over the same `SharedPreferences`-backed store `RealCaptureService` reads at
 * session start ([realCaptureConfigurationStore]).
 *
 * **[save] bases its write on the pending configuration when one exists**, not on `current`:
 * otherwise a frequency edit made after a mode change was already recorded mid-session would
 * silently drop that pending mode change on the floor.
 */
public class RealLogFrequencyEditor(
    private val store: CaptureConfigurationStore,
    private val rigState: () -> RigStatus.State = { RigStatus.state },
    private val isCapturing: () -> Boolean = { CaptureState.isCapturing },
) : LogFrequencyEditor {
    public constructor(context: Context) : this(realCaptureConfigurationStore(context))

    override fun facts(): LogFrequencyFacts {
        val pending = store.pendingConfiguration()
        return LogFrequencyFacts(
            rigReportedHz = (rigState() as? RigStatus.State.Connected)
                ?.bands
                ?.firstNotNullOfOrNull { it.frequencyHz },
            activeManualHz = store.current().manualFrequencyHz,
            pendingManualHz = pending?.manualFrequencyHz,
            hasPendingConfiguration = pending != null,
            isCapturing = isCapturing(),
        )
    }

    override fun save(hz: Long?) {
        val base = store.pendingConfiguration() ?: store.current()
        store.update(base.copy(manualFrequencyHz = hz))
    }
}

/** The behavioural fake (constitution II) — scriptable to any [LogFrequencyFacts], and it records
 * every [save] so a test can prove the screen wrote exactly once, with exactly what the operator
 * typed, rather than merely that it called something. */
public class FakeLogFrequencyEditor(
    private var facts: LogFrequencyFacts = LogFrequencyFacts(
        rigReportedHz = null,
        activeManualHz = null,
        pendingManualHz = null,
        hasPendingConfiguration = false,
        isCapturing = false,
    ),
) : LogFrequencyEditor {
    public val saved: MutableList<Long?> = mutableListOf()

    override fun facts(): LogFrequencyFacts = facts

    override fun save(hz: Long?) {
        saved += hz
        facts = if (facts.isCapturing) {
            facts.copy(pendingManualHz = hz, hasPendingConfiguration = true)
        } else {
            facts.copy(activeManualHz = hz, pendingManualHz = null, hasPendingConfiguration = false)
        }
    }

    public fun setFacts(value: LogFrequencyFacts) {
        facts = value
    }
}
