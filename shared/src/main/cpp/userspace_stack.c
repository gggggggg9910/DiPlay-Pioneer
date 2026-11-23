#include "userspace_stack.h"

#include "lwip/init.h"
#include "lwip/ip.h"
#include "lwip/ip6.h"
#include "lwip/ip6_addr.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/priv/tcp_priv.h"
#include "lwip/tcp.h"
#include "lwip/timeouts.h"
#include "lwip/udp.h"

#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define SLOT_COUNT 16
#define UDP_COUNT 24
#define EVENT_COUNT 256

struct slot {
    int used;
    int generation;
    struct tcp_pcb *pcb;
};

struct udp_binding {
    int used;
    u16_t port;
    struct udp_pcb *pcb;
};

struct queued_event {
    int type;
    int id;
    int generation;
    int local_port;
    int remote_port;
    uint8_t address[16];
    uint8_t *data;
    int length;
};

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static int started;
static int lwip_ready;
static struct netif nif;
static struct slot slots[SLOT_COUNT];
static struct udp_binding udp_bindings[UDP_COUNT];
static struct queued_event events[EVENT_COUNT];
static int event_head;
static int event_tail;
static int event_count;
static int queued_total;

u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t) (ts.tv_sec * 1000u + ts.tv_nsec / 1000000u);
}

static void copy_addr(uint8_t out[16], const ip_addr_t *addr) {
    memset(out, 0, 16);
    if (addr != NULL && IP_IS_V6(addr)) {
        memcpy(out, ip_2_ip6(addr)->addr, 16);
    }
}

/* Takes ownership of data. Frees it if the queue is full. */
static int enqueue_buffer(int type, int id, int generation, int local_port, int remote_port,
                          const uint8_t address[16], uint8_t *data, int length) {
    if (event_count >= EVENT_COUNT) {
        free(data);
        return 0;
    }
    struct queued_event *event = &events[event_tail];
    event->type = type;
    event->id = id;
    event->generation = generation;
    event->local_port = local_port;
    event->remote_port = remote_port;
    if (address != NULL) {
        memcpy(event->address, address, 16);
    } else {
        memset(event->address, 0, 16);
    }
    event->data = data;
    event->length = length;
    event_tail = (event_tail + 1) % EVENT_COUNT;
    event_count++;
    queued_total++;
    return 1;
}

static int enqueue(int type, int id, int generation, int local_port, int remote_port,
                    const uint8_t address[16], const uint8_t *data, int length) {
    if (event_count >= EVENT_COUNT) return 0;
    uint8_t *copy = NULL;
    if (length > 0) {
        copy = (uint8_t *) malloc((size_t) length);
        if (copy == NULL) return 0;
        if (data != NULL) memcpy(copy, data, (size_t) length);
    }
    return enqueue_buffer(type, id, generation, local_port, remote_port, address, copy, length);
}

static int enqueue_pbuf(int type, int id, int generation, int local_port, int remote_port,
                         const uint8_t address[16], struct pbuf *p) {
    int length = p == NULL ? 0 : (int) p->tot_len;
    if (event_count >= EVENT_COUNT) return 0;
    uint8_t *copy = NULL;
    if (length > 0) {
        copy = (uint8_t *) malloc((size_t) length);
        if (copy == NULL) return 0;
        pbuf_copy_partial(p, copy, (u16_t) length, 0);
    }
    return enqueue_buffer(type, id, generation, local_port, remote_port, address, copy, length);
}

static struct slot *slot_from_arg(void *arg) {
    struct slot *slot = (struct slot *) arg;
    if (slot < slots || slot >= slots + SLOT_COUNT || !slot->used) return NULL;
    return slot;
}

static void detach(struct tcp_pcb *pcb) {
    tcp_arg(pcb, NULL);
    tcp_recv(pcb, NULL);
    tcp_err(pcb, NULL);
}

static void slot_release(struct slot *slot) {
    slot->pcb = NULL;
    slot->used = 0;
    slot->generation++;
}

static void slot_close(struct slot *slot) {
    uint8_t address[16];
    int local_port = 0;
    int remote_port = 0;
    int generation = slot->generation;
    if (slot->pcb != NULL) {
        copy_addr(address, &slot->pcb->remote_ip);
        local_port = slot->pcb->local_port;
        remote_port = slot->pcb->remote_port;
    } else {
        memset(address, 0, sizeof(address));
    }
    enqueue(USERSPACE_EVENT_CLOSE, (int) (slot - slots), generation, local_port, remote_port, address, NULL, 0);
    slot_release(slot);
}

