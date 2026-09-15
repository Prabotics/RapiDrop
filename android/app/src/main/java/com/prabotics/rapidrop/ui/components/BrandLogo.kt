package com.prabotics.rapidrop.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prabotics.rapidrop.R



@Composable
fun RapiDropBrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp
) {
    Icon(
        painter = painterResource(id = R.drawable.ic_header_logo),
        contentDescription = "RapiDrop Logo",
        tint = Color.Unspecified,
        modifier = modifier.size(size)
    )
}
