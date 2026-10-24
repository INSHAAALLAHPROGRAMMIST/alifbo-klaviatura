package uz.alifbo.klaviatura

import android.content.Context
import android.view.View

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

fun View.dp(v: Float): Float = context.dp(v)
