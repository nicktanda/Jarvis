package com.jarvis.app.notifications

import java.util.concurrent.ConcurrentLinkedQueue

class NotificationQueue {

    private val queue = ConcurrentLinkedQueue<NotificationData>()

    fun enqueue(notification: NotificationData) {
        queue.add(notification)
    }

    fun dequeue(): NotificationData? = queue.poll()

    fun peek(): NotificationData? = queue.peek()

    fun remove(key: String) {
        queue.removeAll { it.key == key }
    }

    fun size(): Int = queue.size

    fun isEmpty(): Boolean = queue.isEmpty()

    fun clear() {
        queue.clear()
    }

    fun getAll(): List<NotificationData> = queue.toList()
}
