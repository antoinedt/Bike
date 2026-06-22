package com.bike.trainer.export

import com.bike.trainer.session.TrackPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Serialises a recorded ride to Garmin TCX. Strava ingests TCX directly,
 * including power via the ActivityExtension (ns3:TPX) namespace.
 */
object TcxWriter {

    fun write(activityName: String, points: List<TrackPoint>): String {
        val sb = StringBuilder(points.size * 200 + 512)
        val startTime = points.firstOrNull()?.timeMillis ?: System.currentTimeMillis()
        val isoStart = iso(startTime)

        val totalSeconds = if (points.size >= 2) {
            (points.last().timeMillis - points.first().timeMillis) / 1000.0
        } else 0.0
        val totalDistance = points.lastOrNull()?.distanceMeters ?: 0.0
        val maxSpeedMs = (points.maxOfOrNull { it.speedKmh } ?: 0.0) / 3.6
        val avgHr = points.filter { it.heartRate > 0 }.map { it.heartRate }.average().takeIf { !it.isNaN() }
        val maxHr = points.maxOfOrNull { it.heartRate }?.takeIf { it > 0 }
        val avgPower = points.map { it.powerWatts }.average().takeIf { !it.isNaN() } ?: 0.0

        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append(
            """<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" """ +
                """xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">"""
        ).append('\n')
        sb.append("  <Activities>\n")
        sb.append("""    <Activity Sport="Biking">""").append('\n')
        sb.append("      <Id>").append(isoStart).append("</Id>\n")
        sb.append("""      <Lap StartTime="""").append(isoStart).append("\">\n")
        sb.append("        <TotalTimeSeconds>").append(fmt(totalSeconds)).append("</TotalTimeSeconds>\n")
        sb.append("        <DistanceMeters>").append(fmt(totalDistance)).append("</DistanceMeters>\n")
        sb.append("        <MaximumSpeed>").append(fmt(maxSpeedMs)).append("</MaximumSpeed>\n")
        sb.append("        <Calories>0</Calories>\n")
        if (avgHr != null) {
            sb.append("        <AverageHeartRateBpm><Value>")
                .append(avgHr.toInt()).append("</Value></AverageHeartRateBpm>\n")
        }
        if (maxHr != null) {
            sb.append("        <MaximumHeartRateBpm><Value>")
                .append(maxHr).append("</Value></MaximumHeartRateBpm>\n")
        }
        sb.append("        <Intensity>Active</Intensity>\n")
        sb.append("        <TriggerMethod>Manual</TriggerMethod>\n")
        sb.append("        <Track>\n")

        for (p in points) {
            sb.append("          <Trackpoint>\n")
            sb.append("            <Time>").append(iso(p.timeMillis)).append("</Time>\n")
            sb.append("            <Position>\n")
            sb.append("              <LatitudeDegrees>").append(fmt(p.lat)).append("</LatitudeDegrees>\n")
            sb.append("              <LongitudeDegrees>").append(fmt(p.lon)).append("</LongitudeDegrees>\n")
            sb.append("            </Position>\n")
            sb.append("            <AltitudeMeters>").append(fmt(p.elevation)).append("</AltitudeMeters>\n")
            sb.append("            <DistanceMeters>").append(fmt(p.distanceMeters)).append("</DistanceMeters>\n")
            if (p.cadenceRpm > 0) {
                sb.append("            <Cadence>").append(p.cadenceRpm.coerceAtMost(254)).append("</Cadence>\n")
            }
            if (p.heartRate > 0) {
                sb.append("            <HeartRateBpm><Value>")
                    .append(p.heartRate).append("</Value></HeartRateBpm>\n")
            }
            sb.append("            <Extensions>\n")
            sb.append("              <ns3:TPX>\n")
            sb.append("                <ns3:Speed>").append(fmt(p.speedKmh / 3.6)).append("</ns3:Speed>\n")
            sb.append("                <ns3:Watts>").append(p.powerWatts).append("</ns3:Watts>\n")
            sb.append("              </ns3:TPX>\n")
            sb.append("            </Extensions>\n")
            sb.append("          </Trackpoint>\n")
        }

        sb.append("        </Track>\n")
        sb.append("        <Extensions>\n")
        sb.append("          <ns3:LX>\n")
        sb.append("            <ns3:AvgWatts>").append(avgPower.toInt()).append("</ns3:AvgWatts>\n")
        sb.append("          </ns3:LX>\n")
        sb.append("        </Extensions>\n")
        sb.append("      </Lap>\n")
        sb.append("      <Creator xsi:type=\"Device_t\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n")
        sb.append("        <Name>Bike Virtual Trainer</Name>\n")
        sb.append("      </Creator>\n")
        sb.append("    </Activity>\n")
        sb.append("  </Activities>\n")
        sb.append("</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun iso(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
}
