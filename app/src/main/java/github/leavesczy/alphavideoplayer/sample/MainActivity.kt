package github.leavesczy.alphavideoplayer.sample

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(scrim = Color.TRANSPARENT)
        )
        setContent {
            AppTheme {
                MainScreen(
                    modifier = Modifier
                        .fillMaxSize(),
                    onOpenExoPlayer = {
                        startActivity(
                            PlayerActivity.intent(
                                context = this,
                                playerType = PlayerType.ExoPlayer
                            )
                        )
                    },
                    onOpenSystemPlayer = {
                        startActivity(
                            PlayerActivity.intent(
                                context = this,
                                playerType = PlayerType.SystemPlayer
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    modifier: Modifier,
    onOpenExoPlayer: () -> Unit,
    onOpenSystemPlayer: () -> Unit
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize(),
        topBar = {
            TopBar(
                modifier = Modifier,
                title = stringResource(id = R.string.app_name)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues = innerPadding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = 12.dp,
                alignment = Alignment.CenterVertically
            )
        ) {
            Button(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = onOpenExoPlayer
            ) {
                Text(
                    modifier = Modifier,
                    text = stringResource(id = R.string.demo_exo_player),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth(),
                onClick = onOpenSystemPlayer
            ) {
                Text(
                    modifier = Modifier,
                    text = stringResource(id = R.string.demo_system_player),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
