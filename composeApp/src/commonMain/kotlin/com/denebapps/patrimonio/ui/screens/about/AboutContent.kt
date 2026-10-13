package com.denebapps.patrimonio.ui.screens.about

private const val APP_NAME = "Patrimonio"
private const val APACHE_2 = "Apache License 2.0"
private const val MIT = "MIT License"
private const val OFL = "SIL Open Font License 1.1"

/** A provider the app queries: [covers] what it supplies and, in [note], what to keep in mind about it. */
data class DataSourceInfo(val name: String, val covers: String, val note: String, val url: String? = null)

/** An open-source component or font the app ships. [notice] is the copyright line when the license asks for it. */
data class LicenseInfo(val name: String, val license: String, val notice: String? = null)

/** A row of «Acerca de» that opens [uri] (a web page or a `mailto:`); [subtitle] is shown under the [title]. */
data class AboutLink(val title: String, val uri: String, val subtitle: String? = null)

/** Everything «Acerca de» shows. */
data class AboutContent(
    val appName: String,
    val versionName: String,
    val dataSources: List<DataSourceInfo>,
    val licenses: List<LicenseInfo>,
    /** Privacy policy and contact, only the ones that are configured. */
    val links: List<AboutLink>,
)

/**
 * The content of «Acerca de» for the app at [versionName]. [privacyPolicyUrl] and [contactEmail] default to the
 * constants of `AboutConfig.kt`; a null or blank one leaves its row out.
 */
fun aboutContent(
    versionName: String,
    privacyPolicyUrl: String? = PRIVACY_POLICY_URL,
    contactEmail: String? = CONTACT_EMAIL,
): AboutContent = AboutContent(
    appName = APP_NAME,
    versionName = versionName,
    dataSources = DATA_SOURCES,
    licenses = LICENSES,
    links = listOfNotNull(
        privacyPolicyUrl?.trim()?.takeIf(String::isNotEmpty)?.let {
            AboutLink(title = "Política de privacidad", uri = it)
        },
        contactEmail?.trim()?.takeIf(String::isNotEmpty)?.let {
            AboutLink(title = "Contacto", uri = "mailto:$it", subtitle = it)
        },
    ),
)

private val DATA_SOURCES = listOf(
    DataSourceInfo(
        name = "Morningstar",
        covers = "Fondos, ETF y acciones",
        note = "Fuente pública no oficial; los precios pueden retrasarse o fallar.",
    ),
    DataSourceInfo(
        name = "Yahoo Finance",
        covers = "Fondos, ETF y acciones",
        note = "Fuente pública no oficial; los precios pueden retrasarse o fallar.",
    ),
    DataSourceInfo(
        name = "CoinGecko",
        covers = "Criptomonedas",
        note = "Powered by CoinGecko.",
        url = "https://www.coingecko.com",
    ),
    DataSourceInfo(
        name = "Frankfurter",
        covers = "Tipos de cambio",
        note = "Datos del Banco Central Europeo (BCE).",
        url = "https://frankfurter.dev",
    ),
)

// Keep in step with gradle/libs.versions.toml: a new library that ships in the app belongs here.
private val LICENSES = listOf(
    LicenseInfo("Kotlin", APACHE_2),
    LicenseInfo("Compose Multiplatform", APACHE_2),
    LicenseInfo("AndroidX", APACHE_2),
    LicenseInfo("Room", APACHE_2),
    LicenseInfo("DataStore", APACHE_2),
    LicenseInfo("Koin", APACHE_2),
    LicenseInfo("Ktor", APACHE_2),
    LicenseInfo("kotlinx.coroutines", APACHE_2),
    LicenseInfo("kotlinx.serialization", APACHE_2),
    LicenseInfo("kotlinx-datetime", APACHE_2),
    LicenseInfo("cryptography-kotlin", APACHE_2),
    LicenseInfo("FileKit", MIT),
    LicenseInfo("Google Identity Services", "Android Software Development Kit License"),
    LicenseInfo(
        name = "rumbo",
        license = MIT,
        notice = "Copyright (c) 2026 Dani Dominguez Quant. La consulta de precios de Morningstar y Yahoo Finance " +
            "sigue su lógica (github.com/danidm98/rumbo).",
    ),
    LicenseInfo(
        name = "Manrope",
        license = OFL,
        notice = "Copyright 2019 The Manrope Project Authors (https://github.com/sharanda/manrope)",
    ),
    LicenseInfo(
        name = "Instrument Serif",
        license = OFL,
        notice = "Copyright 2022 The Instrument Serif Project Authors (https://github.com/Instrument/instrument-serif)",
    ),
)
