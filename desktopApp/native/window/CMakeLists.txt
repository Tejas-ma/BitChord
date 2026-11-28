cmake_minimum_required(VERSION 3.22.1)

# The static runtime is selected below through CMP0091. Without NEW, MSVC
# ignores CMAKE_MSVC_RUNTIME_LIBRARY and links the DLL against the
# redistributable, which the app's private runtime directory cannot assume.
if(POLICY CMP0091)
    cmake_policy(SET CMP0091 NEW)
endif()

project(bitchord_window LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Every native library links the C/C++ runtime statically: this one is unpacked
# into the app's private directory, where neither MinGW's DLLs nor the Visual
# C++ redistributable are guaranteed to exist. MinGW's equivalent is the link
# options at the bottom.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

foreach(suffix "" _DEBUG _RELEASE _RELWITHDEBINFO _MINSIZEREL)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY${suffix} ${CMAKE_BINARY_DIR})
endforeach()

add_library(bitchord_window SHARED window_jni.cpp)
set_target_properties(bitchord_window PROPERTIES PREFIX "")

# JNI headers come from the JDK running the Gradle build, which sets JAVA_HOME
# for every configure and build. find_package is the fallback for a manual
# cmake invocation; MSVC needs include/win32 for jni_md.h just as MinGW does.
if(DEFINED ENV{JAVA_HOME})
    target_include_directories(bitchord_window PRIVATE
        "$ENV{JAVA_HOME}/include"
        "$ENV{JAVA_HOME}/include/win32"
    )
else()
    find_package(JNI REQUIRED)
    target_include_directories(bitchord_window PRIVATE ${JNI_INCLUDE_DIRS})
endif()

# Every import library the source actually calls into:
#   user32  — window enumeration, DPI metrics, hit testing, messages, capture
#   dwmapi  — frame extension, Mica/Acrylic backdrop, rounded corners
#   ole32   — COM init, device enumeration, PropVariantClear, MTA usage
#   uuid    — PKEY_Device_FriendlyName and the endpoint CLSIDs
target_link_libraries(bitchord_window PRIVATE user32 dwmapi ole32 uuid)

if(MSVC)
    # The source is UTF-8 (its comments carry em dashes). MSVC otherwise decodes
    # it with the build machine's ANSI code page and warns on every such file.
    target_compile_options(bitchord_window PRIVATE /utf-8)
elseif(MINGW)
    # Gradle daemons and installed builds do not inherit the developer
    # toolchain's bin directory, so leaving MinGW's libwinpthread dynamic makes
    # the bridge silently unavailable even though the DLL was packaged.
    target_link_options(bitchord_window PRIVATE -static -static-libgcc -static-libstdc++)
endif()
