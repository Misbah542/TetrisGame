package com.misbahminiproject.tetris.logic


import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misbahminiproject.tetris.logic.TetrisSpirits.Companion.Empty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min


class GameViewModel : ViewModel() {

    private val _viewState: MutableState<ViewState> = mutableStateOf(ViewState())
    val viewState : State<ViewState> = _viewState


    fun dispatch(action: Action) =
        reduce(viewState.value, action)


    private fun reduce(state: ViewState, action: Action) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {

                emit(when (action) {
                    Action.Reset -> run {
                        if (state.gameStatus == GameStatus.Onboard || state.gameStatus == GameStatus.GameOver)
                            return@run ViewState(
                                gameStatus = GameStatus.Running,
                                isMute = state.isMute
                            )
                        state.copy(
                            gameStatus = GameStatus.ScreenClearing
                        ).also {
                            launch {
                                clearScreen(state = state)
                                emit(
                                    ViewState(
                                        gameStatus = GameStatus.Onboard,
                                        isMute = state.isMute
                                    )
                                )
                            }
                        }
                    }

                    Action.Pause -> if (state.isRunning) {
                        state.copy(gameStatus = GameStatus.Paused)
                    } else state

                    Action.Resume ->
                        if (state.isPaused) {
                            state.copy(gameStatus = GameStatus.Running)
                        } else state

                    is Action.Move -> run {
                        if (!state.isRunning) return@run state
                        SoundUtil.play(state.isMute, SoundType.Move)
                        val offset = action.direction.toOffset()
                        val spirit = state.tetrisSpirits.moveBy(offset)
                        if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                            state.copy(tetrisSpirits = spirit)
                        } else {
                            state
                        }
                    }

                    Action.Rotate -> run {
                        if (!state.isRunning) return@run state
                        SoundUtil.play(state.isMute, SoundType.Rotate)
                        val spirit = state.tetrisSpirits.rotate().adjustOffset(state.matrix)
                        if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                            state.copy(tetrisSpirits = spirit)
                        } else {
                            state
                        }
                    }

                    Action.Drop -> run {
                        if (!state.isRunning) return@run state
                        SoundUtil.play(state.isMute, SoundType.Drop)
                        var i = 0
                        while (state.tetrisSpirits.moveBy(0 to ++i)
                                .isValidInMatrix(state.bricks, state.matrix)
                        ) { //nothing to do
                        }
                        val spirit = state.tetrisSpirits.moveBy(0 to i - 1)

                        state.copy(tetrisSpirits = spirit)
                    }

                    Action.GameTick -> run {
                        if (!state.isRunning) return@run state

                        //TetrisSpirits continue falling
                        if (state.tetrisSpirits != Empty) {
                            val spirit = state.tetrisSpirits.moveBy(Direction.Down.toOffset())
                            if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                                return@run state.copy(tetrisSpirits = spirit)
                            }
                        }

                        //GameOver
                        if (!state.tetrisSpirits.isValidInMatrix(state.bricks, state.matrix)) {
                            return@run state.copy(
                                gameStatus = GameStatus.ScreenClearing
                            ).also {
                                launch {
                                    emit(
                                        clearScreen(state = state).copy(gameStatus = GameStatus.GameOver)
                                    )
                                }
                            }
                        }

                        //Next TetrisSpirits
                        val (updatedBricks, clearedLines) = updateBricks(
                            state.bricks,
                            state.tetrisSpirits,
                            matrix = state.matrix
                        )
                        val (noClear, clearing, cleared) = updatedBricks
                        val newState = state.copy(
                            tetrisSpirits = state.tetrisSpiritsNext,
                            tetrisSpiritsReserve = (state.tetrisSpiritsReserve - state.tetrisSpiritsNext).takeIf { it.isNotEmpty() }
                                ?: generateSpiritReverse(state.matrix),
                            score = state.score + calculateScore(clearedLines) +
                                    if (state.tetrisSpirits != Empty) ScoreEverySpirit else 0,
                            line = state.line + clearedLines
                        )
                        if (clearedLines != 0) {// has cleared lines
                            SoundUtil.play(state.isMute, SoundType.Clean)
                            state.copy(
                                gameStatus = GameStatus.LineClearing
                            ).also {
                                launch {
                                    //animate the clearing lines
                                    repeat(5) {
                                        emit(
                                            state.copy(
                                                gameStatus = GameStatus.LineClearing,
                                                tetrisSpirits = Empty,
                                                bricks = if (it % 2 == 0) noClear else clearing
                                            )
                                        )
                                        delay(100)
                                    }
                                    //delay emit new state
                                    emit(
                                        newState.copy(
                                            bricks = cleared,
                                            gameStatus = GameStatus.Running
                                        )
                                    )
                                }
                            }
                        } else {
                            newState.copy(bricks = noClear)
                        }
                    }

