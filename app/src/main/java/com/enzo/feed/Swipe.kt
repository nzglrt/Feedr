package com.enzo.feed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Arrastar para a esquerda apaga; para a direita salva (se permitido). */
class SwipeCallback(
    private val ctx: Context,
    allowRight: Boolean,
    private val onLeft: (Int) -> Unit,
    private val onRight: (Int) -> Unit
) : ItemTouchHelper.SimpleCallback(
    0, ItemTouchHelper.LEFT or (if (allowRight) ItemTouchHelper.RIGHT else 0)
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trash = ContextCompat.getDrawable(ctx, R.drawable.ic_delete)!!.mutate().also { it.setTint(Color.WHITE) }
    private val pin = ContextCompat.getDrawable(ctx, R.drawable.ic_pin_filled)!!.mutate().also { it.setTint(Color.WHITE) }

    override fun onMove(
        rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
    ) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
        val pos = vh.adapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        if (direction == ItemTouchHelper.LEFT) onLeft(pos) else onRight(pos)
    }

    override fun onChildDraw(
        c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            val v = viewHolder.itemView
            val padX = ctx.dp(16)
            val padY = ctx.dp(8)
            val top = v.top + padY
            val bottom = v.bottom - padY
            val toRight = dX > 0
            paint.color = if (toRight) ctx.getColor(R.color.accent) else 0xFFC94040.toInt()
            val rect = if (toRight) RectF((v.left + padX).toFloat(), top.toFloat(), v.left + dX, bottom.toFloat())
            else RectF(v.right + dX, top.toFloat(), (v.right - padX).toFloat(), bottom.toFloat())
            val r = ctx.dp(16).toFloat()
            c.drawRoundRect(rect, r, r, paint)
            val size = ctx.dp(26)
            if (abs(dX) > size + ctx.dp(40)) {
                val icon = if (toRight) pin else trash
                val cx = if (toRight) v.left + padX + ctx.dp(28) else v.right - padX - ctx.dp(28)
                val cy = (top + bottom) / 2
                icon.setBounds(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
                icon.draw(c)
            }
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
    }
}
