/*
 * Licensed under Apache-2.0
 *
 * Designed and developed by Aidan Follestad (@afollestad)
 */
package com.afollestad.aesthetic.views

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Menu
import android.view.View
import androidx.appcompat.view.menu.ActionMenuItemView
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.afollestad.aesthetic.ActiveInactiveColors
import com.afollestad.aesthetic.Aesthetic
import com.afollestad.aesthetic.Aesthetic.Companion.get
import com.afollestad.aesthetic.utils.TintHelper.createTintedDrawable
import com.afollestad.aesthetic.utils.adjustAlpha
import com.afollestad.aesthetic.utils.blendWith
import com.afollestad.aesthetic.utils.distinctToMainThread
import com.afollestad.aesthetic.utils.isColorLight
import com.afollestad.aesthetic.utils.setOverflowButtonColor
import com.afollestad.aesthetic.utils.subscribeTo
import com.afollestad.aesthetic.utils.tintMenu
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.CollapsingToolbarLayout
import io.reactivex.Observable.combineLatest
import io.reactivex.disposables.Disposable
import io.reactivex.functions.BiFunction

private typealias ToolbarIconTitleFunc =
    BiFunction<Int, ActiveInactiveColors, Pair<Int, ActiveInactiveColors>>

/** @author Aidan Follestad (afollestad) */
class AestheticCoordinatorLayout(
  context: Context,
  attrs: AttributeSet? = null
) : CoordinatorLayout(context, attrs), AppBarLayout.OnOffsetChangedListener {

  private var toolbarColorSubscription: Disposable? = null
  private var statusBarColorSubscription: Disposable? = null
  private var appBarLayout: AppBarLayout? = null
  private var colorView: View? = null
  private var toolbar: AestheticToolbar? = null
  private var collapsingToolbarLayout: CollapsingToolbarLayout? = null

  private var toolbarColor: Int = 0
  private var iconTextColors: ActiveInactiveColors? = null
  private var lastOffset = -1

  private fun tintMenu(
    toolbar: AestheticToolbar,
    menu: Menu?,
    colors: ActiveInactiveColors
  ) {
    if (toolbar.navigationIcon != null) {
      toolbar.setNavigationIcon(toolbar.navigationIcon, colors.activeColor)
    }
    toolbar.setOverflowButtonColor(colors.activeColor)

    try {
      val field = Toolbar::class.java.getDeclaredField("mCollapseIcon")
      field.isAccessible = true
      val collapseIcon = field.get(toolbar) as? Drawable
      if (collapseIcon != null) {
        field.set(toolbar, createTintedDrawable(collapseIcon, colors.toEnabledSl()))
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }

    val colorFilter = PorterDuffColorFilter(colors.activeColor, PorterDuff.Mode.SRC_IN)
    for (i in 0 until toolbar.childCount) {
      val v = toolbar.getChildAt(i)
      // We can't iterate through the toolbar.getMenu() here, because we need the
      // ActionMenuItemView.
      if (v is ActionMenuView) {
        for (j in 0 until v.childCount) {
          val innerView = v.getChildAt(j)
          if (innerView is ActionMenuItemView) {
            val drawablesCount = innerView.compoundDrawables.size
            for (k in 0 until drawablesCount) {
              if (innerView.compoundDrawables[k] != null) {
                innerView
                    .compoundDrawables[k].colorFilter = colorFilter
              }
            }
          }
        }
      }
    }
    toolbar.tintMenu(menu ?: toolbar.menu, colors)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    // Find the toolbar and color view used to blend the scroll transition
    if (childCount > 0 && getChildAt(0) is AppBarLayout) {
      appBarLayout = getChildAt(0) as AppBarLayout
      if (appBarLayout!!.childCount > 0 && appBarLayout!!.getChildAt(
              0
          ) is CollapsingToolbarLayout
      ) {
        collapsingToolbarLayout = appBarLayout!!.getChildAt(0) as CollapsingToolbarLayout
        for (i in 0 until collapsingToolbarLayout!!.childCount) {
          if (this.toolbar != null && this.colorView != null) {
            break
          }
          val child = collapsingToolbarLayout!!.getChildAt(i)
          if (child is AestheticToolbar) {
            this.toolbar = child
          } else if (child.background != null && child.background is ColorDrawable) {
            this.colorView = child
          }
        }
      }
    }

    if (toolbar != null && colorView != null) {
      this.appBarLayout?.addOnOffsetChangedListener(this)

      toolbarColorSubscription =
          combineLatest<Int, ActiveInactiveColors, Pair<Int, ActiveInactiveColors>>(
              toolbar!!.colorUpdated(),
              Aesthetic.get().colorIconTitle(toolbar!!.colorUpdated()),
              ToolbarIconTitleFunc { a, b -> Pair(a, b) }
          )
              .distinctToMainThread()
              .subscribeTo {
                toolbarColor = it.first
                iconTextColors = it.second
                invalidateColors()
              }
    }

    if (collapsingToolbarLayout != null) {
      statusBarColorSubscription = get()
          .colorStatusBar()
          .distinctToMainThread()
          .subscribeTo {
            collapsingToolbarLayout?.apply {
              setContentScrimColor(it)
              setStatusBarScrimColor(it)
            }
          }
    }
  }

  override fun onDetachedFromWindow() {
    toolbarColorSubscription?.dispose()
    statusBarColorSubscription?.dispose()
    this.appBarLayout?.removeOnOffsetChangedListener(this)
    this.appBarLayout = null
    this.toolbar = null
    this.colorView = null
    super.onDetachedFromWindow()
  }

  override fun onOffsetChanged(
    appBarLayout: AppBarLayout,
    verticalOffset: Int
  ) {
    if (lastOffset == Math.abs(verticalOffset)) return
    lastOffset = Math.abs(verticalOffset)
    invalidateColors()
  }

  private fun invalidateColors() {
    if (iconTextColors == null) {
      return
    }

    val maxOffset = appBarLayout!!.measuredHeight - toolbar!!.measuredHeight
    val ratio = lastOffset.toFloat() / maxOffset.toFloat()

    val colorViewColor = (colorView!!.background as ColorDrawable).color
    val blendedColor = colorViewColor.blendWith(toolbarColor, ratio)
    val collapsedTitleColor = iconTextColors!!.activeColor
    val expandedTitleColor = if (colorViewColor.isColorLight()) Color.BLACK else Color.WHITE
    val blendedTitleColor = expandedTitleColor.blendWith(collapsedTitleColor, ratio)

    toolbar?.apply {
      setBackgroundColor(blendedColor)
      tintMenu(
          this,
          menu,
          ActiveInactiveColors(
              blendedTitleColor,
              blendedColor.adjustAlpha(0.7f)
          )
      )
    }

    collapsingToolbarLayout?.apply {
      setCollapsedTitleTextColor(collapsedTitleColor)
      setExpandedTitleColor(expandedTitleColor)
    }
  }
}
