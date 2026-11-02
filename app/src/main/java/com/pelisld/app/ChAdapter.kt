package com.pelisld.app

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChAdapter(
    private val onClick: (Ch) -> Unit,
    private val onLong: (Ch) -> Unit,
    private val big: Boolean = false   // true: letra y filas más grandes (lista flotante en pantalla completa)
) : RecyclerView.Adapter<ChAdapter.VH>() {

    class VH(val row: LinearLayout, val bar: View, val name: TextView, val tag: TextView, val star: ImageView) :
        RecyclerView.ViewHolder(row)

    private var items: List<Ch> = emptyList()
    private var currentId: String? = null
    private var favs: Set<String> = emptySet()
    private var down: Set<String> = emptySet()
    private val gold = Color.parseColor("#D4AF37")

    fun submit(list: List<Ch>, current: String?, favorites: Set<String>, downIds: Set<String> = emptySet()) {
        items = list
        currentId = current
        favs = favorites
        down = downIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = parent.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = px(5) }
            setPadding(px(12), px(if (big) 18 else 14), px(14), px(if (big) 18 else 14))
            setBackgroundResource(R.drawable.bg_item)
            isFocusable = true
            isClickable = true
        }
        val bar = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(4), px(if (big) 28 else 22)).also { it.marginEnd = px(12) } }
        val name = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = if (big) 20f else 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val tag = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = px(8) }
            textSize = if (big) 12f else 10f
            setTextColor(Color.parseColor("#8A8A99"))
            setBackgroundResource(R.drawable.bg_tag)
            setPadding(px(6), px(1), px(6), px(1))
        }
        val star = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(px(if (big) 20 else 16), px(if (big) 20 else 16)).also { it.marginStart = px(10) }
            setImageResource(R.drawable.ic_star)
            setColorFilter(gold)
        }
        row.addView(bar); row.addView(name); row.addView(tag); row.addView(star)
        return VH(row, bar, name, tag, star)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val ch = items[position]
        val cur = ch.id == currentId
        h.name.text = ch.name
        h.name.setTextColor(if (cur) gold else Color.WHITE)
        h.bar.setBackgroundColor(if (cur) gold else Color.TRANSPARENT)
        h.row.isActivated = cur
        if (ch.id in down) {
            h.tag.visibility = View.VISIBLE
            h.tag.text = "⚠ caído"
            h.tag.setTextColor(Color.parseColor("#E5645B"))
        } else {
            h.tag.visibility = if (ch.dup) View.VISIBLE else View.GONE
            h.tag.text = "L${ch.src}"
            h.tag.setTextColor(Color.parseColor("#8A8A99"))
        }
        h.star.visibility = if (ch.key in favs) View.VISIBLE else View.GONE
        h.row.setOnClickListener { onClick(ch) }
        h.row.setOnLongClickListener { onLong(ch); true }
    }

    override fun getItemCount() = items.size
}
