cmake_minimum_required(VERSION 3.22.1)

project("flatgram_sticker")

set(THIRDPARTY_DIR "${CMAKE_HOME_DIRECTORY}/third_party")

file(GLOB_RECURSE RLOTTIE_SOURCES
        "${THIRDPARTY_DIR}/rlottie/src/*.cpp"
        "${THIRDPARTY_DIR}/rlottie/src/*.c"
        "${THIRDPARTY_DIR}/rlottie/src/*.cc"
        "${THIRDPARTY_DIR}/rlottie/src/*.cxx"
)

set(RLOTTIE_FILTERED_SOURCES "")
foreach(source_file ${RLOTTIE_SOURCES})
    string(REPLACE "\\" "/" source_path "${source_file}")
    string(REGEX MATCH "/(wasm|test|example|tools|pixman)/|/vdrawhelper_neon\\.cpp$" excluded_source "${source_path}")
    if(NOT excluded_source)
        list(APPEND RLOTTIE_FILTERED_SOURCES "${source_file}")
    endif()
endforeach()

add_library(rlottie STATIC ${RLOTTIE_FILTERED_SOURCES})
target_include_directories(rlottie
        PUBLIC "${THIRDPARTY_DIR}/rlottie/inc"
        PRIVATE
        "${THIRDPARTY_DIR}/rlottie/vs2019"
        "${THIRDPARTY_DIR}/rlottie/src"
        "${THIRDPARTY_DIR}/rlottie/src/vector"
        "${THIRDPARTY_DIR}/rlottie/src/vector/freetype"
)
target_compile_features(rlottie PUBLIC cxx_std_14)
target_compile_options(rlottie PRIVATE
        -std=c++14
        -O3
        -ffast-math
        -ftree-vectorize
        -fno-exceptions
        -fno-unwind-tables
        -fno-asynchronous-unwind-tables
        -fno-rtti
        -fvisibility=hidden
        -fvisibility-inlines-hidden
        -DNDEBUG
        -Wno-unused-parameter
)
if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(rlottie PRIVATE -mfpu=neon)
endif()

add_library(flatgram_sticker SHARED sticker_jni.cpp)
target_include_directories(flatgram_sticker PRIVATE
        "${THIRDPARTY_DIR}/rlottie/inc"
)
target_compile_features(flatgram_sticker PUBLIC cxx_std_17)
target_compile_options(flatgram_sticker PRIVATE
        -O3
        -ffast-math
        -fvisibility=hidden
)
if (${ANDROID_ABI} STREQUAL "armeabi-v7a")
    target_compile_options(flatgram_sticker PRIVATE -mfpu=neon)
endif()
target_link_libraries(flatgram_sticker
        rlottie
        jnigraphics
        android
        log
)

if (${ANDROID_ABI} STREQUAL "arm64-v8a" OR ${ANDROID_ABI} STREQUAL "x86_64")
    target_link_options(flatgram_sticker PRIVATE "-Wl,-z,max-page-size=16384")
endif()
