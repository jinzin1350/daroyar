package com.daroyar.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** Tiny view helpers: big text, big buttons, right-to-left everywhere. */
object Ui {
    val PRIMARY = Color.parseColor("#00796B")
    val GREEN = Color.parseColor("#2E7D32")
    val RED = Color.parseColor("#C62828")
    val AMBER = Color.parseColor("#B26A00")
    val GRAY_BTN = Color.parseColor("#E3E7EB")
    val TEXT = Color.parseColor("#1B1D21")
    val MUTED = Color.parseColor("#5F6670")
    val CARD = Color.WHITE
    val LINE = Color.parseColor("#DDE2E7")
    val BG = Color.parseColor("#F3F5F7")

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(strokeWidth, stroke)
        }

    private fun lp(c: Context, top: Int = 10): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(c, top) }

    /** Sets up a page with a header bar and a scrolling column; returns the column. */
    fun page(a: Activity, title: String, back: Boolean = true): LinearLayout {
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BG)
        }
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PRIMARY)
            setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14))
        }
        if (back) {
            bar.addView(TextView(a).apply {
                text = "→"
                textSize = 26f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 0)
                setOnClickListener { a.finish() }
            }, LinearLayout.LayoutParams(dp(a, 40), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bar.addView(TextView(a).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 32))
        }
        val scroll = ScrollView(a).apply {
            isFillViewport = true
            addView(col)
        }
        root.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        a.setContentView(root)
        return col
    }

    fun text(
        c: Context,
        s: String,
        sp: Float = 18f,
        color: Int = TEXT,
        bold: Boolean = false,
        center: Boolean = false,
        top: Int = 8
    ): TextView = TextView(c).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = if (center) Gravity.CENTER else Gravity.START
        setLineSpacing(0f, 1.15f)
        layoutParams = lp(c, top)
    }

    fun button(
        c: Context,
        s: String,
        bg: Int = PRIMARY,
        fg: Int = Color.WHITE,
        sp: Float = 20f,
        height: Int = 60,
        onClick: () -> Unit
    ): Button = Button(c).apply {
        text = s
        textSize = sp
        isAllCaps = false
        setTextColor(fg)
        background = rounded(bg, dp(c, 14))
        stateListAnimator = null
        minHeight = dp(c, height)
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        layoutParams = lp(c, 10)
        setOnClickListener { onClick() }
    }

    /** Buttons side by side, equal width. */
    fun row(c: Context, vararg buttons: Button): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        layoutParams = lp(c, 10)
        buttons.forEachIndexed { i, b ->
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(c, 10)
            }
            addView(b)
        }
    }

    fun card(c: Context, border: Int = LINE): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        background = rounded(CARD, dp(c, 16), border, dp(c, 1))
        setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 14))
        layoutParams = lp(c, 12)
    }

    fun input(c: Context, hint: String, value: String = "", ltr: Boolean = false): EditText =
        EditText(c).apply {
            this.hint = hint
            setText(value)
            textSize = 20f
            setTextColor(TEXT)
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(Color.WHITE, dp(c, 12), LINE, dp(c, 1))
            setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12))
            if (ltr) {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
            }
            layoutParams = lp(c, 8)
        }

    /** Padded container for dialog contents. */
    fun box(c: Context, vararg views: View): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 4))
        views.forEach { addView(it) }
    }

    fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_LONG).show()

    fun confirm(a: Activity, title: String, msg: String, yes: String, onYes: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("انصراف", null)
            .show()
    }

    /** Runs [work] off the main thread, then [done] back on it. */
    fun <T> background(a: Activity, work: () -> T, done: (T) -> Unit) {
        Thread {
            val r = work()
            a.runOnUiThread { if (!a.isFinishing) done(r) }
        }.start()
    }
}
