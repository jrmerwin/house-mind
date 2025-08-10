package com.example.ld1.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.ld1.R
import com.example.ld1.model.ChatMessage

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {
    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_BOT = 2
        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(o: ChatMessage, n: ChatMessage) = o.id == n.id
            override fun areContentsTheSame(o: ChatMessage, n: ChatMessage) = o == n
        }
    }
    override fun getItemViewType(position: Int) =
        if (getItem(position).fromUser) TYPE_USER else TYPE_BOT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserVH(inf.inflate(R.layout.row_chat_user, parent, false))
        } else {
            BotVH(inf.inflate(R.layout.row_chat_bot, parent, false))
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserVH -> holder.bind(msg)
            is BotVH  -> holder.bind(msg)
        }
    }

    class UserVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt: TextView = v.findViewById(R.id.messageText)
        fun bind(m: ChatMessage) { txt.text = m.text }
    }
    class BotVH(v: View) : RecyclerView.ViewHolder(v) {
        private val txt: TextView = v.findViewById(R.id.messageText)
        fun bind(m: ChatMessage) { txt.text = m.text }
    }
}
