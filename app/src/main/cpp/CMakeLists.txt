cmake_minimum_required(VERSION 3.22.1)
project(ircamusb C)

set(LIBUSB ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
add_library(usb1 STATIC
    ${LIBUSB}/core.c ${LIBUSB}/descriptor.c ${LIBUSB}/hotplug.c ${LIBUSB}/io.c
    ${LIBUSB}/strerror.c ${LIBUSB}/sync.c
    ${LIBUSB}/os/linux_usbfs.c ${LIBUSB}/os/linux_netlink.c
    ${LIBUSB}/os/events_posix.c ${LIBUSB}/os/threads_posix.c)
target_include_directories(usb1 PUBLIC ${LIBUSB} ${LIBUSB}/os)
target_compile_options(usb1 PRIVATE -Wno-everything)
target_link_libraries(usb1 log)

add_library(ircamusb SHARED uvc.c jni.c)
target_link_libraries(ircamusb usb1 log)
target_link_options(ircamusb PRIVATE "-Wl,-z,max-page-size=16384")
