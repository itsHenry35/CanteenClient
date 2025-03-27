package com.itshenry.canteenclient.api.models

data class LoginResponse(
    val code: Int,
    val message: String,
    val data: LoginData?
)

data class LoginData(
    val token: String,
    val user: User
)

data class User(
    val id: Int,
    val username: String,
    val full_name: String,
    val role: String
)