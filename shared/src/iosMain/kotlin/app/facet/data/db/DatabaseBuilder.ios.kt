package app.facet.data.db

import androidx.room.Room

/** The gallery database at [path] (the app's Application Support folder; wired up in Phase 9). */
fun createFacetDatabase(path: String): FacetDatabase =
    Room.databaseBuilder<FacetDatabase>(name = path).buildFacetDatabase()
