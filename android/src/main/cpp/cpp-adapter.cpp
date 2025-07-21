#include <jni.h>
#include "qusaieilouti99_callmanagerOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return margelo::nitro::qusaieilouti99_callmanager::initialize(vm);
}