static err_t on_recv(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t err) {
    struct slot *slot = slot_from_arg(arg);
    if (slot == NULL) {
        if (p != NULL) pbuf_free(p);
        return ERR_OK;
    }
    if (p == NULL || err != ERR_OK) {
        if (p != NULL) pbuf_free(p);
        struct tcp_pcb *pcb_to_close = slot->pcb;
        slot_close(slot);
        if (pcb_to_close != NULL) {
            detach(pcb_to_close);
            if (tcp_close(pcb_to_close) != ERR_OK) tcp_abort(pcb_to_close);
        }
        return ERR_OK;
    }
    uint8_t address[16];
    copy_addr(address, &pcb->remote_ip);
    if (!enqueue_pbuf(USERSPACE_EVENT_DATA, (int) (slot - slots), slot->generation, pcb->local_port,
                      pcb->remote_port, address, p)) {
        return ERR_MEM;
    }
    /* The bytes are copied out of lwIP. Return the window here. Waiting until the Java
     * relay writes them to the local socket left the window closed across that hop, and a
     * full window makes the phone drop the next 30 Hz screen slot. */
    u32_t left = p->tot_len;
    while (left > 0) {
        u16_t step = left > 65535u ? 65535u : (u16_t) left;
        tcp_recved(pcb, step);
        left -= step;
    }
    pbuf_free(p);
    return ERR_OK;
}

static void on_err(void *arg, err_t err) {
    (void) err;
    struct slot *slot = slot_from_arg(arg);
    if (slot == NULL) return;
    /* lwIP has already freed the pcb. */
    slot->pcb = NULL;
    slot_close(slot);
}

static err_t on_accept(void *arg, struct tcp_pcb *newpcb, err_t err) {
    (void) arg;
    if (newpcb == NULL || err != ERR_OK) return ERR_VAL;
    int id = -1;
    for (int index = 0; index < SLOT_COUNT; index++) {
        if (!slots[index].used) {
            id = index;
            break;
        }
    }
    if (id < 0) return ERR_MEM;
    struct slot *slot = &slots[id];
    slot->used = 1;
    slot->pcb = newpcb;
    tcp_arg(newpcb, slot);
    tcp_recv(newpcb, on_recv);
    tcp_err(newpcb, on_err);
    tcp_nagle_disable(newpcb);
    uint8_t address[16];
    copy_addr(address, &newpcb->remote_ip);
    if (!enqueue(USERSPACE_EVENT_ACCEPT, id, slot->generation, newpcb->local_port, newpcb->remote_port,
                 address, NULL, 0)) {
        detach(newpcb);
        slot->used = 0;
        slot->pcb = NULL;
        tcp_abort(newpcb);
        return ERR_ABRT;
    }
    return ERR_OK;
}

int userspace_ensure_listener(unsigned short port) {
    for (struct tcp_pcb_listen *listener = tcp_listen_pcbs.listen_pcbs; listener != NULL; listener = listener->next) {
        if (listener->local_port == port) return 1;
    }
    struct tcp_pcb *pcb = tcp_new_ip_type(IPADDR_TYPE_V6);
    if (pcb == NULL) return 0;
    ip_addr_t any;
    ip_addr_set_any(1, &any);
    if (tcp_bind(pcb, &any, port) != ERR_OK) {
        tcp_abort(pcb);
        return 0;
    }
    struct tcp_pcb *listener = tcp_listen_with_backlog(pcb, TCP_DEFAULT_LISTEN_BACKLOG);
    if (listener == NULL) {
        tcp_abort(pcb);
        return 0;
    }
    tcp_accept(listener, on_accept);
    return 1;
}

static void on_udp(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *addr, u16_t port) {
    (void) arg;
    uint8_t address[16];
    copy_addr(address, addr);
    if (!enqueue_pbuf(USERSPACE_EVENT_UDP, -1, 0, pcb->local_port, port, address, p)) {
        pbuf_free(p);
        return;
    }
    pbuf_free(p);
}

