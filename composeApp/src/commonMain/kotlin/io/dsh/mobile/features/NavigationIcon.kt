package io.dsh.mobile.features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NavigationIcon(name: String) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(23.dp)) {
        val u = size.width / 24
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round)
        when (name) {
            "CHATS" -> {
                val path = Path().apply { moveTo(5*u,3*u); lineTo(19*u,3*u); quadraticTo(22*u,3*u,22*u,6*u); lineTo(22*u,15*u); quadraticTo(22*u,18*u,19*u,18*u); lineTo(9*u,18*u); lineTo(3*u,22*u); lineTo(3*u,18*u); quadraticTo(2*u,18*u,2*u,15*u); lineTo(2*u,6*u); quadraticTo(2*u,3*u,5*u,3*u); close() }
                drawPath(path, color, style = stroke); drawLine(color, Offset(7*u,8*u), Offset(17*u,8*u), 1.7f*u, StrokeCap.Round); drawLine(color, Offset(7*u,12*u), Offset(14*u,12*u), 1.7f*u, StrokeCap.Round)
            }
            "DEVICES" -> { drawRoundRect(color, Offset(6*u,2*u), Size(12*u,20*u), CornerRadius(2.5f*u), style = stroke); drawLine(color, Offset(10*u,18*u), Offset(14*u,18*u), 1.7f*u, StrokeCap.Round) }
            "WORKSPACES" -> {
                val path = Path().apply { moveTo(2*u,7*u); lineTo(2*u,5*u); lineTo(9*u,5*u); lineTo(12*u,8*u); lineTo(22*u,8*u); lineTo(22*u,20*u); lineTo(2*u,20*u); close() }; drawPath(path, color, style = stroke)
            }
            else -> {
                drawCircle(color, 4*u, style = stroke)
                repeat(8) { index -> val angle = index * kotlin.math.PI / 4; drawLine(color, Offset((12+7*cos(angle)).toFloat()*u, (12+7*sin(angle)).toFloat()*u), Offset((12+10*cos(angle)).toFloat()*u, (12+10*sin(angle)).toFloat()*u), 2*u, StrokeCap.Round) }
            }
        }
    }
}
