package com.denebapps.patrimonio.data.backup

import com.denebapps.patrimonio.data.db.entity.HoldingKind
import com.denebapps.patrimonio.data.repository.movementToDomain
import com.denebapps.patrimonio.domain.calc.TargetsError
import com.denebapps.patrimonio.domain.calc.checkedSavingsGoalAdd
import com.denebapps.patrimonio.domain.calc.movementValidationError
import com.denebapps.patrimonio.domain.calc.validateTargets
import com.denebapps.patrimonio.domain.model.AccountKind
import com.denebapps.patrimonio.domain.model.Asset
import com.denebapps.patrimonio.domain.model.AssetClass
import com.denebapps.patrimonio.domain.model.Currency
import com.denebapps.patrimonio.domain.model.DEFAULT_PORTFOLIO_NAME
import com.denebapps.patrimonio.domain.model.InstrumentType
import com.denebapps.patrimonio.domain.model.Liability
import com.denebapps.patrimonio.domain.model.MAX_BROKER_LENGTH
import com.denebapps.patrimonio.domain.model.MAX_FUNDAMENTALS_TEXT_LENGTH
import com.denebapps.patrimonio.domain.model.MAX_PORTFOLIO_NAME_LENGTH
import com.denebapps.patrimonio.domain.model.MAX_RISK_LEVEL
import com.denebapps.patrimonio.domain.model.MIN_RISK_LEVEL
import com.denebapps.patrimonio.domain.model.Money
import com.denebapps.patrimonio.domain.model.MovementType
import com.denebapps.patrimonio.domain.model.QuoteProvider
import com.denebapps.patrimonio.domain.model.SavingsGoalLifecycle
import com.denebapps.patrimonio.domain.model.SavingsGoalLinkEventKind
import com.denebapps.patrimonio.domain.model.TypeColor
import com.denebapps.patrimonio.domain.repository.InvalidBackupException
import com.denebapps.patrimonio.domain.repository.MAX_MOVEMENT_NOTE_LENGTH
import com.denebapps.patrimonio.domain.repository.MAX_REBALANCE_TOLERANCE_BP
import com.denebapps.patrimonio.domain.repository.SavingsGoalArithmeticOverflowException
import kotlinx.datetime.LocalDate
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
 * Writes [VERSION] (15). Reads every version from 1 up to [VERSION]: a v1 file simply lacks the
 * group-link fields, which default to null, v1–v3 files have numeric goal ids, which
 * [upgradeLegacyGoalIds] replaces before decoding, v1–v4 files link a goal to one `linkedAssetId`,
 * which [upgradeSingleLinkedAsset] turns into a list, v1–v5 files have no custom account types nor
 * emojis, which default to empty, v3–v6 files may carry a `subscriptions` section from the removed
 * subscriptions feature, which is ignored like any unknown key, v1–v7 files have no portfolio holdings, which
 * default to empty, v1–v8 holdings have no bank or broker, which defaults to null, v1–v9 holdings have no
 * fundamentals (cost, risk, category, manager), which default to null, and v1–v10 files have no portfolios, which
 * [upgradeToSinglePortfolio] answers with one new "Mi cartera" that holds every holding, and v1–v11 holdings have no
 * asset class, which defaults to null (the class is inferred when read), and v1–v12 files have no movements, which
 * default to empty, and v1–v13 files have no allocation targets, which default to empty, nor portfolio rebalance
 * tolerances, which default to 500, and v1–v14 files have no holding members of groups, which default to empty.
 */
