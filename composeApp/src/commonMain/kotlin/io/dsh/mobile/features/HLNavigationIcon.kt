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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HLNavigationIcon(name: String, iconSize: Dp = 23.dp) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(iconSize)) {
        val u = size.width / 24
        val stroke = Stroke(width = 1.7f * u, cap = StrokeCap.Round)
        when (name) {
            "LIKE", "DISLIKE" -> { rotate(if (name == "DISLIKE") 180f else 0f) { val p=Path().apply {moveTo(8*u,10*u);lineTo(12*u,3*u);quadraticTo(17*u,2*u,15*u,10*u);lineTo(20*u,10*u);quadraticTo(22*u,10*u,21*u,13*u);lineTo(19*u,20*u);lineTo(8*u,20*u);close()};drawPath(p,color,style=stroke);drawRoundRect(color,Offset(3*u,10*u),Size(4*u,10*u),CornerRadius(u),style=stroke) } }
            "BRANCH" -> { drawCircle(color,2.3f*u,Offset(17*u,4*u),style=stroke);drawCircle(color,2.3f*u,Offset(17*u,20*u),style=stroke);val p=Path().apply {moveTo(3*u,12*u);lineTo(8*u,12*u);lineTo(15*u,5*u);moveTo(8*u,12*u);lineTo(15*u,19*u)};drawPath(p,color,style=stroke) }
            "DATABASE" -> { drawOval(color,Offset(4*u,3*u),Size(16*u,6*u),style=stroke);drawArc(color,0f,180f,false,Offset(4*u,9*u),Size(16*u,6*u),style=stroke);drawArc(color,0f,180f,false,Offset(4*u,15*u),Size(16*u,6*u),style=stroke);drawLine(color,Offset(4*u,6*u),Offset(4*u,18*u),1.7f*u);drawLine(color,Offset(20*u,6*u),Offset(20*u,18*u),1.7f*u) }
            "SHIELD" -> { val p=Path().apply {moveTo(12*u,2*u);lineTo(21*u,6*u);lineTo(20*u,14*u);quadraticTo(17*u,20*u,12*u,22*u);quadraticTo(7*u,20*u,4*u,14*u);lineTo(3*u,6*u);close();moveTo(8*u,12*u);lineTo(11*u,15*u);lineTo(16*u,9*u)};drawPath(p,color,style=stroke) }
            "GAUGE" -> { drawArc(color,150f,240f,false,Offset(3*u,3*u),Size(18*u,18*u),style=stroke);drawLine(color,Offset(12*u,12*u),Offset(17*u,7*u),1.7f*u,StrokeCap.Round);drawCircle(color,1.4f*u,Offset(12*u,12*u)) }
            "TIMELINE" -> { repeat(3) { val y=(5+7*it)*u;drawCircle(color,1.5f*u,Offset(4*u,y));drawLine(color,Offset(9*u,y),Offset(21*u,y),1.7f*u,StrokeCap.Round) } }
            "DIFF" -> { val p=Path().apply {moveTo(5*u,2*u);lineTo(14*u,2*u);lineTo(19*u,7*u);lineTo(19*u,22*u);lineTo(5*u,22*u);close();moveTo(14*u,2*u);lineTo(14*u,7*u);lineTo(19*u,7*u);moveTo(8*u,11*u);lineTo(15*u,11*u);moveTo(11.5f*u,8*u);lineTo(11.5f*u,14*u);moveTo(8*u,18*u);lineTo(15*u,18*u)};drawPath(p,color,style=stroke) }
            "CLOSE" -> { drawLine(color,Offset(6*u,6*u),Offset(18*u,18*u),1.7f*u,StrokeCap.Round);drawLine(color,Offset(18*u,6*u),Offset(6*u,18*u),1.7f*u,StrokeCap.Round) }

            "PIN" -> { val p=Path().apply {moveTo(8*u,3*u);lineTo(16*u,3*u);lineTo(15*u,10*u);lineTo(19*u,14*u);lineTo(5*u,14*u);lineTo(9*u,10*u);close();moveTo(12*u,14*u);lineTo(12*u,22*u)};drawPath(p,color,style=stroke) }
            "REFRESH" -> { drawArc(color,40f,280f,false,Offset(4*u,4*u),Size(16*u,16*u),style=stroke);val p=Path().apply {moveTo(20*u,3*u);lineTo(20*u,9*u);lineTo(14*u,9*u)};drawPath(p,color,style=stroke) }

            "PHOTO" -> { drawRoundRect(color,Offset(3*u,3*u),Size(18*u,18*u),CornerRadius(3*u),style=stroke);drawCircle(color,2*u,Offset(8*u,8*u));val p=Path().apply {moveTo(3*u,18*u);lineTo(10*u,11*u);lineTo(14*u,15*u);lineTo(17*u,12*u);lineTo(21*u,16*u)};drawPath(p,color,style=stroke) }
            "DOWNLOAD" -> { val p=Path().apply {moveTo(12*u,3*u);lineTo(12*u,15*u);moveTo(7*u,10*u);lineTo(12*u,15*u);lineTo(17*u,10*u);moveTo(4*u,17*u);lineTo(4*u,21*u);lineTo(20*u,21*u);lineTo(20*u,17*u)};drawPath(p,color,style=stroke) }
            "RIGHT" -> { val p=Path().apply {moveTo(9*u,5*u);lineTo(16*u,12*u);lineTo(9*u,19*u)};drawPath(p,color,style=stroke) }

            "COPY" -> { drawRoundRect(color,Offset(8*u,8*u),Size(12*u,13*u),CornerRadius(2*u),style=stroke); val path=Path().apply { moveTo(5*u,16*u);lineTo(3*u,16*u);lineTo(3*u,3*u);lineTo(16*u,3*u);lineTo(16*u,5*u) };drawPath(path,color,style=stroke) }
            "CHECK" -> { val path=Path().apply {moveTo(5*u,12*u);lineTo(10*u,17*u);lineTo(20*u,6*u)};drawPath(path,color,style=stroke) }
            "TOOL" -> { val path=Path().apply {moveTo(8*u,5*u);lineTo(2*u,12*u);lineTo(8*u,19*u);moveTo(16*u,5*u);lineTo(22*u,12*u);lineTo(16*u,19*u)};drawPath(path,color,style=stroke) }

            "THINK" -> { rotate(45f) { drawOval(color, Offset(2*u,8*u), Size(20*u,8*u), style=stroke) }; rotate(-45f) { drawOval(color, Offset(2*u,8*u), Size(20*u,8*u), style=stroke) };drawCircle(color,1.2f*u) }
            "MENU" -> { drawLine(color,Offset(4*u,8*u),Offset(20*u,8*u),1.7f*u,StrokeCap.Round); drawLine(color,Offset(4*u,16*u),Offset(15*u,16*u),1.7f*u,StrokeCap.Round) }
            "NEW_CHAT" -> { val p=Path().apply {moveTo(10*u,4*u);lineTo(4*u,4*u);lineTo(4*u,20*u);lineTo(20*u,20*u);lineTo(20*u,14*u);moveTo(9*u,15*u);lineTo(10*u,10*u);lineTo(18*u,2*u);lineTo(22*u,6*u);lineTo(14*u,14*u);close();moveTo(16*u,4*u);lineTo(20*u,8*u)};drawPath(p,color,style=stroke) }
            "PLUS" -> { drawLine(color, Offset(12*u,5*u), Offset(12*u,19*u), 1.7f*u, StrokeCap.Round); drawLine(color, Offset(5*u,12*u), Offset(19*u,12*u), 1.7f*u, StrokeCap.Round) }
            "DOWN", "UP" -> { val a = if(name == "UP") 15f else 9f; val b=if(name == "UP") 9f else 15f; val path=Path().apply { moveTo(6*u,a*u); lineTo(12*u,b*u); lineTo(18*u,a*u) };drawPath(path,color,style=stroke) }
            "BACK" -> { val path = Path().apply { moveTo(15*u,5*u); lineTo(8*u,12*u); lineTo(15*u,19*u) }; drawPath(path,color,style=stroke) }
            "SEARCH" -> { drawCircle(color, 6.5f*u, Offset(10.5f*u,10.5f*u), style=stroke); drawLine(color,Offset(16*u,16*u),Offset(21*u,21*u),1.7f*u,StrokeCap.Round) }
            "STOP" -> drawRoundRect(color,Offset(7*u,7*u),Size(10*u,10*u),CornerRadius(2*u))
            "SEND" -> { val path = Path().apply { moveTo(6*u,11*u); lineTo(12*u,5*u); lineTo(18*u,11*u); moveTo(12*u,5*u); lineTo(12*u,20*u) }; drawPath(path,color,style=stroke) }
            "MORE" -> repeat(3) { drawCircle(color,1.4f*u,Offset((6+it*6)*u,12*u)) }
            "FILE" -> { val path=Path().apply { moveTo(5*u,2*u); lineTo(14*u,2*u); lineTo(19*u,7*u); lineTo(19*u,22*u); lineTo(5*u,22*u); close(); moveTo(14*u,2*u); lineTo(14*u,7*u); lineTo(19*u,7*u); moveTo(8*u,12*u); lineTo(16*u,12*u); moveTo(8*u,16*u); lineTo(14*u,16*u) };drawPath(path,color,style=stroke) }
            "LAPTOP" -> { drawRoundRect(color,Offset(4*u,3*u),Size(16*u,13*u),CornerRadius(2*u),style=stroke); val path=Path().apply { moveTo(4*u,16*u); lineTo(1*u,20*u); lineTo(23*u,20*u); lineTo(20*u,16*u) };drawPath(path,color,style=stroke) }
            "SCAN" -> { for ((x,y) in listOf(3f to 3f,15f to 3f,3f to 15f)) drawRoundRect(color,Offset(x*u,y*u),Size(6*u,6*u),CornerRadius(u),style=stroke); drawLine(color,Offset(15*u,15*u),Offset(21*u,15*u),1.7f*u);drawLine(color,Offset(21*u,15*u),Offset(21*u,21*u),1.7f*u);drawLine(color,Offset(15*u,21*u),Offset(17*u,21*u),1.7f*u) }
            "CHATS" -> {
                val path = Path().apply { moveTo(5*u,3*u); lineTo(19*u,3*u); quadraticTo(22*u,3*u,22*u,6*u); lineTo(22*u,15*u); quadraticTo(22*u,18*u,19*u,18*u); lineTo(9*u,18*u); lineTo(3*u,22*u); lineTo(3*u,18*u); quadraticTo(2*u,18*u,2*u,15*u); lineTo(2*u,6*u); quadraticTo(2*u,3*u,5*u,3*u); close() }
                drawPath(path, color, style = stroke); drawLine(color, Offset(7*u,8*u), Offset(17*u,8*u), 1.7f*u, StrokeCap.Round); drawLine(color, Offset(7*u,12*u), Offset(14*u,12*u), 1.7f*u, StrokeCap.Round)
            }
            "DEVICES" -> { drawRoundRect(color, Offset(6*u,2*u), Size(12*u,20*u), CornerRadius(2.5f*u), style = stroke); drawLine(color, Offset(10*u,18*u), Offset(14*u,18*u), 1.7f*u, StrokeCap.Round) }
            "WORKSPACES" -> {
                val path = Path().apply { moveTo(2*u,7*u); lineTo(2*u,5*u); lineTo(9*u,5*u); lineTo(12*u,8*u); lineTo(22*u,8*u); lineTo(22*u,20*u); lineTo(2*u,20*u); close() }; drawPath(path, color, style = stroke)
            }
            else -> {
                val gear=Path(); repeat(32) { index -> val angle=index*kotlin.math.PI/16; val radius=if(index%4 in 1..2) 10f else 8f; val x=(12+radius*cos(angle)).toFloat()*u; val y=(12+radius*sin(angle)).toFloat()*u; if(index==0) gear.moveTo(x,y) else gear.lineTo(x,y) }; gear.close(); drawPath(gear,color,style=stroke); drawCircle(color,3.3f*u,style=stroke)
            }
        }
    }
}
