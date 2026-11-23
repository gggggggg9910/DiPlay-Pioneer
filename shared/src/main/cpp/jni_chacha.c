#include <jni.h>

#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Linked to the bundled Conscrypt BoringSSL. Its JCA Cipher path is about 1 MB/s
 * on this Cortex-A9; EVP_AEAD_CTX_open on the same library is about 66 MB/s. */
typedef struct {
    uint8_t storage[2048] __attribute__((aligned(16)));
} AeadCtx;

extern int EVP_AEAD_CTX_init(AeadCtx *ctx, const void *aead, const uint8_t *key, size_t key_len,
                             size_t tag_len, const void *engine);
extern int EVP_AEAD_CTX_open(const AeadCtx *ctx, uint8_t *out, size_t *out_len, size_t max_out,
                             const uint8_t *nonce, size_t nonce_len, const uint8_t *in, size_t in_len,
                             const uint8_t *ad, size_t ad_len);
extern int EVP_AEAD_CTX_seal(const AeadCtx *ctx, uint8_t *out, size_t *out_len, size_t max_out,
                             const uint8_t *nonce, size_t nonce_len, const uint8_t *in, size_t in_len,
                             const uint8_t *ad, size_t ad_len);
extern void EVP_AEAD_CTX_cleanup(AeadCtx *ctx);
extern const void *EVP_aead_chacha20_poly1305(void);
extern size_t EVP_AEAD_nonce_length(const void *aead);

static void throw_bad_tag(JNIEnv *env) {
    jclass type = (*env)->FindClass(env, "javax/crypto/AEADBadTagException");
    if (type) (*env)->ThrowNew(env, type, "ChaCha20-Poly1305 tag mismatch");
}

typedef struct {
    AeadCtx ctx;
    uint8_t key[32];
    const void *aead;
    int ready;
    uint8_t *output;
    size_t output_capacity;
} TlsAead;

static pthread_key_t tls_key;
static pthread_once_t tls_once = PTHREAD_ONCE_INIT;

static void tls_destroy(void *value) {
    TlsAead *slot = value;
    if (slot && slot->ready) EVP_AEAD_CTX_cleanup(&slot->ctx);
    if (slot) free(slot->output);
    free(slot);
}

static void tls_create(void) { pthread_key_create(&tls_key, tls_destroy); }

static TlsAead *tls_slot(void) {
    pthread_once(&tls_once, tls_create);
    TlsAead *slot = pthread_getspecific(tls_key);
    if (!slot) {
        slot = calloc(1, sizeof *slot);
        if (slot) pthread_setspecific(tls_key, slot);
    }
    return slot;
}

static int tls_ready(TlsAead *slot, const void *aead, const uint8_t *key) {
    if (slot->ready && slot->aead == aead && memcmp(slot->key, key, 32) == 0) return 1;
    if (slot->ready) EVP_AEAD_CTX_cleanup(&slot->ctx);
    memset(&slot->ctx, 0, sizeof slot->ctx);
    if (!EVP_AEAD_CTX_init(&slot->ctx, aead, key, 32, 16, NULL)) {
        slot->ready = 0;
        return 0;
    }
    memcpy(slot->key, key, 32);
    slot->aead = aead;
    slot->ready = 1;
    return 1;
}

