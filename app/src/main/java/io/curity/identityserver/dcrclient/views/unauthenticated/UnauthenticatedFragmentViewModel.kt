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

package io.curity.identityserver.dcrclient.views.unauthenticated;

import android.content.Intent
import androidx.databinding.BaseObservable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.TokenResponse
import io.curity.identityserver.dcrclient.AppAuthHandler
import io.curity.identityserver.dcrclient.ApplicationStateManager
import io.curity.identityserver.dcrclient.configuration.ApplicationConfig
import io.curity.identityserver.dcrclient.errors.ApplicationException
import io.curity.identityserver.dcrclient.views.error.ErrorFragmentViewModel

class UnauthenticatedFragmentViewModel(
    private val events: UnauthenticatedFragmentEvents,
    private val config: ApplicationConfig,
    private val state: ApplicationStateManager,
    private val appauth: AppAuthHandler,
    val error: ErrorFragmentViewModel) : BaseObservable() {

    /*
     * Build the authorization redirect URL with the app's scope and then ask the view to redirect
     */
    fun startLogin() {

        this.error.clearDetails()
        var metadata = this.state.metadata
        val registrationResponse = this.state.registrationResponse

        val that = this@UnauthenticatedFragmentViewModel
        CoroutineScope(Dispatchers.IO).launch {
            try {

                // Look up metadata on a worker thread
                if (metadata == null) {
                    metadata = appauth.fetchMetadata()
                }

                // Switch back to the UI thread for the redirect
                withContext(Dispatchers.Main) {

                    that.state.metadata = metadata
                    val intent = appauth.getAuthorizationRedirectIntent(
                        metadata!!,
                        registrationResponse!!.clientId,
                        config.scope,
                        that.isForcedLogin()
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
     * Redeem the code for tokens and also handle failures or the user cancelling the Chrome Custom Tab
     * Make HTTP requests on a worker thread and then perform updates on the UI thread
     */
    fun endLogin(data: Intent) {

        try {

            val registrationResponse = this.state.registrationResponse
            var tokenResponse: TokenResponse?

            val authorizationResponse = appauth.handleAuthorizationResponse(
                AuthorizationResponse.fromIntent(data),
                AuthorizationException.fromIntent(data))

            val that = this@UnauthenticatedFragmentViewModel
            CoroutineScope(Dispatchers.IO).launch {
                try {

                    // Swap the code for tokens
                    tokenResponse = appauth.redeemCodeForTokens(
                        registrationResponse!!.clientSecret,
                        authorizationResponse
                    )

                    // Update application state
                    withContext(Dispatchers.Main) {
                        that.state.isFirstRun = false
                        that.state.saveTokens(tokenResponse!!)
                        events.onLoggedIn()
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

    private fun isForcedLogin(): Boolean {

        // On the first run the user must perform an initial login to register, then must single sign on to authenticate
        if (this.state.isFirstRun) {
            return false
        }

        // Demonstrate an approach if cookies become stuck in the in app browser window
        // Our force login logic will run it the user is logged out, which is true when there is no ID token
        // https://github.com/openid/AppAuth-iOS/issues/542
        if (this.state.idToken == null) {
            return true
        }

        return false
    }
}