int userspace_udp_unclaimed(struct pbuf *p, const ip_addr_t *src, u16_t src_port, u16_t dest_port) {
    uint8_t address[16];
    copy_addr(address, src);
    if (!enqueue_pbuf(USERSPACE_EVENT_UDP, -1, 0, dest_port, src_port, address, p)) return 0;
    return 1;
}

static struct udp_pcb *udp_for_port(u16_t port) {
    for (int index = 0; index < UDP_COUNT; index++) {
        if (udp_bindings[index].used && udp_bindings[index].port == port) return udp_bindings[index].pcb;
    }
    int free_index = -1;
    for (int index = 0; index < UDP_COUNT; index++) {
        if (!udp_bindings[index].used) {
            free_index = index;
            break;
        }
    }
    if (free_index < 0) return NULL;
    struct udp_pcb *pcb = udp_new_ip_type(IPADDR_TYPE_V6);
    if (pcb == NULL) return NULL;
    ip_addr_t any;
    ip_addr_set_any(1, &any);
    if (udp_bind(pcb, &any, port) != ERR_OK) {
        udp_remove(pcb);
        return NULL;
    }
    udp_recv(pcb, on_udp, NULL);
    udp_bindings[free_index].used = 1;
    udp_bindings[free_index].port = port;
    udp_bindings[free_index].pcb = pcb;
    return pcb;
}

static err_t output_ip6(struct netif *netif, struct pbuf *p, const ip6_addr_t *dest) {
    (void) netif;
    (void) dest;
    if (!enqueue_pbuf(USERSPACE_EVENT_PACKET, -1, 0, 0, 0, NULL, p)) return ERR_MEM;
    return ERR_OK;
}

static err_t netif_init_cb(struct netif *netif) {
    netif->name[0] = 'c';
    netif->name[1] = 'p';
    netif->output_ip6 = output_ip6;
    netif->mtu = 1500;
    return ERR_OK;
}

static void address_from_bytes(ip6_addr_t *addr, const uint8_t bytes[16]) {
    memset(addr, 0, sizeof(*addr));
    memcpy(addr->addr, bytes, 16);
}

int userspace_stack_start(const uint8_t address[16]) {
    pthread_mutex_lock(&lock);
    if (started) {
        pthread_mutex_unlock(&lock);
        return 1;
    }
    if (!lwip_ready) {
        lwip_init();
        memset(&nif, 0, sizeof(nif));
        if (netif_add(&nif, NULL, netif_init_cb, ip6_input) == NULL) {
            pthread_mutex_unlock(&lock);
            return 0;
        }
        lwip_ready = 1;
    }
    ip6_addr_t local;
    address_from_bytes(&local, address);
    netif_ip6_addr_set(&nif, 0, &local);
    netif_ip6_addr_set_state(&nif, 0, IP6_ADDR_PREFERRED);
    netif_set_up(&nif);
    netif_set_link_up(&nif);
    netif_set_default(&nif);
    started = 1;
    pthread_mutex_unlock(&lock);
    return 1;
}

void userspace_stack_stop(void) {
    pthread_mutex_lock(&lock);
    started = 0;
    for (int index = 0; index < SLOT_COUNT; index++) {
        if (slots[index].pcb != NULL) tcp_abort(slots[index].pcb);
        slots[index].pcb = NULL;
        slots[index].used = 0;
    }
    for (int index = 0; index < UDP_COUNT; index++) {
        if (udp_bindings[index].pcb != NULL) udp_remove(udp_bindings[index].pcb);
        udp_bindings[index].pcb = NULL;
        udp_bindings[index].used = 0;
    }
    while (event_count > 0) {
        free(events[event_head].data);
        event_head = (event_head + 1) % EVENT_COUNT;
        event_count--;
    }
    pthread_mutex_unlock(&lock);
}

void userspace_stack_input(const uint8_t *packet, int length) {
    pthread_mutex_lock(&lock);
    if (!started || length <= 0) {
        pthread_mutex_unlock(&lock);
        return;
    }
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t) length, PBUF_POOL);
    if (p == NULL) {
        pthread_mutex_unlock(&lock);
        return;
    }
    pbuf_take(p, packet, (u16_t) length);
    if (ip6_input(p, &nif) != ERR_OK) {
        /* ip6_input frees the pbuf on failure in current lwIP. */
    }
    pthread_mutex_unlock(&lock);
}