static jbyteArray crypt_reuse(JNIEnv *env, int decrypt, jbyteArray key, jbyteArray nonce,
                        jbyteArray input, jbyteArray aad) {
    const void *aead = EVP_aead_chacha20_poly1305();
    if (!aead || EVP_AEAD_nonce_length(aead) != 12) return NULL;
    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize input_len = (*env)->GetArrayLength(env, input);
    jsize aad_len = aad ? (*env)->GetArrayLength(env, aad) : 0;
    if (key_len != 32 || nonce_len != 12 || input_len < 0 || (decrypt && input_len < 16)) return NULL;
    jsize output_len = decrypt ? input_len - 16 : input_len + 16;
    /* Small fixed inputs do not need ART array pin/copy/release cycles. */
    uint8_t key_bytes[32], nonce_bytes[12];
    (*env)->GetByteArrayRegion(env, key, 0, 32, (jbyte *)key_bytes);
    (*env)->GetByteArrayRegion(env, nonce, 0, 12, (jbyte *)nonce_bytes);
    if ((*env)->ExceptionCheck(env)) return NULL;
    TlsAead *slot = tls_slot();
    if (!slot || !tls_ready(slot, aead, key_bytes)) return NULL;

    /* Reuse native output rather than copying a newly zero-filled Java array into native memory.
     * Keep at most 1 MiB per thread; exceptional large frames use temporary storage. */
    size_t required = output_len > 0 ? (size_t)output_len : 1;
    uint8_t *output = NULL;
    int temporary = required > 1024 * 1024;
    if (temporary) {
        output = malloc(required);
    } else {
        if (slot->output_capacity < required) {
            size_t capacity = slot->output_capacity ? slot->output_capacity : 4096;
            while (capacity < required) capacity *= 2;
            uint8_t *grown = realloc(slot->output, capacity);
            if (!grown) return NULL;
            slot->output = grown;
            slot->output_capacity = capacity;
        }
        output = slot->output;
    }
    if (!output) return NULL;
    jbyte *input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
    jbyte *aad_bytes = aad_len > 0 ? (*env)->GetByteArrayElements(env, aad, NULL) : NULL;
    int attempted = input_bytes && (aad_len == 0 || aad_bytes);
    size_t got = 0;
    int ok = attempted &&
        (decrypt ? EVP_AEAD_CTX_open : EVP_AEAD_CTX_seal)(
            &slot->ctx, output, &got, (size_t)output_len, nonce_bytes, 12,
            (const uint8_t *)input_bytes, (size_t)input_len,
            aad_bytes ? (const uint8_t *)aad_bytes : NULL, (size_t)aad_len);
    if (input_bytes) (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    if (aad_bytes) (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
    jbyteArray result = NULL;
    if (!(*env)->ExceptionCheck(env)) {
        if (ok && got == (size_t)output_len) {
            result = (*env)->NewByteArray(env, output_len);
            if (result && output_len > 0) (*env)->SetByteArrayRegion(env, result, 0, output_len, (const jbyte *)output);
        } else if (attempted && decrypt) {
            throw_bad_tag(env);
        }
    }
    if (temporary) free(output);
    return result;
}

static jbyteArray crypt_legacy(JNIEnv *env, int decrypt, jbyteArray key, jbyteArray nonce,
                        jbyteArray input, jbyteArray aad) {
    const void *aead = EVP_aead_chacha20_poly1305();
    if (!aead || EVP_AEAD_nonce_length(aead) != 12) return NULL;
    jsize key_len = (*env)->GetArrayLength(env, key);
    jsize nonce_len = (*env)->GetArrayLength(env, nonce);
    jsize input_len = (*env)->GetArrayLength(env, input);
    jsize aad_len = aad ? (*env)->GetArrayLength(env, aad) : 0;
    if (key_len != 32 || nonce_len != 12 || input_len < 0 || (decrypt && input_len < 16)) return NULL;
    jsize output_len = decrypt ? input_len - 16 : input_len + 16;
    jbyte *key_bytes = (*env)->GetByteArrayElements(env, key, NULL);
    jbyte *nonce_bytes = (*env)->GetByteArrayElements(env, nonce, NULL);
    jbyte *input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
    jbyte *aad_bytes = aad_len > 0 ? (*env)->GetByteArrayElements(env, aad, NULL) : NULL;
    jbyteArray result = NULL;
    if (key_bytes && nonce_bytes && input_bytes && (aad_len == 0 || aad_bytes)) {
        TlsAead *slot = tls_slot();
        if (slot && tls_ready(slot, aead, (const uint8_t *)key_bytes)) {
            jbyteArray output = (*env)->NewByteArray(env, output_len);
            jbyte *output_bytes = output ? (*env)->GetByteArrayElements(env, output, NULL) : NULL;
            size_t got = 0;
            int ok = output_bytes &&
                (decrypt ? EVP_AEAD_CTX_open : EVP_AEAD_CTX_seal)(
                    &slot->ctx, (uint8_t *)output_bytes, &got, (size_t)output_len,
                    (const uint8_t *)nonce_bytes, 12,
                    (const uint8_t *)input_bytes, (size_t)input_len,
                    aad_bytes ? (const uint8_t *)aad_bytes : NULL, (size_t)aad_len);
            if (output_bytes) (*env)->ReleaseByteArrayElements(env, output, output_bytes, 0);
            if (ok && got == (size_t)output_len) result = output;
            else if (decrypt) throw_bad_tag(env);
        }
    }
    if (key_bytes) (*env)->ReleaseByteArrayElements(env, key, key_bytes, JNI_ABORT);
    if (nonce_bytes) (*env)->ReleaseByteArrayElements(env, nonce, nonce_bytes, JNI_ABORT);
    if (input_bytes) (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    if (aad_bytes) (*env)->ReleaseByteArrayElements(env, aad, aad_bytes, JNI_ABORT);
    return result;
}

/* Selected once, before the first live crypt operation, by the Java lazy comparison. */
static int selected_path = 0; /* 0 legacy, 1 reuse, -1 disable native wrapper */
static jbyteArray crypt(JNIEnv *env, int decrypt, jbyteArray key, jbyteArray nonce,
                        jbyteArray input, jbyteArray aad) {
    if (selected_path < 0) return NULL;
    return (selected_path == 1 ? crypt_reuse : crypt_legacy)(env, decrypt, key, nonce, input, aad);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeOpen(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray input, jbyteArray aad) {
    (void)clazz;
    return crypt(env, 1, key, nonce, input, aad);
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeSeal(
        JNIEnv *env, jclass clazz, jbyteArray key, jbyteArray nonce, jbyteArray input, jbyteArray aad) {
    (void)clazz;
    return crypt(env, 0, key, nonce, input, aad);
}

static int hex_nibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    return -1;
}

static int parse_hex(uint8_t *out, const char *text) {
    size_t n = 0;
    while (text[n] && text[n + 1]) n += 2;
    for (size_t i = 0; i < n; i += 2) {
        int hi = hex_nibble(text[i]);
        int lo = hex_nibble(text[i + 1]);
        if (hi < 0 || lo < 0) return -1;
        out[i / 2] = (uint8_t)((hi << 4) | lo);
    }
    return (int)(n / 2);
}

/* RFC 8439 A.5, the same vector checked on this head unit at 66 MB/s. */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeSelfTest(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const void *aead = EVP_aead_chacha20_poly1305();
    if (!aead || EVP_AEAD_nonce_length(aead) != 12) {
        return (*env)->NewStringUTF(env, "fail nonce");
    }
    uint8_t key[32], nonce[12], aad[12], expect_tag[16];
    if (parse_hex(key, "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f") != 32 ||
        parse_hex(nonce, "070000004041424344454647") != 12 ||
        parse_hex(aad, "50515253c0c1c2c3c4c5c6c7") != 12 ||
        parse_hex(expect_tag, "1ae10b594f09e26a7e902ecbd0600691") != 16) {
        return (*env)->NewStringUTF(env, "fail vector-bytes");
    }
    const char *plain =
        "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    size_t plain_len = strlen(plain);
    uint8_t sealed[160];
    uint8_t opened[160];
    AeadCtx ctx;
    memset(&ctx, 0, sizeof ctx);
    if (!EVP_AEAD_CTX_init(&ctx, aead, key, 32, 16, NULL)) {
        return (*env)->NewStringUTF(env, "fail init");
    }
    size_t sealed_len = 0;
    int sealed_ok = EVP_AEAD_CTX_seal(&ctx, sealed, &sealed_len, sizeof sealed, nonce, 12,
                                      (const uint8_t *)plain, plain_len, aad, sizeof aad);
    size_t opened_len = 0;
    int opened_ok = sealed_ok &&
        EVP_AEAD_CTX_open(&ctx, opened, &opened_len, sizeof opened, nonce, 12, sealed, sealed_len, aad, sizeof aad);
    int tag_ok = sealed_ok && sealed_len == plain_len + 16 &&
        memcmp(sealed + plain_len, expect_tag, 16) == 0;
    int plain_ok = opened_ok && opened_len == plain_len && memcmp(opened, plain, plain_len) == 0;

    enum { N = 64 * 1024 };
    static uint8_t big_in[N];
    static uint8_t big_sealed[N + 16];
    static uint8_t big_out[N];
    memset(big_in, 0x5a, sizeof big_in);
    size_t big_sealed_len = 0;
    int big_ok = EVP_AEAD_CTX_seal(&ctx, big_sealed, &big_sealed_len, sizeof big_sealed, nonce, 12,
                                   big_in, N, aad, sizeof aad);
    const int rounds = 16;
    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (int i = 0; big_ok && i < rounds; i++) {
        size_t got = 0;
        if (!EVP_AEAD_CTX_open(&ctx, big_out, &got, N, nonce, 12, big_sealed, big_sealed_len, aad, sizeof aad) ||
            got != N || big_out[0] != 0x5a) {
            big_ok = 0;
            break;
        }
    }
    clock_gettime(CLOCK_MONOTONIC, &end);
    double sec = (double)(end.tv_sec - start.tv_sec) + (double)(end.tv_nsec - start.tv_nsec) / 1e9;
    double mbps = sec > 0 ? ((double)N * rounds) / (1024.0 * 1024.0) / sec : 0;

    enum { FRAME = 32 * 1024, JNI_ROUNDS = 40 };
    static uint8_t frame_plain[FRAME];
    static uint8_t frame_sealed[FRAME + 16];
    memset(frame_plain, 0xa5, sizeof frame_plain);
    size_t frame_sealed_len = 0;
    int frame_ok = EVP_AEAD_CTX_seal(&ctx, frame_sealed, &frame_sealed_len, sizeof frame_sealed,
                                     nonce, 12, frame_plain, FRAME, aad, sizeof aad);
    EVP_AEAD_CTX_cleanup(&ctx);
    double jni_mbps = -1;
    if (frame_ok && frame_sealed_len == FRAME + 16) {
        jbyteArray jkey = (*env)->NewByteArray(env, 32);
        jbyteArray jnonce = (*env)->NewByteArray(env, 12);
        jbyteArray jaad = (*env)->NewByteArray(env, 12);
        jbyteArray jsealed = (*env)->NewByteArray(env, (jsize)frame_sealed_len);
        if (jkey && jnonce && jaad && jsealed) {
            (*env)->SetByteArrayRegion(env, jkey, 0, 32, (const jbyte *)key);
            (*env)->SetByteArrayRegion(env, jnonce, 0, 12, (const jbyte *)nonce);
            (*env)->SetByteArrayRegion(env, jaad, 0, 12, (const jbyte *)aad);
            (*env)->SetByteArrayRegion(env, jsealed, 0, (jsize)frame_sealed_len, (const jbyte *)frame_sealed);
            struct timespec jni_start, jni_end;
            clock_gettime(CLOCK_MONOTONIC, &jni_start);
            int jni_ok = 1;
            for (int i = 0; i < JNI_ROUNDS; i++) {
                jbyteArray opened_frame = crypt(env, 1, jkey, jnonce, jsealed, jaad);
                if (!opened_frame) {
                    jni_ok = 0;
                    break;
                }
                if (i == 0) {
                    jbyte first = 0, last = 0;
                    (*env)->GetByteArrayRegion(env, opened_frame, 0, 1, &first);
                    (*env)->GetByteArrayRegion(env, opened_frame, FRAME - 1, 1, &last);
                    if ((uint8_t)first != 0xa5 || (uint8_t)last != 0xa5) jni_ok = 0;
                }
                (*env)->DeleteLocalRef(env, opened_frame);
            }
            clock_gettime(CLOCK_MONOTONIC, &jni_end);
            if (jni_ok) {
                double jni_sec = (double)(jni_end.tv_sec - jni_start.tv_sec) +
                    (double)(jni_end.tv_nsec - jni_start.tv_nsec) / 1e9;
                jni_mbps = jni_sec > 0 ? ((double)FRAME * JNI_ROUNDS) / (1024.0 * 1024.0) / jni_sec : 0;
            }
            /* Also exercise rejection through the same JNI wrapper used by video. */
            jclass bad_tag_type = (*env)->FindClass(env, "javax/crypto/AEADBadTagException");
            jbyte changed_tag = (jbyte)(frame_sealed[frame_sealed_len - 1] ^ 1);
            if (bad_tag_type) {
                (*env)->SetByteArrayRegion(env, jsealed, (jsize)frame_sealed_len - 1, 1, &changed_tag);
                jbyteArray rejected = crypt(env, 1, jkey, jnonce, jsealed, jaad);
                jthrowable error = (*env)->ExceptionOccurred(env);
                if (error) (*env)->ExceptionClear(env);
                int rejects_bad_tag = !rejected && error && (*env)->IsInstanceOf(env, error, bad_tag_type);
                tag_ok = tag_ok && rejects_bad_tag;
                if (error) (*env)->DeleteLocalRef(env, error);
                if (rejected) (*env)->DeleteLocalRef(env, rejected);
                (*env)->DeleteLocalRef(env, bad_tag_type);
            } else {
                return NULL; /* Preserve the VM exception instead of calling JNI with it pending. */
            }
        }
    }
    char message[192];
    snprintf(message, sizeof message,
             "%s tag=%s plain=%s bulk=%.1fMiB/s jni=%.1fMiB/s perf=%s",
             (tag_ok && plain_ok && big_ok && jni_mbps >= 0) ? "pass" : "fail",
             tag_ok ? "ok" : "bad", plain_ok ? "ok" : "bad", mbps, jni_mbps,
             (mbps >= 10.0 && jni_mbps >= 20.0) ? "target-met" : "below-target");
    return (*env)->NewStringUTF(env, message);
}


static double monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec * 1e9 + ts.tv_nsec;
}

/* Identical authenticated inputs; AB/BA order, warmup, full plaintext and rejection checks.
 * No live stream nonce/counter/key is read or modified here. */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_nativeCompare(JNIEnv *env, jclass clazz) {
    (void)clazz;
    uint8_t key[32] = {0}, nonce[12] = {0}, aad[16] = {0};
    for (int i = 0; i < 32; i++) key[i] = (uint8_t)(i + 13);
    AeadCtx ctx;
    memset(&ctx, 0, sizeof ctx);
    if (!EVP_AEAD_CTX_init(&ctx, EVP_aead_chacha20_poly1305(), key, 32, 16, NULL)) {
        selected_path = -1;
        return (*env)->NewStringUTF(env, "JniCompare selected=disabled reason=init");
    }
    jbyteArray jkey = (*env)->NewByteArray(env, 32);
    jbyteArray jnonce = (*env)->NewByteArray(env, 12);
    jbyteArray jaad = (*env)->NewByteArray(env, 16);
    if (!jkey || !jnonce || !jaad) { EVP_AEAD_CTX_cleanup(&ctx); return NULL; }
    (*env)->SetByteArrayRegion(env, jkey, 0, 32, (const jbyte *)key);
    (*env)->SetByteArrayRegion(env, jnonce, 0, 12, (const jbyte *)nonce);
    (*env)->SetByteArrayRegion(env, jaad, 0, 16, (const jbyte *)aad);
    double elapsed[2] = {0, 0};
    int valid[2] = {1, 1};
    size_t bytes_tested = 0;
    const int sizes[2] = {4096, 32768};
    const int rounds = 6;
    for (int size_index = 0; size_index < 2; size_index++) {
        int size = sizes[size_index];
        uint8_t *plain = malloc((size_t)size), *sealed = malloc((size_t)size + 16);
        if (!plain || !sealed) { free(plain); free(sealed); valid[0] = valid[1] = 0; break; }
        for (int i = 0; i < size; i++) plain[i] = (uint8_t)(i * 7 + 19);
        nonce[0] = (uint8_t)size_index;
        (*env)->SetByteArrayRegion(env, jnonce, 0, 12, (const jbyte *)nonce);
        size_t sealed_size = 0;
        int ok = EVP_AEAD_CTX_seal(&ctx, sealed, &sealed_size, (size_t)size + 16,
                                  nonce, 12, plain, (size_t)size, aad, 16);
        jbyteArray jin = ok ? (*env)->NewByteArray(env, (jsize)sealed_size) : NULL;
        if (!jin) { free(plain); free(sealed); valid[0] = valid[1] = 0; break; }
        (*env)->SetByteArrayRegion(env, jin, 0, (jsize)sealed_size, (const jbyte *)sealed);
        for (int round = -2; round < rounds; round++) {
            for (int order = 0; order < 2; order++) {
                int candidate = ((round + 2) + order) & 1;
                if (!valid[candidate]) continue;
                double start = monotonic_ns();
                jbyteArray out = (candidate ? crypt_reuse : crypt_legacy)(env, 1, jkey, jnonce, jin, jaad);
                double duration = monotonic_ns() - start;
                if ((*env)->ExceptionCheck(env)) {
                    EVP_AEAD_CTX_cleanup(&ctx); free(plain); free(sealed); return NULL;
                }
                if (!out || (*env)->GetArrayLength(env, out) != size) valid[candidate] = 0;
                else {
                    jbyte *contents = (*env)->GetByteArrayElements(env, out, NULL);
                    if (!contents) { EVP_AEAD_CTX_cleanup(&ctx); free(plain); free(sealed); return NULL; }
                    if (memcmp(contents, plain, (size_t)size) != 0) valid[candidate] = 0;
                    (*env)->ReleaseByteArrayElements(env, out, contents, JNI_ABORT);
                }
                if (out) (*env)->DeleteLocalRef(env, out);
                if (round >= 0) elapsed[candidate] += duration;
            }
        }
        // Each wrapper must reject a bad tag; accepting invalid plaintext disqualifies it.
        jclass bad_tag_type = (*env)->FindClass(env, "javax/crypto/AEADBadTagException");
        if (!bad_tag_type) { EVP_AEAD_CTX_cleanup(&ctx); free(plain); free(sealed); return NULL; }
        jbyte changed = (jbyte)(sealed[sealed_size - 1] ^ 1);
        (*env)->SetByteArrayRegion(env, jin, (jsize)sealed_size - 1, 1, &changed);
        for (int candidate = 0; candidate < 2; candidate++) {
            jbyteArray out = (candidate ? crypt_reuse : crypt_legacy)(env, 1, jkey, jnonce, jin, jaad);
            jthrowable failure = (*env)->ExceptionOccurred(env);
            if (failure) (*env)->ExceptionClear(env);
            if (out || !failure || !(*env)->IsInstanceOf(env, failure, bad_tag_type)) valid[candidate] = 0;
            if (out) (*env)->DeleteLocalRef(env, out);
            if (failure) (*env)->DeleteLocalRef(env, failure);
        }
        (*env)->DeleteLocalRef(env, bad_tag_type);
        (*env)->DeleteLocalRef(env, jin);
        bytes_tested += (size_t)size * rounds;
        free(plain); free(sealed);
    }
    EVP_AEAD_CTX_cleanup(&ctx);
    // Require a 10% margin to switch; a noisy near-tie keeps the original path.
    selected_path = !valid[0] ? (valid[1] ? 1 : -1) : (valid[1] && elapsed[1] < elapsed[0] * 0.90 ? 1 : 0);
    char message[256];
    snprintf(message, sizeof message,
             "JniCompare selected=%s legacyValid=%s reuseValid=%s legacyUs=%.0f reuseUs=%.0f bytes=%zu rounds=%d",
             selected_path < 0 ? "disabled" : selected_path ? "reuse" : "legacy",
             valid[0] ? "true" : "false", valid[1] ? "true" : "false",
             elapsed[0] / 1000, elapsed[1] / 1000, bytes_tested, rounds);
    return (*env)->NewStringUTF(env, message);
}
