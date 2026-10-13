package com.denebapps.patrimonio.ui.screens.about

/**
 * Where the privacy policy is published. `null` while it has no public URL: «Acerca de» hides its row until this is
 * set (the stores ask for the same URL in the listing, so fill both together; see `docs/privacy-policy.md`).
 */
val PRIVACY_POLICY_URL: String? = null

/** Contact email shown in «Acerca de» (opens the mail app). `null` hides the row. */
val CONTACT_EMAIL: String? = null
