package com.sahabatmobile.chat

import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent

data class FoodApp(val label: String, val pkg: String)

object FoodApps {

    val KNOWN = listOf(
        FoodApp("Gojek", "com.gojekgo"),
        FoodApp("Grab", "com.grabtaxi.android"),
        FoodApp("ShopeeFood", "com.deliverynow"),
        FoodApp("foodpanda", "com.foodpanda.android")
    )

    fun installed(context: Context): List<FoodApp> {
        val pm = context.packageManager
        return KNOWN.filter { app ->
            try {
                pm.getApplicationInfo(app.pkg, 0).enabled
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    fun open(context: Context, app: FoodApp): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
