#ifndef USERSPACE_STACK_H
#define USERSPACE_STACK_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define USERSPACE_EVENT_NONE   0
#define USERSPACE_EVENT_ACCEPT 1
#define USERSPACE_EVENT_DATA   2
#define USERSPACE_EVENT_CLOSE  3
#define USERSPACE_EVENT_UDP    4
#define USERSPACE_EVENT_PACKET 5

struct userspace_event {
    int type;
    int id;
    int generation;
    int local_port;
    int remote_port;
    uint8_t address[16];
    uint8_t *data;
    int length;
};

int userspace_stack_start(const uint8_t address[16]);
void userspace_stack_stop(void);
void userspace_stack_input(const uint8_t *packet, int length);
int userspace_stack_poll(struct userspace_event *event);
int userspace_stack_write(int id, int generation, const uint8_t *data, int length);
void userspace_stack_recved(int id, int generation, int length);
void userspace_stack_shutdown(int id, int generation);
void userspace_stack_abort(int id, int generation);
int userspace_stack_send_udp(int source_port, const uint8_t destination[16], int destination_port,
                             const uint8_t *data, int length);
void userspace_stack_timeouts(void);
int userspace_stack_event_count(void);

/* Called from tcp_in.c when a segment matches no PCB. Host-order destination port.
   Returns 1 when a listener was created or already existed for a bare SYN. */
int userspace_ensure_listener(unsigned short port);

#ifdef __cplusplus
}
#endif

#endif
