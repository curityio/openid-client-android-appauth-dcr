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

package io.curity.identityserver.dcrclient.views.registration;

import android.content.Intent
import androidx.databinding.BaseObservable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.RegistrationResponse
import io.curity.identityserver.dcrclient.AppAuthHandler
import io.curity.identityserver.dcrclient.ApplicationStateManager
import io.curity.identityserver.dcrclient.configuration.ApplicationConfig
import io.curity.identityserver.dcrclient.errors.ApplicationException
import io.curity.identityserver.dcrclient.views.error.ErrorFragmentViewModel

class RegistrationFragmentViewModel(
    private val events: RegistrationFragmentEvents,
    private val config: ApplicationConfig,
    private val state: ApplicationStateManager,
    private val appauth: AppAuthHandler,
    val error: ErrorFragmentViewModel) : BaseObservable() {

    /*
     * Start an initial redirect with the dcr scope, to get the DCR access token
     */
    fun startLogin() {

        this.error.clearDetails()
        var metadata = this.state.metadata

        val that = this@RegistrationFragmentViewModel
        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Look up metadata if required
                if (metadata == null) {
                    metadata = appauth.fetchMetadata()
                }

                // Switch back to the UI thread for the redirect
                withContext(Dispatchers.Main) {

                    that.state.metadata = metadata
                    val intent = appauth.getAuthorizationRedirectIntent(
                        metadata!!,
                        that.config.registrationClientID,
                        "dcr",
                        true
                    )

                    that.events.startLoginRedirect(intent)
                }

            } catch (ex: ApplicationException) {

                withContext(Dispatchers.Main) {
                    error.setDetails(ex)
                }
            }
        }
    }

    /*
     * Redeem the code for the DCR access token and then register the dynamic client
     */
    fun endLogin(data: Intent) {

        try {

            val authorizationResponse = appauth.handleAuthorizationResponse(
                AuthorizationResponse.fromIntent(data),
                AuthorizationException.fromIntent(data))

            val metadata = this.state.metadata!!
            var dcrAccessToken: String?
            var registrationResponse: RegistrationResponse?

            val that = this@RegistrationFragmentViewModel
            CoroutineScope(Dispatchers.IO).launch {
                try {

                    // Swap the code for the DCR access token
                    val tokenResponse = appauth.redeemCodeForTokens(
                        null,
                        authorizationResponse
                    )
                    dcrAccessToken = tokenResponse?.accessToken

                    // Securely register the client
                    registrationResponse = appauth.registerClient(metadata, dcrAccessToken!!)

                    // Update application state
                    withContext(Dispatchers.Main) {
                        that.state.metadata = metadata
                        that.state.saveRegistration(registrationResponse!!)
                        events.onRegistered()
                    }

                } catch (ex: ApplicationException) {

                    withContext(Dispatchers.Main) {
                        error.setDetails(ex)
                    }
                }
            }

        } catch (ex: ApplicationException) {
            error.setDetails(ex)
        }
    }
}
