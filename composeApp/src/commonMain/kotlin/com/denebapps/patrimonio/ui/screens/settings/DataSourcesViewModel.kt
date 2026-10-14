package com.denebapps.patrimonio.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.denebapps.patrimonio.data.datasources.DataSourceSettings
import com.denebapps.patrimonio.data.datasources.DataSourceSettingsStore
import com.denebapps.patrimonio.data.quotes.EodhdApi
import com.denebapps.patrimonio.data.quotes.EodhdInvalidKeyException
import com.denebapps.patrimonio.data.quotes.EodhdUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val DATA_SOURCES_STOP_TIMEOUT_MS = 5_000L
private const val MASK = "••••••"
private const val VISIBLE_KEY_CHARS = 4

// A key this short would give away too much of itself: show no characters at all.
private const val MIN_KEY_LENGTH_TO_REVEAL = 9

/** Why EODHD is not being used right now although a key is stored. */
enum class DataSourceNotice(val message: String) {
    INVALID_KEY("EODHD rechazó la clave: se usan las fuentes habituales. Cámbiala o quítala."),
    QUOTA_EXHAUSTED("Cupo diario de EODHD agotado: hasta mañana se usan las fuentes habituales."),
}

/** The key being typed. [draft] is the text field's own value, the only place the full key lives in the UI state. */
data class KeyEntry(val draft: String = "", val checking: Boolean = false, val error: String? = null)

data class DataSourcesUiState(
    /** The stored key as `••••••ab12`, or `null` when there is none. */
    val maskedKey: String? = null,
    /** `Plan free · 6 de 20 llamadas hoy`, once known. */
    val usage: String? = null,
    val eodhdPrimary: Boolean = false,
    val notice: DataSourceNotice? = null,
    val entry: KeyEntry? = null,
    val confirmingRemoval: Boolean = false,
) {
    val hasKey: Boolean get() = maskedKey != null
}

/** The key with all but its last four characters hidden (none at all when it is too short to hide them well). */
internal fun maskApiKey(key: String): String =
    if (key.length >= MIN_KEY_LENGTH_TO_REVEAL) MASK + key.takeLast(VISIBLE_KEY_CHARS) else MASK

/**
 * Ajustes → Fuentes de datos: the user's own EODHD key (checked against EODHD before it is kept), its quota usage, and
 * whether EODHD is the primary price source or only a fallback. The key is never logged nor shown in full.
 */
class DataSourcesViewModel(
    private val store: DataSourceSettingsStore,
    private val api: EodhdApi,
    private val clock: Clock,
) : ViewModel() {
    private class Usage(val key: String, val usage: EodhdUsage)

    private val entry = MutableStateFlow<KeyEntry?>(null)
    private val confirmingRemoval = MutableStateFlow(false)
    private val loadedUsage = MutableStateFlow<Usage?>(null)
    private var checkJob: Job? = null

    val state: StateFlow<DataSourcesUiState> = combine(
        store.observe(),
        loadedUsage,
        entry,
        confirmingRemoval,
    ) { settings, usage, entry, confirming -> uiState(settings, usage, entry, confirming) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(DATA_SOURCES_STOP_TIMEOUT_MS), DataSourcesUiState())

    init {
        // The usage call is free, so it is read once per key: when the section opens and after the key changes.
        viewModelScope.launch {
            store.observe().map { it.apiKey }.distinctUntilChanged().collectLatest { key ->
                if (key != null && loadedUsage.value?.key != key) {
                    loadedUsage.value = runCatchingUsage(key)?.let { Usage(key, it) }
                }
            }
        }
    }

    fun onAddKey() {
        entry.value = KeyEntry()
    }

    fun onDraftChange(draft: String) {
        entry.update { current ->
            if (current == null || current.checking) current else current.copy(draft = draft, error = null)
        }
    }

    fun onEntryDismiss() {
        checkJob?.cancel()
        checkJob = null
        entry.value = null
    }

    /** Asks EODHD about the key (free, and the only way to tell a good key from a bad one) and keeps it if accepted. */
    fun onSave() {
        val current = entry.value ?: return
        val key = current.draft.trim()
        if (current.checking || key.isEmpty()) return
        entry.value = current.copy(checking = true, error = null)
        checkJob = viewModelScope.launch {
            val failure = try {
                loadedUsage.value = Usage(key, api.usage(key))
                store.setApiKey(key)
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: EodhdInvalidKeyException) {
                INVALID_KEY_MESSAGE
            } catch (_: Exception) {
                UNREACHABLE_MESSAGE
            }
            entry.value = if (failure == null) null else current.copy(checking = false, error = failure)
        }
    }

    fun onPrimaryChange(primary: Boolean) {
        viewModelScope.launch {
            if (store.current().apiKey != null) store.setEodhdPrimary(primary)
        }
    }

    fun onRemoveRequest() {
        confirmingRemoval.value = true
    }

    fun onRemoveDismiss() {
        confirmingRemoval.value = false
    }

    fun onRemoveConfirm() {
        confirmingRemoval.value = false
        viewModelScope.launch {
            store.setApiKey(null)
            loadedUsage.value = null
        }
    }

    private suspend fun runCatchingUsage(key: String): EodhdUsage? = try {
        api.usage(key)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private fun uiState(
        settings: DataSourceSettings,
        usage: Usage?,
        entry: KeyEntry?,
        confirmingRemoval: Boolean,
    ): DataSourcesUiState {
        val key = settings.apiKey
        val today = clock.now().toLocalDateTime(TimeZone.UTC).date
        val notice = when {
            key == null -> null
            settings.invalidKey -> DataSourceNotice.INVALID_KEY
            settings.quotaExhaustedOn == today -> DataSourceNotice.QUOTA_EXHAUSTED
            else -> null
        }
        return DataSourcesUiState(
            maskedKey = key?.let(::maskApiKey),
            usage = usage?.takeIf { it.key == key }?.let { usageText(it.usage) },
            eodhdPrimary = key != null && settings.eodhdPrimary,
            notice = notice,
            entry = entry,
            confirmingRemoval = confirmingRemoval && key != null,
        )
    }

    private fun usageText(usage: EodhdUsage): String {
        val calls = "${groupThousands(usage.apiRequests)} de ${groupThousands(usage.dailyRateLimit)} llamadas hoy"
        return if (usage.subscriptionType.isBlank()) calls else "Plan ${usage.subscriptionType} · $calls"
    }

    private companion object {
        const val INVALID_KEY_MESSAGE = "EODHD no reconoce esta clave"
        const val UNREACHABLE_MESSAGE = "No se pudo comprobar la clave. Revisa la conexión e inténtalo de nuevo."
    }
}

/** 1234567 → «1.234.567», the Spanish thousands separator. */
private fun groupThousands(value: Int): String = value.toString().reversed().chunked(3).joinToString(".").reversed()
