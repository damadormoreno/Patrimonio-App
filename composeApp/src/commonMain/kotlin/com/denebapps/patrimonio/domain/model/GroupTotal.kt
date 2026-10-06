package com.denebapps.patrimonio.domain.model

/** A group paired with its aggregated EUR total (e.g. one row of `assetsByGroup`). */
data class GroupTotal<G>(val group: G, val total: Money)
