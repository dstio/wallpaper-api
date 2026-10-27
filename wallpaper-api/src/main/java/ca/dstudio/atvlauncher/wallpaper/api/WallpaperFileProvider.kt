package ca.dstudio.atvlauncher.wallpaper.api

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves a plugin's pictures to the launcher, read-only, from `filesDir/<rootDir>`. Extend it (an
 * empty subclass is enough), declare it, and hand the launcher links such as
 * `content://<authority>/field-1.png` in [WallpaperItem.uri].
 *
 * ```xml
 * <provider android:name=".MyWallpaperFiles"
 *           android:authorities="${applicationId}.wallpapers"
 *           android:exported="true"
 *           android:readPermission="ca.dstudio.atvlauncher.permission.BIND_WALLPAPER"/>
 * ```
 *
 * **Why a permission and no grants.** The launcher reads the plugin's files with the permission it
 * holds, so the provider is exported and guarded by [WallpaperContract.PERMISSION_BIND_WALLPAPER] -
 * a signature permission only the launcher has. It does not use `grantUriPermissions`: a per-link
 * grant is dropped when the plugin updates, and the launcher keeps a plugin's links across updates.
 * The entry is checked when the provider starts ([attachInfo]): not exported, another read
 * permission, or grants, and the plugin fails to start rather than serve its files wrongly.
 *
 * Only [openFile] in mode "r" is served. Every write - `insert`, `update`, `delete`, a writable
 * mode - is refused, so the entry needs no write permission. A link's path is resolved inside
 * the root and must name a regular file there: no "..", no way out through a symbolic link, no
 * folder.
 */
open class WallpaperFileProvider : ContentProvider() {

    /** The folder under `filesDir` this provider serves. */
    open val rootDir: String = "wallpapers"

    override fun attachInfo(context: Context, info: ProviderInfo) {
        if (!info.exported ||
            info.readPermission != WallpaperContract.PERMISSION_BIND_WALLPAPER ||
            info.grantUriPermissions
        ) {
            throw SecurityException(
                "${info.name} must be exported, with android:readPermission=\"" +
                    "${WallpaperContract.PERMISSION_BIND_WALLPAPER}\" and no grantUriPermissions"
            )
        }
        super.attachInfo(context, info)
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("$uri: read-only, not \"$mode\"")
        val context = context ?: throw FileNotFoundException("$uri: provider not attached")
        val root = File(context.filesDir, rootDir)
        val file = PathGuard.resolveInside(root, uri.path.orEmpty())
            ?: throw FileNotFoundException("$uri: no such file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = PathGuard.mimeTypeOf(uri.path.orEmpty())

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
