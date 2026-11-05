package app.facet.data.db

import android.content.Context
import androidx.room.Room

/** The gallery database in the app's databases folder (excluded from backup with the rest of the app). */
fun createFacetDatabase(context: Context): FacetDatabase {
    val app = context.applicationContext
    return Room.databaseBuilder<FacetDatabase>(app, app.getDatabasePath(DATABASE_FILE_NAME).absolutePath)
        .buildFacetDatabase()
}
