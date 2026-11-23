#ifndef LWIPOPTS_H
#define LWIPOPTS_H

/* NO_SYS: one mutex in userspace_stack.c serializes every lwIP call. */
#define NO_SYS                          1
#define SYS_LIGHTWEIGHT_PROT            0
#define LWIP_TIMERS                     1
#define LWIP_TCPIP_CORE_LOCKING         0

#define LWIP_IPV4                       0
#define LWIP_ARP                        0
#define LWIP_ETHERNET                   0
#define LWIP_IPV6                       1
#define LWIP_IPV6_SCOPES                1
#define LWIP_IPV6_SCOPES_DEBUG          0
#define LWIP_IPV6_NUM_ADDRESSES         3
#define LWIP_IPV6_REASS                 1
#define LWIP_IPV6_FRAG                  1
#define LWIP_IPV6_AUTOCONFIG            0
#define LWIP_IPV6_DHCP6                 0
#define LWIP_IPV6_SEND_ROUTER_SOLICIT   0
#define LWIP_ICMP6                      1

#define LWIP_TCP                        1
#define LWIP_UDP                        1
#define LWIP_RAW                        0
#define LWIP_DNS                        0
#define LWIP_DHCP                       0
#define LWIP_SOCKET                     0
#define LWIP_NETCONN                    0
#define LWIP_COMPAT_SOCKETS             0
#define LWIP_STATS                      0
#define LWIP_SINGLE_NETIF               1
#define LWIP_NETIF_HOSTNAME             0
#define LWIP_HAVE_LOOPIF                0

#define MEM_ALIGNMENT                   4
#define MEM_SIZE                        (128 * 1024)
#define MEMP_NUM_PBUF                   64
#define MEMP_NUM_UDP_PCB                24
#define MEMP_NUM_TCP_PCB                16
#define MEMP_NUM_TCP_PCB_LISTEN         16
#define MEMP_NUM_TCP_SEG                256
#define MEMP_NUM_REASSDATA              8
#define MEMP_NUM_FRAG_PBUF              16
#define PBUF_POOL_SIZE                  320
#define PBUF_POOL_BUFSIZE               1516

#define TCP_MSS                         1360
#define TCP_SND_BUF                     (8 * TCP_MSS)
#define TCP_SND_QUEUELEN                32
/* 64 KB filled in about 70 ms at the busy-screen bitrate, the phone then waited
 * out a 1 s zero-window probe. Scale 2 can advertise at most 262140 bytes. */
#define TCP_WND                         (255 * 1024)
#define LWIP_WND_SCALE                  1
#define TCP_RCV_SCALE                   2
#define LWIP_TCP_SACK_OUT               1
#define TCP_LISTEN_BACKLOG              1
#define TCP_DEFAULT_LISTEN_BACKLOG      8

/* Inbound checks run on the i.MX6 NCM thread for every video segment. Generation stays on. */
#define CHECKSUM_GEN_ICMP6              1
#define CHECKSUM_CHECK_ICMP6            0
#define CHECKSUM_GEN_UDP                1
#define CHECKSUM_CHECK_UDP              0
#define CHECKSUM_GEN_TCP                1
#define CHECKSUM_CHECK_TCP              0

#define LWIP_DEBUG                      0

#endif
