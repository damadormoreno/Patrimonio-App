package com.denebapps.patrimonio.data.auth

/**
 * Firebase project the optional account and cloud backup talk to (REST, so Android and iOS share all
 * the code and no Firebase SDK is linked). The API key only identifies the project, it is not a secret:
 * access is granted by the signed-in user's ID token and the Firestore security rules.
 */
internal object FirebaseConfig {
    const val API_KEY = "AIzaSyAVw1WsvNuuNM9LQaCjKYmXHYRuPoeUQnE"
    const val PROJECT_ID = "patrimonio-116ff"
}
