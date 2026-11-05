// Android shim for <sys/timeb.h>, which bionic does not ship. PoissonRecon's MyMiscellany.h
// includes it unconditionally but only uses struct _timeb / _ftime on Windows, so it can be empty.
#pragma once
