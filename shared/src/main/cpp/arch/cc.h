#ifndef LWIP_ARCH_CC_H
#define LWIP_ARCH_CC_H

#include <errno.h>
#include <stdlib.h>
#include <sys/time.h>

#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_ERRNO_INCLUDE <errno.h>
#define LWIP_RAND() ((unsigned int)rand())

#define LWIP_PLATFORM_DIAG(x) do { } while (0)
#define LWIP_PLATFORM_ASSERT(message) do { (void)(message); abort(); } while (0)

typedef unsigned int sys_prot_t;

#endif
