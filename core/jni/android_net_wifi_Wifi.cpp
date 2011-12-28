/*
 * Copyright 2008, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "wifi"

#include "jni.h"
#include <ScopedUtfChars.h>
#include <utils/misc.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/Log.h>
#include <utils/String16.h>

#include "wifi.h"

#define WIFI_PKG_NAME "android/net/wifi/WifiNative"
#define BUF_SIZE 256

//TODO: This file can be refactored to push a lot of the functionality to java
//with just a few JNI calls - doBoolean/doInt/doString

namespace android {

static jint DBG = false;

static int doCommand(const char *cmd, char *replybuf, int replybuflen)
{
    size_t reply_len = replybuflen - 1;

    if (::wifi_command(cmd, replybuf, &reply_len) != 0)
        return -1;
    else {
        // Strip off trailing newline
        if (reply_len > 0 && replybuf[reply_len-1] == '\n')
            replybuf[reply_len-1] = '\0';
        else
            replybuf[reply_len] = '\0';
        return 0;
    }
}

static jint doIntCommand(const char* fmt, ...)
{
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return -1;
    }
    char reply[BUF_SIZE];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return -1;
    }
    return static_cast<jint>(atoi(reply));
}

static jboolean doBooleanCommand(const char* expect, const char* fmt, ...)
{
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return JNI_FALSE;
    }
    char reply[BUF_SIZE];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return JNI_FALSE;
    }
    return (strcmp(reply, expect) == 0);
}

// Send a command to the supplicant, and return the reply as a String
static jstring doStringCommand(JNIEnv* env, const char* fmt, ...) {
    char buf[BUF_SIZE];
    va_list args;
    va_start(args, fmt);
    int byteCount = vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    if (byteCount < 0 || byteCount >= BUF_SIZE) {
        return NULL;
    }
    char reply[4096];
    if (doCommand(buf, reply, sizeof(reply)) != 0) {
        return NULL;
    }
    // TODO: why not just NewStringUTF?
    String16 str((char *)reply);
    return env->NewString((const jchar *)str.string(), str.size());
}

static jboolean android_net_wifi_isDriverLoaded(JNIEnv* env, jobject)
{
    return (jboolean)(::is_wifi_driver_loaded() == 1);
}

static jboolean android_net_wifi_loadDriver(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_load_driver() == 0);
}

static jboolean android_net_wifi_unloadDriver(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_unload_driver() == 0);
}

static jboolean android_net_wifi_startSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_start_supplicant() == 0);
}

static jboolean android_net_wifi_startP2pSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_start_p2p_supplicant() == 0);
}

static jboolean android_net_wifi_killSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_stop_supplicant() == 0);
}

static jboolean android_net_wifi_connectToSupplicant(JNIEnv* env, jobject)
{
    return (jboolean)(::wifi_connect_to_supplicant() == 0);
}

static void android_net_wifi_closeSupplicantConnection(JNIEnv* env, jobject)
{
    ::wifi_close_supplicant_connection();
}

static jstring android_net_wifi_waitForEvent(JNIEnv* env, jobject)
{
    char buf[BUF_SIZE];

    int nread = ::wifi_wait_for_event(buf, sizeof buf);
    if (nread > 0) {
        return env->NewStringUTF(buf);
    } else {
        return NULL;
    }
}

static jboolean android_net_wifi_doBooleanCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return JNI_FALSE;
    }
    if (DBG) LOGD("doBoolean: %s", command.c_str());
    return doBooleanCommand("OK", "%s", command.c_str());
}

static jint android_net_wifi_doIntCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return -1;
    }
    if (DBG) LOGD("doInt: %s", command.c_str());
    return doIntCommand("%s", command.c_str());
}

static jstring android_net_wifi_doStringCommand(JNIEnv* env, jobject, jstring javaCommand)
{
    ScopedUtfChars command(env, javaCommand);
    if (command.c_str() == NULL) {
        return NULL;
    }
    if (DBG) LOGD("doString: %s", command.c_str());
    return doStringCommand(env, "%s", command.c_str());
}



// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gWifiMethods[] = {
    /* name, signature, funcPtr */

    { "loadDriver", "()Z",  (void *)android_net_wifi_loadDriver },
    { "isDriverLoaded", "()Z",  (void *)android_net_wifi_isDriverLoaded},
    { "unloadDriver", "()Z",  (void *)android_net_wifi_unloadDriver },
    { "startSupplicant", "()Z",  (void *)android_net_wifi_startSupplicant },
    { "startP2pSupplicant", "()Z",  (void *)android_net_wifi_startP2pSupplicant },
    { "killSupplicant", "()Z",  (void *)android_net_wifi_killSupplicant },
    { "connectToSupplicant", "()Z",  (void *)android_net_wifi_connectToSupplicant },
    { "closeSupplicantConnection", "()V",  (void *)android_net_wifi_closeSupplicantConnection },
    { "waitForEvent", "()Ljava/lang/String;", (void*) android_net_wifi_waitForEvent },
    { "doBooleanCommand", "(Ljava/lang/String;)Z", (void*) android_net_wifi_doBooleanCommand},
    { "doIntCommand", "(Ljava/lang/String;)I", (void*) android_net_wifi_doIntCommand},
    { "doStringCommand", "(Ljava/lang/String;)Ljava/lang/String;", (void*) android_net_wifi_doStringCommand},
};

int register_android_net_wifi_WifiManager(JNIEnv* env)
{
    return AndroidRuntime::registerNativeMethods(env,
            WIFI_PKG_NAME, gWifiMethods, NELEM(gWifiMethods));
}

}; // namespace android
