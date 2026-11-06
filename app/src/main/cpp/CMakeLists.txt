cmake_minimum_required(VERSION 3.22.1)

project(antitrack_tunnel C)

# Userspace TCP/IP стек из NetGuard (GPLv3, Copyright 2015-2026 Marcel Bokhorst).
# Он даёт точный UID каждого соединения через getConnectionOwnerUid и позволяет
# блокировать трафик по IP, а не только по DNS.
add_library(
    antitrack_tunnel
    SHARED
    netguard/netguard.c
    netguard/session.c
    netguard/ip.c
    netguard/tcp.c
    netguard/udp.c
    netguard/icmp.c
    netguard/tls.c
    netguard/dns.c
    netguard/dhcp.c
    netguard/pcap.c
    netguard/util.c
)

target_include_directories(antitrack_tunnel PRIVATE netguard)

find_library(log-lib log)
target_link_libraries(antitrack_tunnel ${log-lib})

# Android 15+ требует поддержку 16 КБ страниц для нативных библиотек.
target_link_options(antitrack_tunnel PRIVATE "-Wl,-z,max-page-size=16384")
