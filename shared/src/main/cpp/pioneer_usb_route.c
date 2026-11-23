/* Device-specific USB source watchdog. No PCM, microphone or persistent writes. */
#include <dlfcn.h>
#include <dirent.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <time.h>

static volatile sig_atomic_t stopped;
static void stop(int s) { (void)s; stopped = 1; }
static long long now_ms(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
static int storage_present(void) {
    DIR *dir = opendir("/sys/bus/usb/devices");
    if (!dir) return 0;
    struct dirent *e; int found = 0;
    while ((e = readdir(dir))) {
        char path[512]; unsigned int kind = 0;
        snprintf(path, sizeof(path), "/sys/bus/usb/devices/%s/bInterfaceClass", e->d_name);
        FILE *f = fopen(path, "r");
        if (f) { if (fscanf(f, "%x", &kind) == 1 && kind == 8) found = 1; fclose(f); }
        if (found) break;
    }
    closedir(dir); return found;
}
int main(void) {
    void *lib = dlopen("/system/lib/libSourceSts.so", RTLD_NOW);
    if (!lib) { fprintf(stderr, "USB watchdog: %s\n", dlerror()); return 2; }
    typedef void (*status_fn)(void *, int, int, int);
    status_fn set = (status_fn)dlsym(lib, "_ZN12MediaLibrary12LibSourceSts16setUsb1SourceStsENS_16SOURCE_ON_STATUSENS_30SOURCE_STS_USB1_CONNECT_DEVICEENS_20SOURCE_STS_PLAY_PARTE");
    if (!set || storage_present()) { dlclose(lib); return 3; }
    signal(SIGTERM, stop); signal(SIGINT, stop); signal(SIGHUP, SIG_IGN);
    /* This firmware's LibSourceSts constructor/destructor are empty. */
    unsigned int object[8] = {0};
    set(object, 2, 2, 1);
    printf("USB_READY %d\n", getpid()); fflush(stdout);
    long long heartbeat = now_ms(); int relinquish = 0;
    while (!stopped && now_ms() - heartbeat < 6000) {
        struct pollfd p = { .fd = STDIN_FILENO, .events = POLLIN };
        int ready = poll(&p, 1, 1000);
        if (ready > 0 && (p.revents & (POLLIN | POLLHUP | POLLERR))) {
            char b[64]; ssize_t n = read(STDIN_FILENO, b, sizeof(b));
            if (n <= 0) break;
            for (ssize_t i = 0; i < n; i++) {
                if (b[i] == 'h') heartbeat = now_ms();
                if (b[i] == 'q') stopped = 1;
                if (b[i] == 'r') { relinquish = 1; stopped = 1; }
            }
        }
        if (storage_present()) { relinquish = 1; break; }
    }
    if (!relinquish && !storage_present()) set(object, 0, 1, 0);
    puts(relinquish ? "USB_RELINQUISHED" : "USB_RESET"); fflush(stdout);
    dlclose(lib); return 0;
}
