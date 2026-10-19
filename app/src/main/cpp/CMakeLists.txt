cmake_minimum_required(VERSION 3.22)
project(dictate_qwen LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
if(NOT EXISTS "${CRISPASR_SOURCE}/src/qwen3_asr.cpp")
    message(FATAL_ERROR "CRISPASR_SOURCE must point to pinned CrispASR checkout")
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# Two Android builds share these sources (see tools/build-qwen-native.sh): a baseline armv8-a library
# that runs on every arm64 phone, and one using ARMv8.2 dot-product and fp16 instructions, which
# make ggml's quantised matmuls (and its repacked Q4_K/Q8_0 kernels) several times faster. The app
# loads the second only when the CPU reports asimddp + asimdhp, so older cores never see them.
set(DICTATE_ARM_ARCH "armv8-a" CACHE STRING "GGML_CPU_ARM_ARCH for the Android build")
set(DICTATE_LIB_NAME "dictate-crispasr" CACHE STRING "Output name of the Android JNI library")
if(ANDROID)
    set(GGML_CPU_ARM_ARCH ${DICTATE_ARM_ARCH} CACHE STRING "" FORCE)
endif()
add_subdirectory("${CRISPASR_SOURCE}/ggml" ggml)
add_library(qwen-runtime STATIC qwen_adapter.cpp crispasr_engines.cpp
    "${CRISPASR_SOURCE}/src/qwen3_asr.cpp"
    "${CRISPASR_SOURCE}/src/granite_speech.cpp"
    "${CRISPASR_SOURCE}/src/cohere.cpp"
    "${CRISPASR_SOURCE}/src/moonshine_streaming.cpp"
    "${CRISPASR_SOURCE}/src/moonshine-tokenizer.cpp"
    "${CRISPASR_SOURCE}/src/core/gguf_loader.cpp"
    "${CRISPASR_SOURCE}/src/core/mel.cpp"
    "${CRISPASR_SOURCE}/src/crispasr_imatrix.cpp"
    "${CRISPASR_SOURCE}/crisp_audio/src/audio_tower.cpp")
target_include_directories(qwen-runtime PUBLIC . "${CRISPASR_SOURCE}/src" "${CRISPASR_SOURCE}/include" "${CRISPASR_SOURCE}/crisp_audio/include" "${CRISPASR_SOURCE}/ggml/include" "${CRISPASR_SOURCE}/ggml/src")
target_link_libraries(qwen-runtime PUBLIC ggml ggml-cpu)
if(ANDROID)
    add_library(dictate-crispasr SHARED crispasr_jni.cpp)
    set_target_properties(dictate-crispasr PROPERTIES OUTPUT_NAME ${DICTATE_LIB_NAME})
    target_link_libraries(dictate-crispasr PRIVATE qwen-runtime log)
    target_link_options(dictate-crispasr PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
else()
    add_executable(qwen-smoke qwen_smoke.cpp)
    target_link_libraries(qwen-smoke PRIVATE qwen-runtime)
    add_executable(crispasr-smoke crispasr_smoke.cpp)
    target_link_libraries(crispasr-smoke PRIVATE qwen-runtime)
    add_executable(qwen-adapter-test qwen_adapter_test.cpp qwen_adapter.cpp)
    target_include_directories(qwen-adapter-test PRIVATE "${CRISPASR_SOURCE}/src")
    enable_testing()
    add_test(NAME qwen-adapter-test COMMAND qwen-adapter-test)
    add_test(NAME qwen-native-self-test COMMAND qwen-smoke --self-test)
    add_test(NAME crispasr-engine-self-test COMMAND crispasr-smoke --self-test)
endif()
