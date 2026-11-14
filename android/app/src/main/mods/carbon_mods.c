// Optional Carbon mods for the launcher switches. SPDX-License-Identifier: GPL-3.0-only
//
// Loaded by GameActivity before libmain.so, and only when a switch is on (env CARBON_MODS = "unlock,money,").
// Linked with -z global: libmain.so's relocations to sub_<PPC address> then bind to the definitions here
// instead of to its own weak ones; the original body stays reachable as __imp__sub_<address>.
#define _GNU_SOURCE  // process_vm_readv
#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <stdarg.h>
#include <stdio.h>

// Logcat is flooded by the renderer and pruned per app, so every line also goes to carbon_mods.log in the
// executable folder (REX_APP_FOLDER, files/nfscarbon/user).
static void LOG(const char* format, ...) {
    char line[256];
    va_list args;
    va_start(args, format);
    vsnprintf(line, sizeof line, format, args);
    va_end(args);
    __android_log_write(ANDROID_LOG_INFO, "CarbonMods", line);
    const char* folder = getenv("REX_APP_FOLDER");
    char path[512];
    snprintf(path, sizeof path, "%s/carbon_mods.log", folder ? folder : ".");
    FILE* file = fopen(path, "a");
    if (file) { fprintf(file, "%s\n", line); fclose(file); }
}

static int unlock_all, infinite_money;

// ---- Guest memory --------------------------------------------------------------------------------------------
// librexruntime.so exports rex::Runtime::instance() and Runtime::virtual_membase() const. The title image (a PE
// file) sits at guest 0x82000000; until it is mapped, reads go through process_vm_readv so they fail instead of
// faulting.
#include <pthread.h>
#include <sys/uio.h>
#include <unistd.h>

#define IMAGE_BASE 0x82000000u

static int guest_read(const uint8_t* base, uint32_t address, void* out, size_t size) {
    struct iovec local = {out, size}, remote = {(void*)(base + address), size};
    return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == (ssize_t)size;
}

/** Waits (up to ~2 minutes) until the runtime exists and the image is in guest memory; NULL on timeout. */
static uint8_t* wait_for_image(void) {
    void* (*instance)(void) = NULL;
    uint8_t* (*membase)(const void*) = NULL;
    for (int i = 0; i < 1200; i++, usleep(100000)) {
        if (!instance || !membase) {
            // librexruntime.so is loaded after this library, as a dependency of libmain.so, so not RTLD_GLOBAL:
            // RTLD_DEFAULT does not see it, its own handle does.
            void* runtime_lib = dlopen("librexruntime.so", RTLD_NOW | RTLD_NOLOAD);
            if (!runtime_lib) continue;
            instance = (void* (*)(void))dlsym(runtime_lib, "_ZN3rex7Runtime8instanceEv");
            membase = (uint8_t* (*)(const void*))dlsym(runtime_lib, "_ZNK3rex7Runtime15virtual_membaseEv");
            if (instance && membase) LOG("runtime symbols found after %d ms", i * 100);
            continue;
        }
        void* runtime = instance();
        uint8_t* base = runtime ? membase(runtime) : NULL;
        char mz[2];
        if (base && guest_read(base, IMAGE_BASE, mz, 2) && mz[0] == 'M' && mz[1] == 'Z') return base;
    }
    LOG("guest image not found (runtime symbols %s)", instance && membase ? "found" : "missing");
    return NULL;
}

// ---- Mods ----------------------------------------------------------------------------------------------------
// Addresses belong to Collector's Edition (Europe), xex 0.0.0.11, as recompiled by upstream; M4 checks them first.

/** Guest code words that must match before anything is written (big-endian PPC instructions). */
struct expect { uint32_t address, word; };

static int matches(const uint8_t* base, const struct expect* list, int count, const char* what) {
    for (int i = 0; i < count; i++) {
        uint8_t b[4];
        if (!guest_read(base, list[i].address, b, 4)) return 0;
        uint32_t word = (uint32_t)b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3];
        if (word != list[i].word) {
            LOG("%s: %08x holds %08x, expected %08x: other game version, mod stays off", what, list[i].address,
                word, list[i].word);
            return 0;
        }
    }
    return 1;
}

