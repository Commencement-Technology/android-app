package net.ivpn.core.rest.data.model

/*
 IVPN Android app
 https://github.com/ivpn/android-app

 Created by Juraj Hilje.
 Copyright (c) 2023 Privatus Limited.

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

import com.google.gson.Gson
import com.google.gson.annotations.Expose
import com.google.gson.annotations.SerializedName

class PortResponse {

    @SerializedName("type")
    @Expose
    var protocol: String? = null

    @SerializedName("port")
    @Expose
    var portNumber: Int? = null

    fun from(json: String): PortResponse {
        val gson = Gson()
        return gson.fromJson(json, PortResponse::class.java)
    }

    fun toJson(): String {
        return Gson().toJson(this)
    }

}

class Ports {

    @SerializedName("wireguard")
    @Expose
    lateinit var wireguard: List<PortResponse>

    @SerializedName("openvpn")
    @Expose
    lateinit var openvpn: List<PortResponse>

}