package com.example.data.export

import com.example.data.local.AngleMeasurementEntity
import com.example.data.local.CountedAngleEntity
import com.example.data.local.LengthMeasurementEntity
import com.example.domain.math.AngleMath
import com.example.domain.model.UnitSystem
import java.util.Locale

object CsvExporter {

    fun buildMeasurementAndCountCsv(
        documentTitle: String,
        unitSystem: UnitSystem,
        decimals: Int,
        angleToleranceDeg: Double,
        lengths: List<LengthMeasurementEntity>,
        angles: List<AngleMeasurementEntity>,
        countedAngles: List<CountedAngleEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# IngeTrazo Mobile Export")
        sb.appendLine("# Document,${escape(documentTitle)}")
        sb.appendLine("# Display Unit,${unitSystem.label},Precision,$decimals decimals,Angle Tolerance,${String.format(Locale.US, "%.2f", angleToleranceDeg)} deg")
        sb.appendLine()

        // Section 1: Angle Count Summary
        val buckets = AngleMath.groupAnglesByTolerance(
            countedAngles.map { it.angleDegrees },
            angleToleranceDeg
        )
        sb.appendLine("## ANGLE COUNT SUMMARY")
        sb.appendLine("Total Counted Angles,${countedAngles.size}")
        sb.appendLine("Distinct Angle Groups,${buckets.size}")
        sb.appendLine("Angle Bucket (deg),Count,Mean Value (deg)")
        for (b in buckets) {
            sb.appendLine(
                "${escape(b.bucketKey)},${b.count},${String.format(Locale.US, "%.3f", b.averageDegrees)}"
            )
        }
        sb.appendLine()

        // Section 2: Counted Angles Detail
        sb.appendLine("## COUNTED ANGLES DETAIL")
        sb.appendLine("ID,Angle (deg),Location,Source,Entity Ref,Vertex X (m),Vertex Y (m),Vertex Z (m),Notes")
        for (c in countedAngles) {
            sb.appendLine(
                listOf(
                    c.id.toString(),
                    String.format(Locale.US, "%.3f", c.angleDegrees),
                    escape(c.locationLabel),
                    escape(c.source),
                    escape(c.entityRef),
                    fmtNum(c.vx),
                    fmtNum(c.vy),
                    fmtNum(c.vz),
                    escape(c.notes)
                ).joinToString(",")
            )
        }
        sb.appendLine()

        // Section 3: Length Measurements
        sb.appendLine("## LENGTH MEASUREMENTS")
        sb.appendLine("ID,Label,Length (${unitSystem.symbol}),Length (m),dX (${unitSystem.symbol}),dY (${unitSystem.symbol}),dZ (${unitSystem.symbol}),Ax (m),Ay (m),Az (m),Bx (m),By (m),Bz (m)")
        for (l in lengths) {
            val lenDisp = String.format(Locale.US, "%.${decimals}f", unitSystem.fromMeters(l.lengthMeters))
            val dx = String.format(Locale.US, "%.${decimals}f", unitSystem.fromMeters(kotlin.math.abs(l.bx - l.ax)))
            val dy = String.format(Locale.US, "%.${decimals}f", unitSystem.fromMeters(kotlin.math.abs(l.by - l.ay)))
            val dz = String.format(Locale.US, "%.${decimals}f", unitSystem.fromMeters(kotlin.math.abs(l.bz - l.az)))
            sb.appendLine(
                listOf(
                    l.id.toString(),
                    escape(l.label),
                    lenDisp,
                    fmtNum(l.lengthMeters),
                    dx,
                    dy,
                    dz,
                    fmtNum(l.ax),
                    fmtNum(l.ay),
                    fmtNum(l.az),
                    fmtNum(l.bx),
                    fmtNum(l.by),
                    fmtNum(l.bz)
                ).joinToString(",")
            )
        }
        sb.appendLine()

        // Section 4: Measured Angles
        sb.appendLine("## MEASURED ANGLES")
        sb.appendLine("ID,Label,Angle (deg),Mode,Vertex X (m),Vertex Y (m),Vertex Z (m)")
        for (a in angles) {
            sb.appendLine(
                listOf(
                    a.id.toString(),
                    escape(a.label),
                    String.format(Locale.US, "%.3f", a.angleDegrees),
                    escape(a.mode),
                    fmtNum(a.vx),
                    fmtNum(a.vy),
                    fmtNum(a.vz)
                ).joinToString(",")
            )
        }
        return sb.toString()
    }

    private fun fmtNum(v: Double): String = String.format(Locale.US, "%.4f", v)

    private fun escape(raw: String): String {
        val needsQuote = raw.contains(',') || raw.contains('"') || raw.contains('\n')
        return if (needsQuote) "\"${raw.replace("\"", "\"\"")}\"" else raw
    }
}
