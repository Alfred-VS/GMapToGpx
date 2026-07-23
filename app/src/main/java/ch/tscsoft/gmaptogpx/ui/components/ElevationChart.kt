package ch.tscsoft.gmaptogpx.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import ch.tscsoft.gmaptogpx.data.models.RouteOption
import java.util.*

@Composable
fun ElevationChart(
    option: RouteOption,
    modifier: Modifier = Modifier,
    highlightedIndex: Int? = null,
    onPointHighlighted: (Int?) -> Unit = {}
) {
    val altitudes = option.altitudes
    val distances = option.distances
    if (altitudes.isEmpty() || altitudes.size != distances.size) return

    val minAlt = (altitudes.minOrNull() ?: 0.0)
    val maxAlt = (altitudes.maxOrNull() ?: 0.0)
    val totalDist = distances.lastOrNull() ?: 1.0
    val altRange = (maxAlt - minAlt).coerceAtLeast(10.0)

    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightColor = MaterialTheme.colorScheme.error

    var zoomX by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var chartWidth by remember { mutableIntStateOf(0) }

    LaunchedEffect(highlightedIndex, zoomX, chartWidth) {
        if (highlightedIndex != null && chartWidth > 0 && zoomX > 1f) {
            val width = chartWidth.toFloat()
            val hX = (distances[highlightedIndex] / totalDist * width * zoomX).toFloat()
            val margin = 50f // pixels margin
            
            if (hX < offsetX + margin) {
                offsetX = (hX - margin).coerceIn(0f, width * (zoomX - 1f))
            } else if (hX > offsetX + width - margin) {
                offsetX = (hX - width + margin).coerceIn(0f, width * (zoomX - 1f))
            }
        }
    }

    Column(modifier = modifier) {
        val currentAlt = if (highlightedIndex != null && highlightedIndex in altitudes.indices) {
            altitudes[highlightedIndex]
        } else maxAlt

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${currentAlt.toInt()} m",
                style = MaterialTheme.typography.labelSmall,
                color = if (highlightedIndex != null) highlightColor else labelColor
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        zoomX = (zoomX / 1.5f).coerceAtLeast(1f)
                        val maxOffset = (chartWidth * (zoomX - 1f))
                        offsetX = offsetX.coerceAtMost(maxOffset)
                    },
                    modifier = Modifier
                        .size(16.dp),
                    enabled = zoomX > 1f
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", modifier = Modifier.size(24.dp))
                }

                Text(
                    "${String.format(Locale.US, "%.1f", zoomX)}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                IconButton(
                    onClick = {
                        zoomX = (zoomX * 1.5f).coerceAtMost(10f)
                    },
                    modifier = Modifier
                        .size(16.dp)
                        ,
                    enabled = zoomX < 10f
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", modifier = Modifier.size(24.dp))
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .onSizeChanged { chartWidth = it.width }
                .pointerInput(distances) {
                    detectTapGestures(
                        onDoubleTap = {
                            zoomX = 1f
                            offsetX = 0f
                        },
                        onTap = { offset ->
                            val index = findNearestIndex(offset.x, size.width, distances, zoomX, offsetX)
                            onPointHighlighted(index)
                        }
                    )
                }
                .pointerInput(distances) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomX = (zoomX * zoom).coerceIn(1f, 10f)
                        val maxOffset = (size.width * (zoomX - 1f))
                        offsetX = (offsetX - pan.x).coerceIn(0f, maxOffset)
                    }
                }
                .pointerInput(distances) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val index = findNearestIndex(offset.x, size.width, distances, zoomX, offsetX)
                            onPointHighlighted(index)
                        },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change, _ ->
                            val x = change.position.x
                            val index = findNearestIndex(x, size.width, distances, zoomX, offsetX)
                            onPointHighlighted(index)
                        }
                    )
                }
        ) {
            val width = size.width
            val height = size.height

            if (option.segments.isNotEmpty()) {
                option.segments.forEach { seg ->
                    val segPath = Path()
                    val segFillPath = Path()
                    val segColor = getSurfaceColor(seg.surface)

                    for (i in seg.startIndex..seg.endIndex) {
                        if (i >= distances.size) break
                        val x = ((distances[i] / totalDist * width * zoomX) - offsetX).toFloat()
                        val y = (height - ((altitudes[i] - minAlt) / altRange * height)).toFloat()

                        if (i == seg.startIndex) {
                            segPath.moveTo(x, y)
                            segFillPath.moveTo(x, height)
                            segFillPath.lineTo(x, y)
                        } else {
                            segPath.lineTo(x, y)
                            segFillPath.lineTo(x, y)
                        }
                    }
                    val lastIdx = Math.min(seg.endIndex, distances.size - 1)
                    val lastX = ((distances[lastIdx] / totalDist * width * zoomX) - offsetX).toFloat()
                    segFillPath.lineTo(lastX, height)
                    segFillPath.close()

                    drawPath(segFillPath, color = segColor.copy(alpha = 0.3f))
                    drawPath(segPath, color = segColor, style = Stroke(width = 2.dp.toPx()))
                }
            } else {
                val path = Path()
                val fillPath = Path()
                val color = Color(0xFF2196F3)

                distances.forEachIndexed { i, d ->
                    val x = ((d / totalDist * width * zoomX) - offsetX).toFloat()
                    val y = (height - ((altitudes[i] - minAlt) / altRange * height)).toFloat()

                    if (i == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, height)
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }

                    if (i == distances.size - 1) {
                        fillPath.lineTo(x, height)
                        fillPath.close()
                    }
                }
                drawPath(fillPath, color = color.copy(alpha = 0.2f))
                drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))
            }

            if (highlightedIndex != null && highlightedIndex in distances.indices) {
                val hX = ((distances[highlightedIndex] / totalDist * width * zoomX) - offsetX).toFloat()
                val hY = (height - ((altitudes[highlightedIndex] - minAlt) / altRange * height)).toFloat()

                drawLine(
                    color = highlightColor,
                    start = Offset(hX, 0f),
                    end = Offset(hX, height),
                    strokeWidth = 1.dp.toPx()
                )
                drawCircle(
                    color = highlightColor,
                    center = Offset(hX, hY),
                    radius = 4.dp.toPx()
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentDist = if (highlightedIndex != null && highlightedIndex in distances.indices) {
                distances[highlightedIndex]
            } else totalDist

            Text("${minAlt.toInt()} m", style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(
                String.format(Locale.US, "%.1f km", currentDist / 1000.0),
                style = MaterialTheme.typography.labelSmall,
                color = if (highlightedIndex != null) highlightColor else labelColor
            )
        }

        if (option.surfaceSummary.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                option.surfaceSummary.forEach { (label, ratio) ->
                    if (ratio > 0.05) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(getSurfaceColor(label))
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "$label ${(ratio * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getSurfaceColor(surface: String): Color {
    return when (surface.lowercase()) {
        "asphalt", "paved", "concrete" -> Color(0xFF666666)
        "gravel", "fine_gravel", "compacted", "dirt", "ground", "unpaved", "schotter" -> Color(0xFFC2B280)
        "paving_stones", "sett", "cobblestone", "pflaster" -> Color(0xFF999999)
        "sand" -> Color(0xFFEDC9AF)
        "unbekannt" -> Color(0xFF2196F3)
        else -> Color(0xFF2196F3)
    }
}

private fun findNearestIndex(x: Float, width: Int, distances: List<Double>, zoomX: Float = 1f, offsetX: Float = 0f): Int? {
    if (distances.isEmpty()) return null
    val totalDist = distances.last()
    if (totalDist <= 0) return 0
    val targetDist = ((x + offsetX) / (width * zoomX)) * totalDist
    
    var low = 0
    var high = distances.size - 1
    
    while (low <= high) {
        val mid = (low + high) / 2
        if (distances[mid] < targetDist) low = mid + 1
        else if (distances[mid] > targetDist) high = mid - 1
        else return mid
    }
    
    return when {
        low >= distances.size -> distances.size - 1
        high < 0 -> 0
        else -> if (Math.abs(distances[low] - targetDist) < Math.abs(distances[high] - targetDist)) low else high
    }
}
