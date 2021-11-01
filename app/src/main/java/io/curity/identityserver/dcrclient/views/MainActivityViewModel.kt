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

package io.curity.identityserver.dcrclient.views

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import io.curity.identityserver.dcrclient.AppAuthHandler
import io.curity.identityserver.dcrclient.ApplicationStateManager
import io.curity.identityserver.dcrclient.configuration.ApplicationConfig
import io.curity.identityserver.dcrclient.configuration.ApplicationConfigLoader

class MainActivityViewModel(private val app: Application) : AndroidViewModel(app) {

    val config: ApplicationConfig = ApplicationConfigLoader().load(this.app.applicationContext)

    val appauth: AppAuthHandler = AppAuthHandler(this.config, this.app.applicationContext)

    val state = ApplicationStateManager(
        this.app.baseContext.getSharedPreferences("authState", Context.MODE_PRIVATE)
    )

    fun isRegistered(): Boolean {
        return this.state.registrationResponse != null;
    }

    fun dispose() {
        this.appauth.dispose()
    }
}