package com.itshenry.canteenclient.api.models

data class ScanResponse(
    val code: Int,
    val message: String,
    val data: ScanData?
)

data class ScanData(
    val student_id: Int,
    val student_name: String,
    val has_selected: Boolean,
    val meal_type: String,
    val has_collected: Boolean
)