object BackupCodec {
    const val FORMAT = "patrimonio-backup"
    const val VERSION = 15

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
        val withLinkedAssets = if (version < 5) upgradeSingleLinkedAsset(withGoalIds) else withGoalIds
        val upgraded = if (version < 11) upgradeToSinglePortfolio(withLinkedAssets) else withLinkedAssets
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
        val newId = randomUuid().also { newIds[oldId.content] = it }
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

/**
 * Up to version 10 there were no portfolios. Like the 10 -> 11 database migration, the file gets one "Mi cartera" with
 * a fresh UUID, even without holdings, and every holding is put in it.
 */
private fun upgradeToSinglePortfolio(root: JsonObject): JsonObject {
    val portfolioId = JsonPrimitive(randomUuid())
    val portfolio = JsonObject(
        mapOf(
            "id" to portfolioId,
            "name" to JsonPrimitive(DEFAULT_PORTFOLIO_NAME),
            "sortOrder" to JsonPrimitive(0),
        ),
    )
    val holdings = root.arrayOrEmpty("portfolioHoldings").map { element ->
        val holding = element as? JsonObject ?: return@map element
        JsonObject(holding + ("portfolioId" to portfolioId))
    }
    return JsonObject(
        root + mapOf("portfolios" to JsonArray(listOf(portfolio)), "portfolioHoldings" to JsonArray(holdings)),
    )
}

private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

@OptIn(ExperimentalUuidApi::class)
private fun randomUuid(): String = Uuid.random().toString()

private fun invalid(reason: String): Nothing = throw InvalidBackupException(reason)

private val ACCOUNT_KINDS = AccountKind.entries.map { it.name }.toSet()
private val TYPE_COLORS = TypeColor.entries.map { it.name }.toSet()
private val CURRENCIES = Currency.entries.map { it.name }.toSet()
private val LIFECYCLES = SavingsGoalLifecycle.entries.map { it.name }.toSet()
private val LINK_KINDS = SavingsGoalLinkEventKind.entries.map { it.name }.toSet()
private val INSTRUMENT_TYPES = InstrumentType.entries.map { it.name }.toSet()
private val QUOTE_PROVIDERS = QuoteProvider.entries.map { it.name }.toSet()
private val ASSET_CLASSES = AssetClass.entries.map { it.name }.toSet()
private val MOVEMENT_TYPES = MovementType.entries.map { it.name }.toSet()
private val YEAR_MONTH = Regex("""\d{4}-(0[1-9]|1[0-2])""")

private fun BackupDocument.validate() {
    requireUnique("tipo de cuenta", accountTypes.map { it.id })
    accountTypes.forEach { type ->
        if (type.id.isBlank()) invalid("Hay un tipo de cuenta sin id.")
        if (type.kind !in ACCOUNT_KINDS) invalid("El tipo '${type.name}' no es ni de activos ni de pasivos.")
        if (type.name.isBlank()) invalid("Hay un tipo de cuenta sin nombre.")
        if (type.emoji.isBlank()) invalid("El tipo '${type.name}' no tiene emoji.")
        if (type.color !in TYPE_COLORS) invalid("El tipo '${type.name}' tiene un color desconocido: ${type.color}.")
    }
    val customTypes = accountTypes.groupBy({ it.kind }, { it.id })
    val assetTypes = Asset.AssetGroup.entries.toSet() + customTypes[AccountKind.ASSET.name].orEmpty()
    val liabilityTypes = Liability.LiabilityGroup.entries.toSet() + customTypes[AccountKind.LIABILITY.name].orEmpty()

    requireUnique("activo", assets.map { it.id })
    assets.forEach { asset ->
        if (asset.id.isBlank()) invalid("Hay un activo sin id.")
        if (asset.group !in assetTypes) {
            invalid("El activo '${asset.name}' tiene un tipo desconocido: ${asset.group}.")
        }
        if (asset.currency !in CURRENCIES) {
            invalid("El activo '${asset.name}' tiene una divisa no soportada: ${asset.currency}.")
        }
    }

    requireUnique("pasivo", liabilities.map { it.id })
    liabilities.forEach { liability ->
        if (liability.id.isBlank()) invalid("Hay un pasivo sin id.")
        if (liability.group !in liabilityTypes) {
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

    validatePortfolios(portfolios)
    val portfolioIds = portfolios.map { it.id }.toSet()
    requireUnique("valor de cartera", portfolioHoldings.map { it.id })
    portfolioHoldings.forEach { validateHolding(it, portfolioIds) }
    validateMovements(movements, portfolioHoldings)
    validateAllocationTargets(allocationTargets, portfolios)

    val holdingIds = portfolioHoldings.map { it.id }.toSet()
    requireUnique("valor de cartera en un grupo", accountGroupHoldingMembers.map { "${it.groupId}/${it.holdingId}" })
    accountGroupHoldingMembers.forEach { member ->
        if (member.groupId !in groupIds) {
            invalid("Un valor de cartera apunta a un grupo que no existe: ${member.groupId}.")
        }
        if (member.holdingId !in holdingIds) {
            invalid("Un grupo incluye un valor de cartera que no existe: ${member.holdingId}.")
        }
    }
}

/**
 * Mirrors `AllocationTargetRepository`: every target is of a known portfolio and class, unique per (portfolio, class)
 * and above zero (a class without a target has no row), and the targets of a portfolio pass `validateTargets` (each
 * within 0 to 100 % and adding up to exactly 100 %), or the screens that read them would work from a broken target.
 */
private fun validateAllocationTargets(targets: List<AllocationTargetBackup>, portfolios: List<PortfolioBackup>) {
    val portfolioNames = portfolios.associate { it.id to it.name }
    targets.forEach { target ->
        if (target.portfolioId !in portfolioNames) {
            invalid("Un objetivo de reparto apunta a una cartera que no existe: ${target.portfolioId}.")
        }
        if (target.assetClass !in ASSET_CLASSES) {
            invalid("Un objetivo de reparto tiene una clase de activo desconocida: ${target.assetClass}.")
        }
    }
    requireUnique("objetivo de reparto", targets.map { "${it.portfolioId}/${it.assetClass}" })
    targets.forEach { target ->
        if (target.targetBp <= 0) {
            invalid(
                "El objetivo de ${target.assetClass} en la cartera '${portfolioNames.getValue(target.portfolioId)}' " +
                    "debe ser mayor que 0: una clase sin objetivo no se guarda.",
            )
        }
    }
    targets.groupBy { it.portfolioId }.forEach { (portfolioId, rows) ->
        val name = portfolioNames.getValue(portfolioId)
        val targetsBp = rows.associate { AssetClass.valueOf(it.assetClass) to it.targetBp }
        when (val error = validateTargets(targetsBp)) {
            null -> Unit
            is TargetsError.OutOfRange ->
                invalid("El objetivo de ${error.assetClass.name} en la cartera '$name' está fuera de 0 a 100 %.")
            is TargetsError.WrongSum ->
                invalid("Los objetivos de la cartera '$name' suman ${percentOf(error.totalBp)} %, no 100 %.")
        }
    }
}

/** Basis points as a percentage in Spanish notation: 9000 is "90", 9050 is "90,5", 9005 is "90,05". */
private fun percentOf(bp: Int): String {
    val decimals = (bp % 100).toString().padStart(2, '0').trimEnd('0')
    return if (decimals.isEmpty()) "${bp / 100}" else "${bp / 100},$decimals"
}

/**
 * Mirrors `Movement` and the ledger: every movement is rebuilt as the domain object it will be (its per-type
 * invariants) and the whole history is replayed, so a file that sells more than it holds, or has a half transfer, is
 * refused here and not half way through an import. Movements belong to quoted holdings, in their currency, and a
 * transfer is between funds. The units of the holdings are not checked: the import derives them from the movements.
 */
private fun validateMovements(movements: List<MovementBackup>, holdings: List<PortfolioHoldingBackup>) {
    movements.forEach { if (it.id.isBlank()) invalid("Hay un movimiento sin id.") }
    requireUnique("movimiento", movements.map { it.id })
    val holdingsById = holdings.associateBy { it.id }
    val history = movements.map { movement ->
        val holding = holdingsById[movement.holdingId]
            ?: invalid("Un movimiento apunta a un valor de cartera que no existe: ${movement.holdingId}.")
        val label = "El movimiento '${movement.id}' de '${holding.name}'"
        validateMovementFields(movement, holding, label)
        try {
            movementToDomain(movement.toEntity())
        } catch (_: IllegalArgumentException) {
            invalid("$label no tiene los datos que exige su tipo (${movement.type}).")
        }
    }
    history.filter { it.transferId != null }.forEach { leg ->
        if (holdingsById.getValue(leg.holdingId).type != InstrumentType.FUND.name) {
            invalid("El traspaso '${leg.transferId}' solo puede ser entre fondos de inversión.")
        }
    }
    movementValidationError(history)?.let { invalid(it.message.orEmpty()) }
}

private fun validateMovementFields(movement: MovementBackup, holding: PortfolioHoldingBackup, label: String) {
    if (holding.kind != HoldingKind.QUOTED.name) invalid("$label está en un valor de cartera sin precio de mercado.")
    if (movement.type !in MOVEMENT_TYPES) invalid("$label tiene un tipo desconocido: ${movement.type}.")
    if (!isCalendarDay(movement.epochDay)) invalid("$label tiene una fecha fuera de rango.")
    if ((movement.amountMinor == null) != (movement.currency == null)) {
        invalid("$label tiene un importe sin divisa o una divisa sin importe.")
    }
    if (movement.currency != null) {
        if (movement.currency !in CURRENCIES) invalid("$label tiene una divisa no soportada: ${movement.currency}.")
        if (movement.currency != holding.currency) invalid("$label tiene una divisa distinta a la de su valor.")
    }
    val note = movement.note
    if (note != null && (note.isBlank() || note != note.trim() || note.length > MAX_MOVEMENT_NOTE_LENGTH)) {
        invalid(
            "$label tiene una nota vacía, con espacios sobrantes o de más de $MAX_MOVEMENT_NOTE_LENGTH caracteres.",
        )
    }
}

/**
 * Mirrors `Portfolio` and the repository: unique ids and trimmed, non-blank names of at most 40 characters that are
 * unique ignoring case, or reading them would crash or break the rule.
 */
private fun validatePortfolios(portfolios: List<PortfolioBackup>) {
    portfolios.forEach { portfolio ->
        if (portfolio.id.isBlank()) invalid("Hay una cartera sin id.")
    }
    portfolios.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.let { duplicate ->
        invalid("Hay una cartera con el id duplicado: ${duplicate.key}.")
    }
    portfolios.forEach { portfolio ->
        val name = portfolio.name
        if (name.isBlank() || name != name.trim() || name.length > MAX_PORTFOLIO_NAME_LENGTH) {
            invalid(
                "Hay una cartera con un nombre vacío, con espacios sobrantes o de más de " +
                    "$MAX_PORTFOLIO_NAME_LENGTH caracteres.",
            )
        }
        if (portfolio.rebalanceToleranceBp !in 0..MAX_REBALANCE_TOLERANCE_BP) {
            invalid("La cartera '$name' tiene una tolerancia de reparto fuera de 0 a 100 %.")
        }
    }
    portfolios.groupBy { it.name.lowercase() }.values.firstOrNull { it.size > 1 }?.let { sameName ->
        invalid("Hay dos carteras con el mismo nombre: '${sameName.first().name}'.")
    }
}

/** A holding is either quoted (provider, units and maybe a price) or manual (a typed-in value), never a mix. */
private fun validateHolding(holding: PortfolioHoldingBackup, portfolioIds: Set<String>) {
    if (holding.id.isBlank()) invalid("Hay un valor de cartera sin id.")
    if (holding.name.isBlank()) invalid("Hay un valor de cartera sin nombre.")
    val label = "El valor de cartera '${holding.name}'"
    if (holding.portfolioId !in portfolioIds) {
        invalid("$label está en una cartera que no existe: ${holding.portfolioId}.")
    }
    if (holding.type !in INSTRUMENT_TYPES) invalid("$label tiene un tipo desconocido: ${holding.type}.")
    if (holding.currency !in CURRENCIES) invalid("$label tiene una divisa no soportada: ${holding.currency}.")
    if (holding.broker != null) validateBroker(holding.broker, label)
    if (holding.assetClass != null && holding.assetClass !in ASSET_CLASSES) {
        invalid("$label tiene una clase de activo desconocida: ${holding.assetClass}.")
    }
    validateFundamentals(holding, label)
    when (holding.kind) {
        HoldingKind.QUOTED.name -> validateQuotedHolding(holding, label)
        HoldingKind.MANUAL.name -> validateManualHolding(holding, label)
        else -> invalid("$label tiene una clase desconocida: ${holding.kind}.")
    }
}

/** Mirrors `normalizeBroker`: a stored broker is trimmed, not blank and within the cap, or reading it would crash. */
private fun validateBroker(broker: String, label: String) {
    if (broker.isBlank() || broker != broker.trim()) {
        invalid("$label tiene un banco o bróker vacío o con espacios sobrantes.")
    }
    if (broker.length > MAX_BROKER_LENGTH) {
        invalid("$label tiene un banco o bróker de más de $MAX_BROKER_LENGTH caracteres.")
    }
}

/** Mirrors `InstrumentFundamentals`: a stored value outside its range would crash the read that rebuilds it. */
private fun validateFundamentals(holding: PortfolioHoldingBackup, label: String) {
    if (holding.ongoingChargePercentScaled != null && holding.ongoingChargePercentScaled < 0) {
        invalid("$label tiene unos gastos corrientes negativos.")
    }
    if (holding.riskLevel != null && holding.riskLevel !in MIN_RISK_LEVEL..MAX_RISK_LEVEL) {
        invalid("$label tiene un nivel de riesgo fuera de $MIN_RISK_LEVEL a $MAX_RISK_LEVEL.")
    }
    if (holding.category != null && !isStoredFundamentalsText(holding.category)) {
        invalid(
            "$label tiene una categoría vacía, con espacios sobrantes o de más de " +
                "$MAX_FUNDAMENTALS_TEXT_LENGTH caracteres.",
        )
    }
    if (holding.managementCompany != null && !isStoredFundamentalsText(holding.managementCompany)) {
        invalid(
            "$label tiene una gestora vacía, con espacios sobrantes o de más de " +
                "$MAX_FUNDAMENTALS_TEXT_LENGTH caracteres.",
        )
    }
}

private fun isStoredFundamentalsText(text: String): Boolean =
    text.isNotBlank() && text == text.trim() && text.length <= MAX_FUNDAMENTALS_TEXT_LENGTH

private fun validateQuotedHolding(holding: PortfolioHoldingBackup, label: String) {
    if (holding.provider !in QUOTE_PROVIDERS) {
        invalid("$label tiene un proveedor de precios desconocido: ${holding.provider}.")
    }
    if (holding.providerCode.isNullOrBlank()) invalid("$label no tiene el código del instrumento.")
    if (holding.unitsScaled == null || holding.unitsScaled < 0) {
        invalid("$label no tiene un número de unidades válido.")
    }
    val priceFields = listOf(holding.priceScaled, holding.priceDateEpochDay, holding.priceFetchedAtEpochMs)
    if (priceFields.any { it == null } && priceFields.any { it != null }) {
        invalid("$label tiene un precio incompleto.")
    }
    if (holding.priceScaled != null && holding.priceScaled < 0) invalid("$label tiene un precio negativo.")
    if (holding.priceDateEpochDay != null && !isCalendarDay(holding.priceDateEpochDay)) {
        invalid("$label tiene una fecha de precio fuera de rango.")
    }
    if (holding.valueMinor != null || holding.valuedAtEpochMs != null) {
        invalid("$label mezcla datos de precio y de valor manual.")
    }
}

private fun validateManualHolding(holding: PortfolioHoldingBackup, label: String) {
    if (holding.valueMinor == null || holding.valueMinor < 0 || holding.valuedAtEpochMs == null) {
        invalid("$label no tiene un valor manual válido.")
    }
    val quoteFields = listOf(
        holding.provider,
        holding.providerCode,
        holding.unitsScaled,
        holding.priceScaled,
        holding.priceDateEpochDay,
        holding.priceFetchedAtEpochMs,
    )
    if (quoteFields.any { it != null }) invalid("$label mezcla datos de precio y de valor manual.")
}

/** Whether [epochDay] maps to a date `LocalDate` can represent (a corrupt value would crash a later read). */
private fun isCalendarDay(epochDay: Long): Boolean = epochDay.toInt().toLong() == epochDay &&
    try {
        LocalDate.fromEpochDays(epochDay.toInt())
        true
    } catch (_: IllegalArgumentException) {
        false
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
