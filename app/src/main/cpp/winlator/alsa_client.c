#include <aaudio/AAudio.h>
#include <jni.h>

#define WAIT_COMPLETION_TIMEOUT 100 * 1000000L

#include <pthread.h> // For threading (mutex, condition variables)
#include <stdint.h>  // For standard integer types like uint8_t

#include <malloc.h>      // For malloc/calloc
#include <android/log.h> // For logging

#define LOG_TAG "AlsaClientJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#include <unistd.h> // For usleep()

#include <string.h> // For memcpy

// ===================================================================
//
//  PACER (VIRTUAL SINK) IMPLEMENTATION
//
// ===================================================================

/**
 * This struct holds all the state for our simulated audio device (the "pacer").
 * It manages a ring buffer and a consumer thread to create back-pressure.
 */
typedef struct {
    // --- Ring Buffer ---
    uint8_t *buffer;          // The pointer to the actual memory for our audio buffer.
    size_t capacity_bytes;    // The total size of the buffer in bytes.
    size_t write_pos_bytes;   // The position where the next write should start.
    size_t read_pos_bytes;    // The position where the consumer thread should read from.
    size_t available_bytes;   // How many bytes of data are currently in the buffer.

    // --- Audio Properties ---
    int sample_rate;          // The sample rate (e.g., 44100), needed for the consumer thread's timing.
    int frame_size_bytes;     // The size of a single audio frame in bytes (e.g., 4 bytes for 16-bit stereo).

    // --- Threading and State Management ---
    pthread_t consumer_thread; // The handle to our background consumer thread.
    pthread_mutex_t mutex;     // The lock to make our buffer thread-safe.
    pthread_cond_t cond_not_full;  // A condition variable to signal when the buffer has space.
    pthread_cond_t cond_not_empty; // A condition variable to signal when the buffer has data.
    volatile int running;      // The state of the pacer: 0=stopped, 1=running, 2=paused.
} PacerContext;



enum Format {U8, S16LE, S16BE, FLOATLE, FLOATBE};

static aaudio_format_t toAAudioFormat(int format) {
    switch (format) {
        case FLOATLE:
        case FLOATBE:
            return AAUDIO_FORMAT_PCM_FLOAT;
        case U8:
            return AAUDIO_FORMAT_UNSPECIFIED;
        case S16LE:
        case S16BE:
        default:
            return AAUDIO_FORMAT_PCM_I16;
    }
}

/**
 * Helper function to calculate the size of a single audio frame in bytes.
 * This is needed to correctly calculate the total buffer size.
 */
static int get_bytes_per_frame(int format, int channelCount) {
    int bytes_per_sample = 0;
    switch (format) {
        case U8:
            bytes_per_sample = 1;
            break;
        case S16LE:
        case S16BE:
            bytes_per_sample = 2;
            break;
        case FLOATLE:
        case FLOATBE:
            bytes_per_sample = 4;
            break;
    }
    return bytes_per_sample * channelCount;
}

/**
 * This is the main function for our consumer thread. It runs in the background.
 * Its only job is to "consume" data from the ring buffer at a rate that
 * matches the audio sample rate, simulating a real audio device.
 */
/**
 * MODIFICATION: This is the new, more precise consumer thread function.
 * It uses a monotonic clock to stay in sync and avoid timing drift.
 */
#include <sched.h>

