package github.leavesczy.alphavideoplayer.sample

import android.content.Context

internal object VideoAssets {

    private const val FOLDER = "videos"

    data class Video(
        val path: String,
        val name: String
    )

    fun list(context: Context): List<Video> {
        return context.assets.list(FOLDER)
            .orEmpty()
            .filter { name -> name.endsWith(suffix = ".mp4", ignoreCase = true) }
            .sorted()
            .map { fileName ->
                Video(
                    path = "$FOLDER/$fileName",
                    name = fileName.substringBeforeLast(delimiter = '.')
                )
            }
    }

}
