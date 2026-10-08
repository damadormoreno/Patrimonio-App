package com.denebapps.patrimonio.data.auth

/**
 * Firebase project the optional account and cloud backup talk to (REST, so Android and iOS share all
 * the code and no Firebase SDK is linked). The API key only identifies the project, it is not a secret:
 * access is granted by the signed-in user's ID token and the Firestore security rules.
 */
internal object FirebaseConfig {
    const val API_KEY = "AIzaSyAVw1WsvNuuNM9LQaCjKYmXHYRuPoeUQnE"
    const val PROJECT_ID = "patrimonio-116ff"

    /** The "Web client" OAuth ID Firebase creates when the Google provider is enabled; Google ID tokens must be
     *  issued for it. Blank: Sign in with Google is not offered. Not a secret either. */
    const val GOOGLE_WEB_CLIENT_ID = "301814694875-g5og1somdueurc9gqg69rp4ltuc1iftl.apps.googleusercontent.com"
}
