package app.facet.android.platform

import android.content.Context
import android.os.StatFs
import app.facet.platform.StorageInfo

class AndroidStorageInfo(context: Context) : StorageInfo {
    private val dir = context.applicationContext.filesDir

    override fun freeBytes(): Long = StatFs(dir.path).availableBytes
}