                    Action.Mute -> state.copy(isMute = !state.isMute)

                })
            }
        }

    }

    private suspend fun clearScreen(state: ViewState): ViewState {
        SoundUtil.play(state.isMute, SoundType.Start)
        val xRange = 0 until state.matrix.first
        var newState = state

        (state.matrix.second downTo 0).forEach { y ->
            emit(
                state.copy(
                    gameStatus = GameStatus.ScreenClearing,
                    bricks = state.bricks + Brick.of(
                        xRange, y until state.matrix.second
                    )
                )
            )
            delay(50)
        }
        (0..state.matrix.second).forEach { y ->
            emit(
                state.copy(
                    gameStatus = GameStatus.ScreenClearing,
                    bricks = Brick.of(xRange, y until state.matrix.second),
                    tetrisSpirits = Empty
                ).also { newState = it }
            )
            delay(50)
        }
        return newState
    }

    private fun emit(state: ViewState) {
        _viewState.value = state
    }

    /**
     * Return a [Triple] to store clear-info for bricks:
     * - [Triple.first]:  Bricks before line clearing (Current bricks plus TetrisSpirits)
     * - [Triple.second]: Bricks after line cleared but not offset (bricks minus lines should be cleared)
     * - [Triple.third]: Bricks after line cleared (after bricks offset)
     */
    private fun updateBricks(
        curBricks: List<Brick>,
        tetrisSpirits: TetrisSpirits,
        matrix: Pair<Int, Int>
    ): Pair<Triple<List<Brick>, List<Brick>, List<Brick>>, Int> {
        val bricks = (curBricks + Brick.of(tetrisSpirits))
        val map = mutableMapOf<Float, MutableSet<Float>>()
        bricks.forEach {
            map.getOrPut(it.location.y) {
                mutableSetOf()
            }.add(it.location.x)
        }
        var clearing = bricks
        var cleared = bricks
        val clearLines = map.entries.sortedBy { it.key }
            .filter { it.value.size == matrix.first }.map { it.key }
            .onEach { line ->
                //clear line
                clearing = clearing.filter { it.location.y != line }
                //clear line and then offset brick
                cleared = cleared.filter { it.location.y != line }
                    .map { if (it.location.y < line) it.offsetBy(0 to 1) else it }

            }

        return Triple(bricks, clearing, cleared) to clearLines.size
    }

    data class ViewState(
        val bricks: List<Brick> = emptyList(),
        val tetrisSpirits: TetrisSpirits = Empty,
        val tetrisSpiritsReserve: List<TetrisSpirits> = emptyList(),
        val matrix: Pair<Int, Int> = MatrixWidth to MatrixHeight,
        val gameStatus: GameStatus = GameStatus.Onboard,
        val score: Int = 0,
        val line: Int = 0,
        val isMute: Boolean = false,
    ) {
        val level: Int
            get() = min(10, 1 + line / 20)

        val tetrisSpiritsNext: TetrisSpirits
            get() = tetrisSpiritsReserve.firstOrNull() ?: Empty

        val isPaused
            get() = gameStatus == GameStatus.Paused

        val isRunning
            get() = gameStatus == GameStatus.Running
    }

}

sealed interface Action {
    data class Move(val direction: Direction) : Action
    data object Reset : Action
    data object Pause : Action
    data object Resume : Action
    data object Rotate : Action
    data object Drop : Action
    data object GameTick : Action
    data object Mute : Action
}

enum class GameStatus {
    Onboard,
    Running,
    LineClearing,
    Paused,
    ScreenClearing,
    GameOver
}


private const val MatrixWidth = 12
private const val MatrixHeight = 24