void *pacer_consumer_thread_func(void *arg) {
    PacerContext *ctx = (PacerContext*)arg;

    struct sched_param schedParams;
    schedParams.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &schedParams);

    LOGI("Pacer consumer thread started with high-precision timing and real-time priority.");


    struct timespec next_wakeup_time;
    clock_gettime(CLOCK_MONOTONIC, &next_wakeup_time);

    while (ctx->running != 0) {
        pthread_mutex_lock(&ctx->mutex);

        while (ctx->available_bytes == 0 && ctx->running == 1) {
            pthread_cond_wait(&ctx->cond_not_empty, &ctx->mutex);
            if (ctx->running != 1) break;
        }

        if (ctx->running != 1) {
            pthread_mutex_unlock(&ctx->mutex);
            continue;
        }

        // Consume a small, consistent chunk of audio data (e.g., 5ms)
        int consume_chunk_frames = ctx->sample_rate / 100; // 10ms
        int consume_chunk_bytes = consume_chunk_frames * ctx->frame_size_bytes;
        if (consume_chunk_bytes == 0) consume_chunk_bytes = ctx->frame_size_bytes; // Consume at least one frame
        if (consume_chunk_bytes > ctx->available_bytes) {
            consume_chunk_bytes = ctx->available_bytes;
        }

        ctx->read_pos_bytes = (ctx->read_pos_bytes + consume_chunk_bytes) % ctx->capacity_bytes;
        ctx->available_bytes -= consume_chunk_bytes;

        // Calculate the duration of the consumed audio chunk in nanoseconds
        long duration_ns = (long)(((double)consume_chunk_bytes / ctx->frame_size_bytes / ctx->sample_rate) * 1000000000.0);

        // Schedule the next wakeup time
        next_wakeup_time.tv_nsec += duration_ns;
        if (next_wakeup_time.tv_nsec >= 1000000000) {
            next_wakeup_time.tv_sec++;
            next_wakeup_time.tv_nsec -= 1000000000;
        }

        pthread_cond_broadcast(&ctx->cond_not_full);
        pthread_mutex_unlock(&ctx->mutex);

        // Sleep until the calculated next wakeup time
        struct timespec current_time;
        clock_gettime(CLOCK_MONOTONIC, &current_time);

        if ((current_time.tv_sec > next_wakeup_time.tv_sec) ||
            (current_time.tv_sec == next_wakeup_time.tv_sec && current_time.tv_nsec > next_wakeup_time.tv_nsec)) {
            // We're late; immediately schedule next wakeup from now
            next_wakeup_time = current_time;
        }

        long sleep_ns = (next_wakeup_time.tv_sec - current_time.tv_sec) * 1000000000L + (next_wakeup_time.tv_nsec - current_time.tv_nsec);

        if (sleep_ns > 0) {
            struct timespec sleep_duration = {0, sleep_ns};
            nanosleep(&sleep_duration, NULL);
        }
    }

    LOGI("Pacer consumer thread exiting.");
    return NULL;
}

static AAudioStream *aaudioCreate(int32_t format, int8_t channelCount, int32_t sampleRate, int32_t bufferSize) {
    aaudio_result_t result;
    AAudioStreamBuilder *builder;
    AAudioStream *stream;

    result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK) return NULL;

    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, toAAudioFormat(format));
    AAudioStreamBuilder_setChannelCount(builder, channelCount);
    AAudioStreamBuilder_setSampleRate(builder, sampleRate);

    result = AAudioStreamBuilder_openStream(builder, &stream);
    if (result != AAUDIO_OK) {
        AAudioStreamBuilder_delete(builder);
        return NULL;
    }

    AAudioStream_setBufferSizeInFrames(stream, bufferSize);

    result = AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK) return NULL;

    return stream;
}

static int aaudioWrite(AAudioStream *aaudioStream, void *buffer, int numFrames) {
    aaudio_result_t framesWritten = AAudioStream_write(aaudioStream, buffer, numFrames, WAIT_COMPLETION_TIMEOUT);
    return framesWritten;
}

