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
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import net.openid.appauth.*
import net.openid.appauth.AuthorizationServiceConfiguration.fetchFromIssuer
import io.curity.identityserver.dcrclient.configuration.ApplicationConfig
import io.curity.identityserver.dcrclient.errors.ApplicationException
import io.curity.identityserver.dcrclient.errors.GENERIC_ERROR
import io.curity.identityserver.dcrclient.errors.ServerCommunicationException
import io.curity.identityserver.dcrclient.utilities.HttpHelper

/*
 * Manage AppAuth integration in one class in order to reduce code in the rest of the app
 */
class AppAuthHandler(private val config: ApplicationConfig, val context: Context) {

    private var authService = AuthorizationService(context)

    /*
     * Get OpenID Connect endpoints and ensure that dynamic client registration is configured
     */
    suspend fun fetchMetadata(): AuthorizationServiceConfiguration {

        return suspendCoroutine { continuation ->

            fetchFromIssuer(config.getIssuerUri()) { metadata, ex ->

                when {
                    metadata != null -> {
                        if (metadata.registrationEndpoint == null) {
                            val error = ApplicationException(
                                "Invalid Configuration Error",
                                "No registration endpoint is configured in the Identity Server"
                            )
                            continuation.resumeWithException(error)
                        }

                        Log.i(ContentValues.TAG, "Metadata retrieved successfully")
                        continuation.resume(metadata)
                    }
                    else -> {
                        val error = createAuthorizationError("Metadata Download Error", ex)
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    /*
     * Trigger a redirect with standard parameters
     * acr_values can be sent as an extra parameter, to control authentication methods
     */
    fun getAuthorizationRedirectIntent(
        metadata: AuthorizationServiceConfiguration,
        clientID: String,
        scope: String,
        force: Boolean): Intent {

        // Use acr_values to select a particular authentication method at runtime
        val extraParams = mutableMapOf<String, String>()
        //extraParams.put("acr_values", "urn:se:curity:authentication:html-form:Username-Password")

        val requestBuilder = AuthorizationRequest.Builder(
            metadata,
            clientID,
            ResponseTypeValues.CODE,
            config.getRedirectUri())
            .setScopes(scope)
            .setAdditionalParameters(extraParams)
        if (force) {
            requestBuilder.setPrompt("login")
        }
        val request = requestBuilder.build()

        return authService.getAuthorizationRequestIntent(request)
    }

    /*
     * Handle the authorization response, including the user closing the Chrome Custom Tab
     */
    fun handleAuthorizationResponse(
        response: AuthorizationResponse?,
        ex: AuthorizationException?): AuthorizationResponse {

        if (response == null) {
            throw createAuthorizationError("Authorization Request Error", ex)
        }

        Log.i(ContentValues.TAG, "Authorization response received successfully")
        return response
    }

    /*
     * Handle the authorization code grant request to get tokens
     */
    suspend fun redeemCodeForTokens(
        clientSecret: String?,
        authResponse: AuthorizationResponse): TokenResponse? {

        return suspendCoroutine { continuation ->

            val extraParams = mutableMapOf<String, String>()
            if (clientSecret != null) {
                extraParams["client_secret"] = clientSecret
            }
            val tokenRequest = authResponse.createTokenExchangeRequest(extraParams)

            authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->

                when {
                    tokenResponse != null -> {
                        Log.i(ContentValues.TAG, "Authorization code grant response received successfully")
                        continuation.resume(tokenResponse)
                    }
                    else -> {
                        val error = createAuthorizationError("Authorization Response Error", ex)
                        continuation.resumeWithException(error)
                    }
                }
            }
        }
    }

    /*
     * Perform dynamic client registration and then store the response
     */
    suspend fun registerClient(
        metadata: AuthorizationServiceConfiguration,
        dcrAccessToken: String): RegistrationResponse {

        val extraParams = mutableMapOf<String, String>()
        extraParams["scope"] = config.scope
        extraParams["requires_consent"] = "false"
        extraParams["post_logout_redirect_uris"] = config.postLogoutRedirectUri.toString()

        val nonTemplatizedRequest =
            RegistrationRequest.Builder(
                metadata,
                listOf(config.getRedirectUri())
            )
                .setGrantTypeValues(listOf(GrantTypeValues.AUTHORIZATION_CODE))
                .setAdditionalParameters(extraParams)
                .build()

        try {

            // We send this request ourselves in order to include the DCR access token
            val responseData = HttpHelper().postWithAccessToken(
                nonTemplatizedRequest.configuration.registrationEndpoint.toString(),
                nonTemplatizedRequest.toJsonString(),
                dcrAccessToken)

            val registrationResponse = RegistrationResponse.Builder(nonTemplatizedRequest)
                .fromResponseJsonString(responseData).build();

            Log.i(ContentValues.TAG, "Registration data retrieved successfully")
            Log.d(ContentValues.TAG, "Created dynamic client: ID: ${registrationResponse.clientId}, Secret: ${registrationResponse.clientSecret}")
            return registrationResponse

        } catch (ex: Exception) {

            throw createAuthorizationError(
                "Registration Error",
                AuthorizationException.fromTemplate(AuthorizationException.GeneralErrors.NETWORK_ERROR, ex))
        }
    }

    /*
     * Try to refresh an access token and return null when the refresh token expires
     */
    suspend fun refreshAccessToken(
        metadata: AuthorizationServiceConfiguration,
        clientID: String,
        clientSecret: String,
        refreshToken: String): TokenResponse? {

        return suspendCoroutine { continuation ->

            val extraParams = mapOf("client_secret" to clientSecret)
            val tokenRequest = TokenRequest.Builder(metadata, clientID)
                .setGrantType(GrantTypeValues.REFRESH_TOKEN)
                .setRefreshToken(refreshToken)
                .setAdditionalParameters(extraParams)
                .build()

            authService.performTokenRequest(tokenRequest) { tokenResponse, ex ->

                when {
                    tokenResponse != null -> {
                        Log.i(ContentValues.TAG, "Refresh token grant response received successfully")
                        continuation.resume(tokenResponse)
                    }
                    else -> {

                        if (ex != null &&
                            ex.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                            ex.code == AuthorizationException.TokenRequestErrors.INVALID_GRANT.code
                        ) {
                            Log.i(ContentValues.TAG, "Refresh token expired and the user must re-authenticate")
                            continuation.resume(null)

                        } else {

                            val error = createAuthorizationError("Token Refresh Error", ex)
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        }
    }

    /*
     * Do an OpenID Connect end session redirect and remove the SSO cookie
     */
    fun getEndSessionRedirectIntent(metadata: AuthorizationServiceConfiguration,
                                    idToken: String?): Intent {

        val extraParams = mutableMapOf<String, String>()
        val request = EndSessionRequest.Builder(metadata)
            .setIdTokenHint(idToken)
            .setPostLogoutRedirectUri(config.getPostLogoutRedirectUri())
            .setAdditionalParameters(extraParams)
            .build()

        return authService.getEndSessionRequestIntent(request)
    }

    /*
     * Finalize after receiving an end session response
     */
    fun handleEndSessionResponse(ex: AuthorizationException?) {

        when {
            ex != null -> {
                throw createAuthorizationError("End Session Request Error", ex)
            }
        }
    }

    /*
     * Clean up AppAuth resources on exit
     */
    fun dispose() {
        this.authService.dispose()
    }

    /*
     * Process standard OAuth error / error_description fields and also AppAuth error identifiers
     */
    private fun createAuthorizationError(title: String, ex: AuthorizationException?): ServerCommunicationException {

        val parts = mutableListOf<String>()

        if (ex?.type != null) {
            parts.add("(${ex.type} / ${ex.code})")
        }

        if (ex?.error != null) {
            parts.add(ex.error!!)
        }

        val description: String = if (ex?.errorDescription != null) {
            ex.errorDescription!!
        } else {
            GENERIC_ERROR
        }
        parts.add(description)

        val fullDescription = parts.joinToString(" : ")
        Log.e(ContentValues.TAG, fullDescription)
        return ServerCommunicationException(title, fullDescription)
    }
}