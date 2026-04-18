package com.misbahminiproject.tetris

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.misbahminiproject.tetris.logic.Action
import com.misbahminiproject.tetris.logic.Direction
import com.misbahminiproject.tetris.logic.GameViewModel
import com.misbahminiproject.tetris.logic.SoundUtil
import com.misbahminiproject.tetris.logic.StatusBarUtil
import com.misbahminiproject.tetris.ui.theme.ComposetetrisTheme
import com.misbahminiproject.tetris.ui.theme.GameBody
import com.misbahminiproject.tetris.ui.theme.GameScreen
import com.misbahminiproject.tetris.ui.theme.PreviewGamescreen
import com.misbahminiproject.tetris.ui.theme.combinedClickable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBarUtil.transparentStatusBar(this)
        SoundUtil.init(this)

        setContent {
            ComposetetrisTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val viewModel = viewModel<GameViewModel>()
                    val viewState by viewModel.viewState.collectAsStateWithLifecycle()

                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = object : DefaultLifecycleObserver {
                            override fun onResume(owner: LifecycleOwner) {
                                viewModel.dispatch(Action.Resume)
                            }

                            override fun onPause(owner: LifecycleOwner) {
                                viewModel.dispatch(Action.Pause)
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }

                    GameBody(combinedClickable(
                        onMove = { direction: Direction ->
                            if (direction == Direction.Up) viewModel.dispatch(Action.Drop)
                            else viewModel.dispatch(Action.Move(direction))
                        },
                        onRotate = {
                            viewModel.dispatch(Action.Rotate)
                        },
                        onRestart = {
                            viewModel.dispatch(Action.Reset)
                        },
                        onPause = {
                            if (viewState.isRunning) {
                                viewModel.dispatch(Action.Pause)
                            } else {
                                viewModel.dispatch(Action.Resume)
                            }
                        },
                        onMute = {
                            viewModel.dispatch(Action.Mute)
                        }
                    )) {
                        GameScreen(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundUtil.release()
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    ComposetetrisTheme {
        GameBody {
            PreviewGamescreen(Modifier.fillMaxSize())
        }
    }
}
