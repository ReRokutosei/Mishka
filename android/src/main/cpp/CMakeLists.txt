cmake_minimum_required(VERSION 3.22)
project(mishka)

# 体积优化
set(MISHKA_C_OPTS -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
set(MISHKA_LINK_OPTS -Wl,--gc-sections -Wl,--strip-all -Wl,--exclude-libs,ALL)

# libmishka.so：existing process_helper
add_library(mishka SHARED process_helper.c)
target_link_libraries(mishka log)
target_compile_options(mishka PRIVATE ${MISHKA_C_OPTS})
target_link_options(mishka PRIVATE ${MISHKA_LINK_OPTS})

# libmihomo.so（IMPORTED）：cgo c-shared
set(MIHOMO_CORE_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_library(mihomo_imported SHARED IMPORTED)
set_target_properties(mihomo_imported PROPERTIES
        IMPORTED_LOCATION "${MIHOMO_CORE_DIR}/libmihomo.so"
        IMPORTED_SONAME "libmihomo.so")

# libmishka_jni.so：薄 JNI 桥
add_library(mishka_jni SHARED mishka_jni.c)
target_include_directories(mishka_jni PRIVATE "${MIHOMO_CORE_DIR}")
target_link_libraries(mishka_jni log mihomo_imported)
target_compile_options(mishka_jni PRIVATE ${MISHKA_C_OPTS})
target_link_options(mishka_jni PRIVATE ${MISHKA_LINK_OPTS})

# libmihomo_runner.so：薄 PIE 可执行 wrapper
add_executable(mihomo_runner mihomo_wrapper.c)
target_link_libraries(mihomo_runner log dl)
set_target_properties(mihomo_runner PROPERTIES
        OUTPUT_NAME "mihomo_runner"
        PREFIX "lib"
        SUFFIX ".so")
target_compile_options(mihomo_runner PRIVATE ${MISHKA_C_OPTS} -fPIE)
target_link_options(mihomo_runner PRIVATE ${MISHKA_LINK_OPTS} -pie -Wl,-z,now -Wl,-z,relro)
