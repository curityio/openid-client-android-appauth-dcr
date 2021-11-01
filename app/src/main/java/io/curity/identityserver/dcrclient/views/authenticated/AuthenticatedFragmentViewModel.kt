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

package io.curity.identityserver.dcrclient.views.authenticated;

import android.content.ContentValues
import android.content.Intent
import android.util.Log
import androidx.databinding.BaseObservable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import net.openid.appauth.TokenResponse
import org.jose4j.jwt.JwtClaims
import org.jose4j.jwt.consumer.InvalidJwtException
import org.jose4j.jwt.consumer.JwtConsumerBuilder
import io.curity.identityserver.dcrclient.AppAuthHandler
import io.curity.identityserver.dcrclient.ApplicationStateManager
import io.curity.identityserver.dcrclient.errors.ApplicationException
import io.curity.identityserver.dcrclient.errors.InvalidIdTokenException
import io.curity.identityserver.dcrclient.views.error.ErrorFragmentViewModel

class AuthenticatedFragmentViewModel(
    private val events: AuthenticatedFragmentEvents,
    private val state: ApplicationStateManager,
    private val appauth: AppAuthHandler,
    val error: ErrorFragmentViewModel) : BaseObservable() {

    var subject: String = ""
    var accessToken: String = ""
    var refreshToken: String = ""
    var hasRefreshToken = false
    var hasIdToken = false

    fun processTokens() {

        try {

            if (this.state.tokenResponse?.accessToken != null) {
                this.accessToken = this.state.tokenResponse?.accessToken!!
            }

            if (this.state.tokenResponse?.refreshToken != null) {
                this.refreshToken = this.state.tokenResponse?.refreshToken!!
                this.hasRefreshToken = true
            }

            if (this.state.idToken != null) {
                this.hasIdToken = true
                val jwtClaims = readIdTokenClaims(this.state.idToken!!)
                this.subject = jwtClaims.subject
            }

        } catch(ex: ApplicationException) {
            this.error.setDetails(ex)
        }

        notifyChange()
    }

    fun refreshAccessToken() {

        val metadata = this.state.metadata!!
        val registrationResponse = this.state.registrationResponse!!
        val refreshToken = this.state.tokenResponse!!.refreshToken!!
        var tokenResponse: TokenResponse?
        this.error.clearDetails()

        val that = this@AuthenticatedFragmentViewModel
        CoroutineScope(Dispatchers.IO).launch {

            try {

                tokenResponse = this@AuthenticatedFragmentViewModel.appauth.refreshAccessToken(
                    metadata,
                    registrationResponse.clientId,
                    registrationResponse.clientSecret!!,
                    refreshToken)

                withContext(Dispatchers.Main) {

                    if(tokenResponse != null) {
                        that.state.saveTokens(tokenResponse!!)
                        that.processTokens()
                    } else {
                        that.state.clearTokens()
                        events.onLoggedOut()
                    }
                }

            } catch (ex: ApplicationException) {

                withContext(Dispatchers.Main) {
                    error.setDetails(ex)
                }
            }
        }
    }

    fun startLogout() {

        this.error.clearDetails()

        val intent = appauth.getEndSessionRedirectIntent(
            this.state.metadata!!,
            this.state.idToken)

        this.events.startLogoutRedirect(intent)
    }

    fun endLogout(data: Intent) {

        try {
            this.appauth.handleEndSessionResponse(AuthorizationException.fromIntent(data))
            this.state.clearTokens()
            this.events.onLoggedOut()

        } catch (ex: ApplicationException) {
            this.error.setDetails(ex)
        }
    }

    private fun readIdTokenClaims(idToken: String): JwtClaims {

        val jwtConsumer = JwtConsumerBuilder()
            .setSkipSignatureVerification()
            .setRequireSubject()
            .setAllowedClockSkewInSeconds(30)
            .setExpectedIssuer(this.state.metadata?.discoveryDoc?.issuer)
            .setExpectedAudience(this.state.registrationResponse?.clientId)
            .build()

        try {

            return jwtConsumer.processToClaims(idToken)

        } catch (e: InvalidJwtException) {

            Log.e(ContentValues.TAG, "${e.message}")
            throw InvalidIdTokenException("Failed to parse ID Token")
        }
    }
}
