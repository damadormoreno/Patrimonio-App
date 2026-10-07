package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalAdd
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.BillingCycle
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.domain.repository.SavingsGoalArithmeticOverflowException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure JSON <-> [BackupDocument] codec. [decode] checks the envelope (format + version) before
 * decoding the body, then validates the same invariants the database and the domain enforce
 * (enum names, currency codes, unique ids, foreign keys, savings-goal ledger and link rules), so a
 * hand-edited or corrupted file is rejected with a readable reason instead of failing half-way
 * through an import or crashing a later read.
 *
 * Writes [VERSION] (5). Reads every version from 1 up to [VERSION]: a v1 file simply lacks the
 * group-link fields, which default to null, v1/v2 files lack subscriptions, which default to empty,
 * v1–v3 files have numeric goal ids, which [upgradeLegacyGoalIds] replaces before decoding, and v1–v4
 * files link a goal to one `linkedAssetId`, which [upgradeSingleLinkedAsset] turns into a list.
 */
object BackupCodec {
    const val FORMAT = "patrimonio-backup"
    const val VERSION = 5

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(document: BackupDocument): String = json.encodeToString(BackupDocument.serializer(), document)

    /** @throws InvalidBackupException with a user-facing (Spanish) reason. */
    fun decode(text: String): BackupDocument {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (error: IllegalArgumentException) {
            throw InvalidBackupException("El archivo no es un JSON válido.", error)
        }
        val version = checkEnvelope(root)
        val withGoalIds = if (version < 4) upgradeLegacyGoalIds(root) else root
        val upgraded = if (version < 5) upgradeSingleLinkedAsset(withGoalIds) else withGoalIds
        val document = try {
            json.decodeFromJsonElement(BackupDocument.serializer(), upgraded)
        } catch (error: IllegalArgumentException) {
            throw InvalidBackupException("Al archivo le faltan campos o alguno tiene un tipo incorrecto.", error)
        }
        document.validate()
        return document
    }

    /** @return the file's version. */
    private fun checkEnvelope(root: JsonObject): Int {
        val format = (root["format"] as? JsonPrimitive)?.contentOrNull
        if (format != FORMAT) invalid("El archivo no es una copia de Patrimonio.")
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
            ?: invalid("La copia no indica su versión.")
        if (version > VERSION) {
            invalid("La copia es de una versión más nueva de la app (v$version). Actualiza la app.")
        }
        if (version < 1) invalid("Versión de copia desconocida (v$version).")
        return version
    }
}

/**
 * Up to version 3 goal ids were autoincrement numbers. Like the 3 -> 4 database migration, each goal
 * gets a fresh UUID and keeps its old id as `createdAtEpochMs`, so the order survives, and both event
 * lists follow the new ids. An event pointing at a goal that is not in the file keeps its old id as
 * text, which [validate] then rejects like any other dangling reference.
 */
private fun upgradeLegacyGoalIds(root: JsonObject): JsonObject {
    val newIds = mutableMapOf<String, String>()
    val goals = root.arrayOrEmpty("savingsGoals").map { element ->
        val goal = element as? JsonObject ?: return@map element
        val oldId = goal["id"] as? JsonPrimitive
        val createdAt = oldId?.longOrNull ?: return@map goal
        val newId = randomGoalId().also { newIds[oldId.content] = it }
        JsonObject(goal + mapOf("id" to JsonPrimitive(newId), "createdAtEpochMs" to JsonPrimitive(createdAt)))
    }
    fun JsonElement.withNewGoalId(): JsonElement {
        val event = this as? JsonObject ?: return this
        val oldId = (event["goalId"] as? JsonPrimitive)?.content ?: return this
        return JsonObject(event + ("goalId" to JsonPrimitive(newIds[oldId] ?: oldId)))
    }
    return JsonObject(
        root + mapOf(
            "savingsGoals" to JsonArray(goals),
            "savingsGoalAllocationEvents" to
                JsonArray(root.arrayOrEmpty("savingsGoalAllocationEvents").map { it.withNewGoalId() }),
            "savingsGoalLinkEvents" to
                JsonArray(root.arrayOrEmpty("savingsGoalLinkEvents").map { it.withNewGoalId() }),
        ),
    )
}

