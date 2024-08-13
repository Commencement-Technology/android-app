package net.ivpn.core.v2.viewmodel

/*
 IVPN Android app
 https://github.com/ivpn/android-app
 
 Created by Oleksandr Mykhailenko.
 Copyright (c) 2023 IVPN Limited.
 
 This file is part of the IVPN Android app.
 
 The IVPN Android app is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 3 of the License, or (at your option) any later version.
 
 The IVPN Android app is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 details.
 
 You should have received a copy of the GNU General Public License
 along with the IVPN Android app. If not, see <https://www.gnu.org/licenses/>.
*/

import android.content.Context
import android.net.Uri
import android.widget.CompoundButton
import androidx.databinding.ObservableBoolean
import androidx.lifecycle.ViewModel
import net.ivpn.core.IVPNApplication
import net.ivpn.core.common.dagger.ApplicationScope
import net.ivpn.core.common.logger.FileUtils
import net.ivpn.core.common.utils.LogUtil
import javax.inject.Inject

@ApplicationScope
class LoggingViewModel @Inject constructor(
        private val logUtil: LogUtil
) : ViewModel()  {

    val isLoggingEnabled = ObservableBoolean()

    var enableLoggingListener = CompoundButton.OnCheckedChangeListener { _: CompoundButton?, value: Boolean -> enableLogging(value) }

    init {
    }

    fun onResume() {
        isLoggingEnabled.set(getLoggingValue())
    }

    fun getLogFileUri(context: Context?): Uri {
        return FileUtils.createLogFileUri(context)
    }

    fun reset() {
        isLoggingEnabled.set(getLoggingValue())
    }

    private fun getLoggingValue(): Boolean {
        return logUtil.isLoggingEnabled
    }

    private fun enableLogging(value: Boolean) {
        isLoggingEnabled.set(value)
        logUtil.enableLogging(value)
    }
}