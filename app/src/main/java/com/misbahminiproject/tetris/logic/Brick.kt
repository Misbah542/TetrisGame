package com.misbahminiproject.tetris.logic

import androidx.compose.ui.geometry.Offset


data class Brick(val location: Offset = Offset(0, 0)) {
    companion object {
        private fun of(offsetList: List<Offset>) = offsetList.map { Brick(it) }

        fun of(tetrisSpirits: TetrisSpirits) = of(tetrisSpirits.location)

        fun of(xRange: IntRange, yRange: IntRange) =
            of(mutableListOf<Offset>().apply {
                xRange.forEach { x ->
                    yRange.forEach { y ->
                        this += Offset(x, y)
                    }
                }
            })

    }

    fun offsetBy(step: Pair<Int, Int>) =
        copy(location = Offset(location.x + step.first, location.y + step.second))

}