/** Up to version 4 a goal followed at most one account, `linkedAssetId`; now it is `linkedAssetIds`. */
private fun upgradeSingleLinkedAsset(root: JsonObject): JsonObject {
    val goals = root.arrayOrEmpty("savingsGoals").map { element ->
        val goal = element as? JsonObject ?: return@map element
        val assetId = goal["linkedAssetId"] as? JsonPrimitive
        val assetIds = if (assetId == null || assetId is JsonNull) emptyList() else listOf(assetId)
        JsonObject(goal - "linkedAssetId" + ("linkedAssetIds" to JsonArray(assetIds)))
    }
    return JsonObject(root + ("savingsGoals" to JsonArray(goals)))
}

private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

@OptIn(ExperimentalUuidApi::class)
private fun randomGoalId(): String = Uuid.random().toString()

private fun invalid(reason: String): Nothing = throw InvalidBackupException(reason)

private val ASSET_GROUPS = Asset.AssetGroup.entries.map { it.name }.toSet()
private val LIABILITY_GROUPS = Liability.LiabilityGroup.entries.map { it.name }.toSet()
private val CURRENCIES = Currency.entries.map { it.name }.toSet()
private val LIFECYCLES = SavingsGoalLifecycle.entries.map { it.name }.toSet()
private val LINK_KINDS = SavingsGoalLinkEventKind.entries.map { it.name }.toSet()
private val BILLING_CYCLES = BillingCycle.entries.map { it.name }.toSet()
private val YEAR_MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")

