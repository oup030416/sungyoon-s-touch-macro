package com.sungyoon.helper.overlay.pointer

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

object PointerOverlayDrawables {

    fun cardBg(dp: (Int) -> Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#CC121212"))
            cornerRadius = dp(18).toFloat()
        }
    }
    fun reservationCardBg(dp: (Int) -> Int): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#FF121212"))
            cornerRadius = dp(18).toFloat()
        }
    }

    fun roundedRippleBg(
        fillColor: Int,
        rippleColor: Int,
        dp: (Int) -> Int,
        radiusDp: Int
    ): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadius = dp(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    fun circleRippleBg(baseColor: Int, rippleColor: Int): Drawable {
        val content = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(baseColor)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)
    }

    fun deleteZoneBg(dp: (Int) -> Int, normal: Boolean): Drawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            if (normal) {
                setColor(Color.parseColor("#99111111"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            } else {
                setColor(Color.parseColor("#B8292A2A"))
                setStroke(dp(2), Color.parseColor("#FF5B5CE6"))
            }
        }
    }
}
