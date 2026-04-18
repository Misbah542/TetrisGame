package com.misbahminiproject.tetris.logic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.misbahminiproject.tetris.logic.TetrisSpirits.Companion.Empty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

class GameViewModel : ViewModel() {

    private val _viewState = MutableStateFlow(ViewState())
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    private var gameTickJob: Job? = null

    init {
        startGameTick()
    }

    fun dispatch(action: Action) {
        reduce(_viewState.value, action)
    }

    private fun startGameTick() {
        gameTickJob?.cancel()
        gameTickJob = viewModelScope.launch {
            while (isActive) {
                val state = _viewState.value
                delay(650L - 55 * (state.level - 1))
                if (state.isRunning) {
                    dispatch(Action.GameTick)
                }
            }
        }
    }

    private fun reduce(state: ViewState, action: Action) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) {
                when (action) {
                    Action.Reset -> {
                        if (state.gameStatus == GameStatus.Onboard || state.gameStatus == GameStatus.GameOver) {
                            _viewState.update {
                                ViewState(
                                    gameStatus = GameStatus.Running,
                                    isMute = state.isMute
                                )
                            }
                            return@withContext
                        }
                        _viewState.update { state.copy(gameStatus = GameStatus.ScreenClearing) }
                        clearScreen(state = state)
                        _viewState.update {
                            ViewState(
                                gameStatus = GameStatus.Onboard,
                                isMute = state.isMute
                            )
                        }
                    }

                    Action.Pause -> if (state.isRunning) {
                        _viewState.update { it.copy(gameStatus = GameStatus.Paused) }
                    }

                    Action.Resume -> if (state.isPaused) {
                        _viewState.update { it.copy(gameStatus = GameStatus.Running) }
                    }

                    is Action.Move -> {
                        if (!state.isRunning) return@withContext
                        SoundUtil.play(state.isMute, SoundType.Move)
                        val offset = action.direction.toOffset()
                        val spirit = state.tetrisSpirits.moveBy(offset)
                        if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                            _viewState.update { it.copy(tetrisSpirits = spirit) }
                        }
                    }

                    Action.Rotate -> {
                        if (!state.isRunning) return@withContext
                        SoundUtil.play(state.isMute, SoundType.Rotate)
                        val spirit = state.tetrisSpirits.rotate().adjustOffset(state.matrix)
                        if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                            _viewState.update { it.copy(tetrisSpirits = spirit) }
                        }
                    }

                    Action.Drop -> {
                        if (!state.isRunning) return@withContext
                        SoundUtil.play(state.isMute, SoundType.Drop)
                        var i = 0
                        while (state.tetrisSpirits.moveBy(0 to ++i)
                                .isValidInMatrix(state.bricks, state.matrix)
                        ) {
                            // nothing to do
                        }
                        val spirit = state.tetrisSpirits.moveBy(0 to i - 1)
                        _viewState.update { it.copy(tetrisSpirits = spirit) }
                    }

                    Action.GameTick -> {
                        if (!state.isRunning) return@withContext

                        // TetrisSpirits continue falling
                        if (state.tetrisSpirits != Empty) {
                            val spirit = state.tetrisSpirits.moveBy(Direction.Down.toOffset())
                            if (spirit.isValidInMatrix(state.bricks, state.matrix)) {
                                _viewState.update { it.copy(tetrisSpirits = spirit) }
                                return@withContext
                            }
                        }

                        // GameOver
                        if (!state.tetrisSpirits.isValidInMatrix(state.bricks, state.matrix)) {
                            _viewState.update { it.copy(gameStatus = GameStatus.ScreenClearing) }
                            val finalState = clearScreen(state = state)
                            _viewState.update { finalState.copy(gameStatus = GameStatus.GameOver) }
                            return@withContext
                        }

                        // Next TetrisSpirits
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
                        if (clearedLines != 0) { // has cleared lines
                            SoundUtil.play(state.isMute, SoundType.Clean)
                            _viewState.update { it.copy(gameStatus = GameStatus.LineClearing) }
                            // animate the clearing lines
                            repeat(5) {
                                _viewState.update {
                                    state.copy(
                                        gameStatus = GameStatus.LineClearing,
                                        tetrisSpirits = Empty,
                                        bricks = if (it % 2 == 0) noClear else clearing
                                    )
                                }
                                delay(100)
                            }
                            // delay update new state
                            _viewState.update {
                                newState.copy(
                                    bricks = cleared,
                                    gameStatus = GameStatus.Running
                                )
                            }
                        } else {
                            _viewState.update { it.copy(bricks = noClear) }
                        }
                    }

                    Action.Mute -> _viewState.update { it.copy(isMute = !it.isMute) }
                }
            }
        }
    }

    private suspend fun clearScreen(state: ViewState): ViewState {
        SoundUtil.play(state.isMute, SoundType.Start)
        val xRange = 0 until state.matrix.first
        var currentState = state

        (state.matrix.second downTo 0).forEach { y ->
            _viewState.update {
                it.copy(
                    gameStatus = GameStatus.ScreenClearing,
                    bricks = it.bricks + Brick.of(xRange, y until state.matrix.second)
                ).also { currentState = it }
            }
            delay(50)
        }
        (0..state.matrix.second).forEach { y ->
            _viewState.update {
                it.copy(
                    gameStatus = GameStatus.ScreenClearing,
                    bricks = Brick.of(xRange, y until state.matrix.second),
                    tetrisSpirits = Empty
                ).also { currentState = it }
            }
            delay(50)
        }
        return currentState
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
                // clear line
                clearing = clearing.filter { it.location.y != line }
                // clear line and then offset brick
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
