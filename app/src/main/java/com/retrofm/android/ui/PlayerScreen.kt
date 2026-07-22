package com.retrofm.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper
import coil3.compose.AsyncImage
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.retrofm.android.R
import com.retrofm.android.core.R as CoreR

@Composable
fun PlayerScreen(viewModel: PlayerViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // Only show the cast button where CastContext initialized (Play services + cast meta-data);
    // on devices without Google Play services the button simply doesn't appear. Skipped
    // entirely on Automotive OS: probing cast there makes the framework nag about the car's
    // Play services version, and casting from a car makes no sense anyway.
    val isCastAvailable = remember {
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
            runCatching { CastContext.getSharedInstance(context) }.isSuccess
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.padding(12.dp),
                    action = {
                        Button(onClick = { viewModel.retry() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                ) {
                    Text(data.visuals.message)
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                ArtworkImage(
                    imageUrl = uiState.imageUrl,
                    contentDescription = uiState.trackTitle,
                    modifier = Modifier
                        // Cap the artwork so large screens (tablets, car head units) don't
                        // blow it up to fill the width.
                        .widthIn(max = 360.dp)
                        .fillMaxWidth(0.75f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )

                Spacer(modifier = Modifier.height(32.dp))

                // During a server-spliced ad the track metadata describes what's on air, not
                // what we're hearing — show the ad label and countdown instead.
                val adSeconds = uiState.adSecondsRemaining

                Text(
                    text = if (adSeconds != null) {
                        stringResource(R.string.ad_label)
                    } else {
                        uiState.trackTitle
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (adSeconds != null) {
                        stringResource(R.string.ad_countdown, adSeconds)
                    } else {
                        uiState.artistName
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                uiState.castDeviceName?.let { device ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.casting_to, device),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                PlayPauseButton(
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isBuffering,
                    onClick = { viewModel.togglePlayPause() }
                )
            }

            if (isCastAvailable) {
                CastButton(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(48.dp)
                )
            }
        }
    }
}

@Composable
private fun CastButton(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Themed context so the button tints light and stays visible on the dark
            // background; the Activity-scoped context lets it find its FragmentActivity host.
            val themed = ContextThemeWrapper(context, R.style.ThemeOverlay_RetroFM_MediaRoute)
            MediaRouteButton(themed).also {
                CastButtonFactory.setUpMediaRouteButton(context, it)
            }
        }
    )
}

@Composable
private fun ArtworkImage(
    imageUrl: String?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(CoreR.drawable.ic_notification),
        error = painterResource(CoreR.drawable.ic_notification),
        modifier = modifier
    )
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit
) {
    val bufferingDescription = stringResource(R.string.buffering)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(96.dp)
    ) {
        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(80.dp)
                    .semantics { contentDescription = bufferingDescription },
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            IconButton(
                onClick = onClick,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                    ),
                    contentDescription = stringResource(
                        if (isPlaying) R.string.pause else R.string.play
                    ),
                    modifier = Modifier.fillMaxSize(),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
