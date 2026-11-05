package app.facet

import app.facet.mesh.IosMeshEngine

/** Called from Swift (`IosEntryKt.wiringCheck()`): proves Swift → shared → C++ works. */
fun wiringCheck(): String = FacetInfo.wiringCheck(IosMeshEngine())
