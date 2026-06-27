package com.pingsim

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(
    private val items: List<PingRecord>,
    private val onItemClick: (PingRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.itemTitle)
        val result: TextView = v.findViewById(R.id.itemResult)
        val detail: TextView = v.findViewById(R.id.itemDetail)
        val meta: TextView = v.findViewById(R.id.itemMeta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val ctx = h.itemView.context
        h.title.text = "${r.phone}   [mã: ${r.code}]"

        val (label, colorRes) = when (r.outcome) {
            PingManager.Outcome.ON -> "✅ MÁY MỞ" to R.color.green
            PingManager.Outcome.OFF -> "⛔ MÁY TẮT / NGOÀI VÙNG" to R.color.red
            PingManager.Outcome.FAILED -> "⚠️ LỖI" to R.color.orange
        }
        h.result.text = label
        h.result.setTextColor(ContextCompat.getColor(ctx, colorRes))

        h.detail.text = r.detail
        h.meta.text = "${r.time}  •  ${r.elapsedMs / 1000.0}s  •  Chạm để ping lại"

        // Chạm vào dòng lịch sử -> điền số này lên ô nhập để ping lại.
        h.itemView.setOnClickListener { onItemClick(r) }
    }
}
