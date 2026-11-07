package com.xlollx.songport.ytmbridge

import androidx.core.content.FileProvider

/** Its own subclass: Vivi declares the plain FileProvider, and the manifest merger would fold the two into one. */
class BridgeFileProvider : FileProvider()