// M1: the debug byte the PC version calls UnlockAllThings (guest 0x82C6503C, .data). Some 14 checks start with
// "lbz r11,0x503c(r11); cmplwi r11,0; beq ...; li r3,1": car, part and vinyl locks return "unlocked" when it is
// set. Nothing in the save changes, so switching the mod off brings the locks back. The game itself writes the
// byte in one place (0x822250FC), so it is set again every 250 ms.
#define UNLOCK_ALL_THINGS 0x82C6503Cu
static const struct expect unlock_code[] = {
    {0x821E652C, 0x3D6082C6},  // lis r11,0x82C6
    {0x821E6538, 0x896B503C},  // lbz r11,0x503C(r11)
    {0x821E73C0, 0x896B503C},
    {0x821EDBE0, 0x896B503C},
    {0x822250FC, 0x996A503C},  // stb r11,0x503C(r10)
};

// M2: the career cash is a big-endian s32 at +0x58 in one object of a linked list (found by memory search on the OP:
// 15,000 at the start of a career). Each node: +0 vtable, +4 next link, +8 previous link, +0xC name hash; links point
// at the +4 of the next node. The list head is the object at [0x82C6431C]. The cash object has vtable 0x8206FC98 and
// hash 0x885B4DDC. Walked again every round, so a reloaded profile is followed too. Below 1,000,000 the cash is
// topped up to 9,999,999; that ends up in the save (wanted).
#define CASH_LIST_HEAD 0x82C6431Cu
#define CASH_VTABLE 0x8206FC98u
#define CASH_HASH 0x885B4DDCu
#define CASH_OFFSET 0x58u
static const struct expect money_code[] = {
    {0x8206FC98, 0x821DF990},  // first entries of the cash object's vtable
    {0x8206FC9C, 0x8234CD38},
};

static uint32_t be32(const uint8_t* base, uint32_t address) {
    uint8_t b[4];
    if (!address || !guest_read(base, address, b, 4)) return 0;
    return (uint32_t)b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3];
}

/** Guest address of the cash, or 0 while no career profile is loaded. */
static uint32_t find_cash(const uint8_t* base) {
    uint32_t head = be32(base, CASH_LIST_HEAD);
    if (!head) return 0;
    uint32_t link = be32(base, head + 4);
    for (int i = 0; i < 4096 && link && link != head + 4; i++, link = be32(base, link)) {
        uint32_t node = link - 4;
        if (be32(base, node) == CASH_VTABLE && be32(base, node + 0xC) == CASH_HASH) return node + CASH_OFFSET;
    }
    return 0;
}

static void top_up_cash(uint8_t* base) {
    uint32_t cash = find_cash(base);
    if (!cash) return;
    int32_t value = (int32_t)be32(base, cash);
    if (value >= 1000000) return;
    const uint32_t full = 9999999;
    uint8_t* p = base + cash;
    p[0] = full >> 24; p[1] = full >> 16; p[2] = full >> 8; p[3] = full;
    LOG("cash %d -> %u at %08x", value, full, cash);
}

static void* watcher(void* unused) {
    (void)unused;
    uint8_t* base = wait_for_image();
    if (!base) return NULL;
    LOG("guest membase %p", base);
    if (unlock_all && !matches(base, unlock_code, sizeof unlock_code / sizeof *unlock_code, "unlock")) unlock_all = 0;
    if (infinite_money && !matches(base, money_code, sizeof money_code / sizeof *money_code, "money")) infinite_money = 0;
    if (!unlock_all && !infinite_money) return NULL;
    LOG("active: unlock=%d money=%d", unlock_all, infinite_money);
    for (;; usleep(250000)) {
        if (unlock_all) base[UNLOCK_ALL_THINGS] = 1;
        if (infinite_money) top_up_cash(base);
    }
}

__attribute__((constructor)) static void carbon_mods_init(void) {
    const char* mods = getenv("CARBON_MODS");
    unlock_all = mods && strstr(mods, "unlock") != NULL;
    infinite_money = mods && strstr(mods, "money") != NULL;
    LOG("loaded: unlock=%d money=%d", unlock_all, infinite_money);
    pthread_t thread;
    if (pthread_create(&thread, NULL, watcher, NULL) == 0) pthread_detach(thread);
}