private fun BackupDocument.validate() {
    requireUnique("activo", assets.map { it.id })
    assets.forEach { asset ->
        if (asset.id.isBlank()) invalid("Hay un activo sin id.")
        if (asset.group !in ASSET_GROUPS) {
            invalid("El activo '${asset.name}' tiene un tipo desconocido: ${asset.group}.")
        }
        if (asset.currency !in CURRENCIES) {
            invalid("El activo '${asset.name}' tiene una divisa no soportada: ${asset.currency}.")
        }
    }

    requireUnique("pasivo", liabilities.map { it.id })
    liabilities.forEach { liability ->
        if (liability.id.isBlank()) invalid("Hay un pasivo sin id.")
        if (liability.group !in LIABILITY_GROUPS) {
            invalid("El pasivo '${liability.name}' tiene un tipo desconocido: ${liability.group}.")
        }
        if (liability.currency !in CURRENCIES) {
            invalid("El pasivo '${liability.name}' tiene una divisa no soportada: ${liability.currency}.")
        }
    }

    val assetIds = assets.map { it.id }.toSet()
    requireUnique("grupo", accountGroups.map { it.id })
    val groupIds = accountGroups.map { it.id }.toSet()
    requireUnique("miembro de grupo", accountGroupMembers.map { "${it.groupId}/${it.assetId}" })
    accountGroupMembers.forEach { member ->
        if (member.groupId !in groupIds) invalid("Un miembro apunta a un grupo que no existe: ${member.groupId}.")
        if (member.assetId !in assetIds) invalid("Un grupo incluye un activo que no existe: ${member.assetId}.")
    }

    requireUnique("mes del histórico", netWorthSnapshots.map { it.yearMonth })
    netWorthSnapshots.forEach { snapshot ->
        if (!YEAR_MONTH.matches(snapshot.yearMonth)) invalid("Mes del histórico mal formado: ${snapshot.yearMonth}.")
    }

    requireUnique("meta", savingsGoals.map { it.id })
    savingsGoals.forEach { goal ->
        if (goal.id.isBlank()) invalid("La meta '${goal.name}' no tiene id.")
        if (goal.name.isEmpty() || goal.name != goal.name.trim()) invalid("Hay una meta con un nombre no válido.")
        if (goal.targetMinor <= 0) invalid("La meta '${goal.name}' tiene un objetivo no positivo.")
        if (goal.currency !in CURRENCIES) {
            invalid("La meta '${goal.name}' tiene una divisa no soportada: ${goal.currency}.")
        }
        if (goal.lifecycle !in LIFECYCLES) {
            invalid("La meta '${goal.name}' tiene un estado desconocido: ${goal.lifecycle}.")
        }
        if (goal.linkedAssetIds.any { it !in assetIds }) {
            invalid("La meta '${goal.name}' está vinculada a un activo que no existe.")
        }
        if (goal.linkedAssetIds.size != goal.linkedAssetIds.toSet().size) {
            invalid("La meta '${goal.name}' repite un activo vinculado.")
        }
        if (goal.linkedGroupId != null && goal.linkedGroupId !in groupIds) {
            invalid("La meta '${goal.name}' está vinculada a un grupo que no existe.")
        }
        if (goal.linkedAssetIds.isNotEmpty() && goal.linkedGroupId != null) {
            invalid("La meta '${goal.name}' está vinculada a un activo y a un grupo a la vez.")
        }
    }

    val goalIds = savingsGoals.map { it.id }.toSet()
    requireUnique("aportación", savingsGoalAllocationEvents.map { it.id.toString() })
    savingsGoalAllocationEvents.forEach { event ->
        if (event.id <= 0) invalid("Hay una aportación con un id no válido.")
        if (event.goalId !in goalIds) invalid("Una aportación apunta a una meta que no existe: ${event.goalId}.")
    }
    val goalNames = savingsGoals.associate { it.id to it.name }
    savingsGoalAllocationEvents
        .groupBy { it.goalId }
        .forEach { (goalId, events) -> validateLedger(goalNames.getValue(goalId), events) }

    requireUnique("cambio de vínculo", savingsGoalLinkEvents.map { it.id.toString() })
    savingsGoalLinkEvents.forEach { event ->
        if (event.id <= 0) invalid("Hay un cambio de vínculo con un id no válido.")
        if (event.goalId !in goalIds) invalid("Un cambio de vínculo apunta a una meta que no existe: ${event.goalId}.")
        if (event.kind !in LINK_KINDS) invalid("Tipo de cambio de vínculo desconocido: ${event.kind}.")
    }

    requireUnique("suscripción", subscriptions.map { it.id })
    subscriptions.forEach { subscription ->
        if (subscription.id.isBlank()) invalid("Hay una suscripción sin id.")
        if (subscription.name.isEmpty() || subscription.name != subscription.name.trim()) {
            invalid("Hay una suscripción con un nombre no válido.")
        }
        if (subscription.amountMinor <= 0) {
            invalid("La suscripción '${subscription.name}' tiene un importe no positivo.")
        }
        if (subscription.currency !in CURRENCIES) {
            invalid("La suscripción '${subscription.name}' tiene una divisa no soportada: ${subscription.currency}.")
        }
        if (subscription.cycle !in BILLING_CYCLES) {
            invalid("La suscripción '${subscription.name}' tiene una periodicidad desconocida: ${subscription.cycle}.")
        }
        if (subscription.paidFromAssetId != null && subscription.paidFromAssetId !in assetIds) {
            invalid("La suscripción '${subscription.name}' se paga desde un activo que no existe.")
        }
    }
}

/** Mirrors `checkedSavingsGoalProgress`: replayed in the DAO's (timestamp, id) order, no delta
 *  may be zero and the running progress may never go negative or overflow. */
private fun validateLedger(goalName: String, events: List<SavingsGoalAllocationEventBackup>) {
    events
        .sortedWith(compareBy({ it.timestampEpochMs }, { it.id }))
        .fold(Money.ZERO) { progress, event ->
            if (event.deltaMinor == 0L) invalid("La meta '$goalName' tiene una aportación de importe cero.")
            val updated = try {
                checkedSavingsGoalAdd(progress, Money(event.deltaMinor))
            } catch (_: SavingsGoalArithmeticOverflowException) {
                invalid("Las aportaciones de la meta '$goalName' desbordan el importe máximo.")
            }
            if (updated < Money.ZERO) invalid("El progreso de la meta '$goalName' queda negativo.")
            updated
        }
}

private fun requireUnique(label: String, keys: List<String>) {
    val duplicate = keys.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
    if (duplicate != null) invalid("Hay un $label duplicado: $duplicate.")
}
