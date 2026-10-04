package com.example

import com.example.privprint.service.location.PrivPrintLocationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyShopsGeoTest {

    @Test
    fun testHaversineDistanceCalculationAccuracy() {
        // Ahmedabad center to SG Highway (approx 8.5 km)
        val lat1 = 23.0225
        val lon1 = 72.5714
        val lat2 = 23.0520
        val lon2 = 72.5020

        val distanceKm = PrivPrintLocationHelper.calculateHaversineDistance(lat1, lon1, lat2, lon2)
        // Distance should be roughly between 7.5 and 9.5 km
        assertTrue("Distance should be around 8 km but was $distanceKm", distanceKm in 7.0..10.0)

        // Same point distance should be 0.0
        val zeroDist = PrivPrintLocationHelper.calculateHaversineDistance(lat1, lon1, lat1, lon1)
        assertEquals(0.0, zeroDist, 0.001)
    }

    @Test
    fun testHaversineInterCityDistance() {
        // Ahmedabad (23.0225, 72.5714) to Delhi (28.6139, 77.2090) ~ 775 km
        val distanceAhmedabadToDelhi = PrivPrintLocationHelper.calculateHaversineDistance(
            23.0225, 72.5714,
            28.6139, 77.2090
        )
        assertTrue("Inter-city distance should be ~775km", distanceAhmedabadToDelhi in 750.0..820.0)
    }
}