int userspace_stack_poll(struct userspace_event *event) {
    pthread_mutex_lock(&lock);
    if (event_count == 0) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    struct queued_event queued = events[event_head];
    event_head = (event_head + 1) % EVENT_COUNT;
    event_count--;
    pthread_mutex_unlock(&lock);
    event->type = queued.type;
    event->id = queued.id;
    event->generation = queued.generation;
    event->local_port = queued.local_port;
    event->remote_port = queued.remote_port;
    memcpy(event->address, queued.address, 16);
    event->data = queued.data;
    event->length = queued.length;
    return 1;
}

int userspace_stack_event_count(void) {
    pthread_mutex_lock(&lock);
    int count = queued_total;
    pthread_mutex_unlock(&lock);
    return count;
}

static struct slot *slot_by_id(int id, int generation) {
    if (id < 0 || id >= SLOT_COUNT || !slots[id].used || slots[id].generation != generation) return NULL;
    return &slots[id];
}

int userspace_stack_write(int id, int generation, const uint8_t *data, int length) {
    pthread_mutex_lock(&lock);
    struct slot *slot = slot_by_id(id, generation);
    if (slot == NULL || slot->pcb == NULL) {
        pthread_mutex_unlock(&lock);
        return -1;
    }
    u16_t space = tcp_sndbuf(slot->pcb);
    if (space == 0 || length <= 0) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    u16_t chunk = (u16_t) length;
    if (chunk > space) chunk = space;
    err_t err = tcp_write(slot->pcb, data, chunk, TCP_WRITE_FLAG_COPY);
    if (err == ERR_MEM) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    if (err != ERR_OK) {
        pthread_mutex_unlock(&lock);
        return -1;
    }
    tcp_output(slot->pcb);
    pthread_mutex_unlock(&lock);
    return (int) chunk;
}

void userspace_stack_recved(int id, int generation, int length) {
    pthread_mutex_lock(&lock);
    struct slot *slot = slot_by_id(id, generation);
    while (slot != NULL && slot->pcb != NULL && length > 0) {
        u16_t step = length > 65535 ? 65535 : (u16_t) length;
        tcp_recved(slot->pcb, step);
        length -= step;
    }
    pthread_mutex_unlock(&lock);
}

void userspace_stack_shutdown(int id, int generation) {
    pthread_mutex_lock(&lock);
    struct slot *slot = slot_by_id(id, generation);
    if (slot != NULL && slot->pcb != NULL) {
        struct tcp_pcb *pcb = slot->pcb;
        detach(pcb);
        slot_release(slot);
        if (tcp_shutdown(pcb, 0, 1) != ERR_OK) tcp_abort(pcb);
    }
    pthread_mutex_unlock(&lock);
}

void userspace_stack_abort(int id, int generation) {
    pthread_mutex_lock(&lock);
    struct slot *slot = slot_by_id(id, generation);
    if (slot != NULL && slot->pcb != NULL) {
        struct tcp_pcb *pcb = slot->pcb;
        detach(pcb);
        slot_release(slot);
        tcp_abort(pcb);
    }
    pthread_mutex_unlock(&lock);
}

int userspace_stack_send_udp(int source_port, const uint8_t destination[16], int destination_port,
                             const uint8_t *data, int length) {
    pthread_mutex_lock(&lock);
    if (!started || source_port <= 0 || destination_port <= 0 || length < 0) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    struct udp_pcb *pcb = udp_for_port((u16_t) source_port);
    if (pcb == NULL) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    struct pbuf *p = pbuf_alloc(PBUF_TRANSPORT, (u16_t) length, PBUF_RAM);
    if (p == NULL) {
        pthread_mutex_unlock(&lock);
        return 0;
    }
    if (length > 0) pbuf_take(p, data, (u16_t) length);
    ip_addr_t dest;
    memset(&dest, 0, sizeof(dest));
    IP_SET_TYPE_VAL(dest, IPADDR_TYPE_V6);
    address_from_bytes(ip_2_ip6(&dest), destination);
    ip6_addr_assign_zone(ip_2_ip6(&dest), IP6_UNICAST, &nif);
    err_t err = udp_sendto(pcb, p, &dest, (u16_t) destination_port);
    pbuf_free(p);
    pthread_mutex_unlock(&lock);
    return err == ERR_OK;
}

void userspace_stack_timeouts(void) {
    pthread_mutex_lock(&lock);
    if (started) sys_check_timeouts();
    pthread_mutex_unlock(&lock);
}