static void aaudioStart(AAudioStream *aaudioStream) {
    AAudioStream_requestStart(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STARTING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioStop(AAudioStream *aaudioStream) {
    AAudioStream_requestStop(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_STOPPING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioPause(AAudioStream *aaudioStream) {
    AAudioStream_requestPause(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_PAUSING, NULL, WAIT_COMPLETION_TIMEOUT);
}

static void aaudioFlush(AAudioStream *aaudioStream) {
    AAudioStream_requestFlush(aaudioStream);
    AAudioStream_waitForStateChange(aaudioStream, AAUDIO_STREAM_STATE_FLUSHING, NULL, WAIT_COMPLETION_TIMEOUT);
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedCreate(JNIEnv *env, jobject obj, jint format,
                                                             jbyte channelCount, jint sampleRate, jint bufferSize) {
    // 1. Allocate memory for the context struct. We use calloc to ensure all fields are zero-initialized.
    PacerContext *ctx = (PacerContext*)calloc(1, sizeof(PacerContext));
    if (!ctx) {
        LOGI("Failed to allocate memory for PacerContext");
        return 0; // Return 0 (null) on failure.
    }

    // 2. Calculate and store the audio properties passed in from Java.
    ctx->frame_size_bytes = get_bytes_per_frame(format, channelCount);
    ctx->capacity_bytes = bufferSize * ctx->frame_size_bytes;
    ctx->sample_rate = sampleRate;

    // 3. Allocate the actual memory for our ring buffer.
    ctx->buffer = (uint8_t*)malloc(ctx->capacity_bytes);
    if (!ctx->buffer) {
        LOGI("Failed to allocate memory for pacer ring buffer");
        free(ctx); // Important: Clean up the context struct we already allocated.
        return 0;
    }

    // 4. Initialize the threading components. These are essential for thread safety.
    pthread_mutex_init(&ctx->mutex, NULL);
    pthread_cond_init(&ctx->cond_not_full, NULL);
    pthread_cond_init(&ctx->cond_not_empty, NULL);

    // 5. The 'running' state is already 0 from calloc, indicating it's initially stopped.
    // ctx->running = 0;

    LOGI("Pacer context created successfully. Buffer size: %zu bytes.", ctx->capacity_bytes);

    // 6. Return the memory address of our new struct, cast to a jlong so Java can store it.
    return (jlong)ctx;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_create(JNIEnv *env, jobject obj, jint format,
                                                    jbyte channelCount, jint sampleRate, jint bufferSize) {
    return (jlong)aaudioCreate(format, channelCount, sampleRate, bufferSize);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedWrite(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                                            jint numFrames) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx || ctx->running != 1) {
        return -1;
    }

    void *data = (*env)->GetDirectBufferAddress(env, buffer);
    int bytes_to_write = numFrames * ctx->frame_size_bytes;

    pthread_mutex_lock(&ctx->mutex);

    while ((ctx->capacity_bytes - ctx->available_bytes) < bytes_to_write && ctx->running == 1) {
        pthread_cond_wait(&ctx->cond_not_full, &ctx->mutex);
    }

    if (ctx->running != 1) {
        pthread_mutex_unlock(&ctx->mutex);
        return -1;
    }

    size_t remaining_capacity = ctx->capacity_bytes - ctx->write_pos_bytes;

    if (remaining_capacity >= bytes_to_write) {
        memcpy(ctx->buffer + ctx->write_pos_bytes, data, bytes_to_write);
        ctx->write_pos_bytes = (ctx->write_pos_bytes + bytes_to_write) % ctx->capacity_bytes;
    } else {
        memcpy(ctx->buffer + ctx->write_pos_bytes, data, remaining_capacity);
        memcpy(ctx->buffer, (uint8_t*)data + remaining_capacity, bytes_to_write - remaining_capacity);
        ctx->write_pos_bytes = bytes_to_write - remaining_capacity; // Already modulo by logic
    }

    ctx->available_bytes += bytes_to_write;

    pthread_cond_signal(&ctx->cond_not_empty);
    pthread_mutex_unlock(&ctx->mutex);

    return numFrames;
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_write(JNIEnv *env, jobject obj, jlong streamPtr, jobject buffer,
                                                   jint numFrames) {
    AAudioStream *aaudioStream = (AAudioStream*)streamPtr;
    if (aaudioStream) {
        return aaudioWrite(aaudioStream, (*env)->GetDirectBufferAddress(env, buffer), numFrames);
    }
    else return -1;
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedStart(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // Lock the mutex to safely change the state.
    pthread_mutex_lock(&ctx->mutex);

    // Only start the thread if it's currently stopped (state 0).
    if (ctx->running == 0) {
        ctx->running = 1; // Set state to "running".
        // Create the background thread, passing it our consumer function and the context.
        pthread_create(&ctx->consumer_thread, NULL, pacer_consumer_thread_func, ctx);
    }
    else if (ctx->running == 2) { // If it was paused...
        ctx->running = 1; //...just set the state back to "running".
        // The consumer thread is still alive but waiting, so we signal it to wake up.
        pthread_cond_broadcast(&ctx->cond_not_empty);
    }

    // Unlock the mutex.
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT jint JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_start(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) {
        return AAudioStream_requestStart(aaudioStream);
    }
    return -1; // Or some other error code indicating null pointer
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedFlush(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // Lock the mutex to safely reset the buffer state.
    pthread_mutex_lock(&ctx->mutex);

    // Reset all buffer pointers and counters. This effectively discards all data.
    ctx->read_pos_bytes = 0;
    ctx->write_pos_bytes = 0;
    ctx->available_bytes = 0;

    // Since the buffer is now empty, we signal any waiting writer thread that there is space.
    pthread_cond_broadcast(&ctx->cond_not_full);

    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_flush(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioFlush(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedPause(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    // Safety check: We can only pause if the stream is currently running.
    if (!ctx || ctx->running != 1) {
        return;
    }

    // Lock the mutex to safely modify the state.
    pthread_mutex_lock(&ctx->mutex);

    // Set the state to "paused".
    ctx->running = 2;

    // It's crucial to wake up any threads that might be waiting.
    // If simulatedWrite is waiting for space, or the consumer is waiting for data,
    // they need to wake up to see that the state has changed to "paused".
    pthread_cond_broadcast(&ctx->cond_not_full);
    pthread_cond_broadcast(&ctx->cond_not_empty);

    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_pause(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioPause(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedStop(JNIEnv *env, jobject obj, jlong streamPtr) {
    // "Stopping" the pacer is functionally the same as "pausing" it.
    // The consumer thread remains alive but idle, ready to be started again later.
    // The real cleanup and thread termination will happen in simulatedClose.
    // So, we can simply call the pause function.
    Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedPause(env, obj, streamPtr);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_stop(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) aaudioStop(aaudioStream);
}

JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_simulatedClose(JNIEnv *env, jobject obj, jlong streamPtr) {
    PacerContext *ctx = (PacerContext*)streamPtr;
    if (!ctx) return;

    // --- Begin graceful shutdown of the consumer thread ---

    // 1. Lock the mutex to safely change the state.
    pthread_mutex_lock(&ctx->mutex);

    // 2. Check if the thread is already shut down. If so, just unlock and return.
    if (ctx->running == 0) {
        pthread_mutex_unlock(&ctx->mutex);
        return;
    }

    // 3. Set the state to "stopped". This is the signal for the consumer thread's loop to exit.
    ctx->running = 0;

    // 4. Wake up the consumer thread in case it's waiting on a condition. It needs to
    //    wake up to see that ctx->running is now 0 and exit its loop.
    pthread_cond_broadcast(&ctx->cond_not_empty);
    pthread_cond_broadcast(&ctx->cond_not_full);

    // 5. Unlock the mutex so the consumer thread can acquire it to finish its loop.
    pthread_mutex_unlock(&ctx->mutex);

    // --- Wait for thread and clean up resources ---

    // 6. Wait for the consumer thread to finish executing completely. This is a crucial
    //    blocking call that prevents us from freeing memory while it's still in use.
    if (ctx->consumer_thread) {
        ctx->running = 0;
        pthread_cond_broadcast(&ctx->cond_not_empty);
        pthread_cond_broadcast(&ctx->cond_not_full);
        pthread_join(ctx->consumer_thread, NULL);
        ctx->consumer_thread = 0;
    }

    // 7. Now that the thread is gone, we can safely destroy the threading tools.
    pthread_mutex_destroy(&ctx->mutex);
    pthread_cond_destroy(&ctx->cond_not_full);
    pthread_cond_destroy(&ctx->cond_not_empty);

    // 8. Finally, free all the memory we allocated in simulatedCreate.
    free(ctx->buffer); // Free the ring buffer memory.
    free(ctx);         // Free the context struct itself.

    LOGI("Pacer context destroyed successfully.");
}


JNIEXPORT void JNICALL
Java_com_winlator_cmod_alsaserver_ALSAClient_close(JNIEnv *env, jobject obj, jlong mirrorStreamPtr) {
    AAudioStream *aaudioStream = (AAudioStream*)mirrorStreamPtr;
    if (aaudioStream) AAudioStream_close(aaudioStream);
}