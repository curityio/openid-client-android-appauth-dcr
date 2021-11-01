/*
 *  Copyright 2021 Curity AB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.curity.identityserver.dcrclient

import android.content.ContentValues
import android.content.SharedPreferences
import android.util.Log
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.RegistrationResponse
import net.openid.appauth.TokenResponse

/*
 * Wraps the AuthState class from the AppAuth library
 * Some or all of the auth state can be persisted to a secure location such as Encrypted Shared Preferences
 */
class ApplicationStateManager(private val sharedPreferences: SharedPreferences) {

    private var authState: AuthState? = null
    var idToken: String? = null
    var isFirstRun: Boolean = false

    /*
     * Load any existing state
     */
    init {

        // During development, when the database is recreated, this can be used to delete old registrations from shared preferences
        // deleteRegistration()

        val registration = this.sharedPreferences.getString("registration", null)
        if (registration != null) {
            val lastRegistrationResponse = RegistrationResponse.jsonDeserialize(registration)
            this.authState = AuthState(lastRegistrationResponse)
            Log.d(ContentValues.TAG, "Loaded dynamic client: ${lastRegistrationResponse.clientId}, Secret: ${lastRegistrationResponse.clientSecret}")

        }

        this.isFirstRun = registration == null
    }

    /*
     * Manage storing or updating the token response
     */
    fun saveTokens(tokenResponse: TokenResponse) {

        // When refreshing tokens, the Curity Identity Server does not issue a new ID token
        // The AppAuth code does not allow us to update the token response with the original ID token
        // Therefore we store the ID token separately
        if (tokenResponse.idToken != null) {
            this.idToken = tokenResponse.idToken
        }

        this.authState!!.update(tokenResponse, null)
    }

    /*
     * Clear tokens upon logout or when the session expires
     */
    fun clearTokens() {

        val metadata = this.authState?.authorizationServiceConfiguration
        val lastRegistrationResponse = this.authState?.lastRegistrationResponse
        this.authState = AuthState(metadata!!)
        this.authState!!.update(lastRegistrationResponse)
        this.idToken = null
    }

    /*
     * Registration data must be saved across application restarts
     */
    fun saveRegistration(registrationResponse: RegistrationResponse) {

        this.authState?.update(registrationResponse)
        this.sharedPreferences.edit()
            .putString("registration", this.authState!!.lastRegistrationResponse!!.jsonSerializeString())
            .apply()
    }

    private fun deleteRegistration() {

        this.sharedPreferences.edit()
            .remove("registration")
            .apply()
    }

    var metadata: AuthorizationServiceConfiguration?
        get () {
            return this.authState?.authorizationServiceConfiguration
        }
        set (configuration) {

            val lastRegistrationResponse = this.authState?.lastRegistrationResponse
            this.authState = AuthState(configuration!!)
            if (lastRegistrationResponse != null) {
                this.authState!!.update(lastRegistrationResponse)
            }
        }

    val registrationResponse: RegistrationResponse?
        get () {
            return this.authState?.lastRegistrationResponse
        }

    val tokenResponse: TokenResponse?
        get () {
            return this.authState?.lastTokenResponse
        }
}
