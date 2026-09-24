package com.example

import com.example.privprint.service.realtime.ConnectionState
import com.example.privprint.service.realtime.RealtimeEvent
import com.example.privprint.service.realtime.RealtimeEventType
import com.example.privprint.service.realtime.RealtimeTransportClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTransportTest {

    @Test
    fun testRealtimeClientConnectionAndEventDispatch() = runBlocking {
        val client = RealtimeTransportClient()

        client.connect("user:SES-123", "token_test_abc")
        val state = client.connectionState.first { it is ConnectionState.Connected }
        assertTrue(state is ConnectionState.Connected)
        assertEquals("user:SES-123", (state as ConnectionState.Connected).channel)

        val receivedEvents = mutableListOf<RealtimeEvent>()
        val collector = launch {
            client.events.collect { event ->
                receivedEvents.add(event)
            }
        }

        // Publish print started
        client.publishEvent(
            RealtimeEvent(
                eventId = "EVT-001",
                sequenceNumber = 1L,
                eventType = RealtimeEventType.PRINT_STARTED,
                targetChannel = "user:SES-123",
                jobId = "JOB-101",
                payloadJson = """{"status":"PRINTING"}"""
            )
        )

        // Publish print progress
        client.emitPrintProgress(
            jobId = "JOB-101",
            shopId = "SHOP-101",
            currentPage = 1,
            totalPages = 2,
            currentCopy = 1,
            totalCopies = 1,
            progressPercent = 0.5f
        )

        // Give coroutine brief moment to collect
        kotlinx.coroutines.delay(100)

        assertTrue(receivedEvents.any { it.eventType == RealtimeEventType.PRINT_STARTED })
        assertTrue(receivedEvents.any { it.eventType == RealtimeEventType.PRINT_PROGRESS })

        collector.cancel()
        client.disconnect()
    }

    @Test
    fun testEventDeduplicationIgnoresDuplicateEventId() = runBlocking {
        val client = RealtimeTransportClient()
        val receivedEvents = mutableListOf<RealtimeEvent>()
        val collector = launch {
            client.events.collect { receivedEvents.add(it) }
        }

        val event = RealtimeEvent(
            eventId = "DUPLICATE-EVT-ID",
            sequenceNumber = 1L,
            eventType = RealtimeEventType.COPY_STARTED,
            targetChannel = "all"
        )

        client.publishEvent(event)
        client.publishEvent(event) // Duplicate

        kotlinx.coroutines.delay(50)
        assertEquals(1, receivedEvents.size)

        collector.cancel()
    }
}
