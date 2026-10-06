/*
 * A RIFF reader and writer narrow enough to be obviously correct rather than general:
 * 16-bit PCM, one channel. Shared by every harness in this directory so that a difference
 * between two measurements is never a difference in how their WAV files were parsed.
 *
 * The `data` chunk is found by walking the chunk list rather than assumed to be at offset
 * 36, because it is not at 36 in anything written by ffmpeg or by libsndfile.
 */

#ifndef SE_WAV_H
#define SE_WAV_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

typedef struct {
    int32_t  rate;
    uint32_t frames;
    int16_t *pcm;
} wav;

static int rd32(FILE *f, uint32_t *v) { return fread(v, 4, 1, f) == 1; }
static int rd16(FILE *f, uint16_t *v) { return fread(v, 2, 1, f) == 1; }

static int wav_read(const char *path, wav *w)
{
    FILE *f = fopen(path, "rb");
    char tag[5] = {0};
    uint32_t sz;
    uint16_t fmt = 0, ch = 0, bits = 0;
    uint32_t rate = 0;
    int have_fmt = 0;

    if (!f) { fprintf(stderr, "ns48: cannot open %s\n", path); return 0; }

    if (fread(tag, 1, 4, f) != 4 || memcmp(tag, "RIFF", 4) != 0) goto bad;
    if (!rd32(f, &sz)) goto bad;
    if (fread(tag, 1, 4, f) != 4 || memcmp(tag, "WAVE", 4) != 0) goto bad;

    while (fread(tag, 1, 4, f) == 4 && rd32(f, &sz)) {
        long next = ftell(f) + (long)sz + (sz & 1);  /* chunks are word-aligned */

        if (memcmp(tag, "fmt ", 4) == 0) {
            uint16_t u16; uint32_t u32;
            if (!rd16(f, &fmt) || !rd16(f, &ch) || !rd32(f, &rate)) goto bad;
            if (!rd32(f, &u32) || !rd16(f, &u16) || !rd16(f, &bits)) goto bad;
            have_fmt = 1;
        } else if (memcmp(tag, "data", 4) == 0) {
            if (!have_fmt) goto bad;
            if (fmt != 1 || ch != 1 || bits != 16) {
                fprintf(stderr, "ns48: %s must be 16-bit PCM mono "
                                "(got format %u, %u channel(s), %u bits)\n",
                        path, fmt, ch, bits);
                fclose(f); return 0;
            }
            w->rate   = (int32_t)rate;
            w->frames = sz / 2;
            w->pcm    = malloc(sz ? sz : 2);
            if (!w->pcm) goto bad;
            if (fread(w->pcm, 2, w->frames, f) != w->frames) goto bad;
            fclose(f);
            return 1;
        }
        if (fseek(f, next, SEEK_SET) != 0) goto bad;
    }
bad:
    fprintf(stderr, "ns48: %s is not a WAV file this tool can read\n", path);
    if (f) fclose(f);
    return 0;
}

static int wav_write(const char *path, const wav *w)
{
    FILE *f = fopen(path, "wb");
    uint32_t data = w->frames * 2, riff = 36 + data, byte_rate = (uint32_t)w->rate * 2;
    uint16_t one = 1, ch = 1, align = 2, bits = 16;
    uint32_t sub1 = 16;
    uint32_t rate = (uint32_t)w->rate;

    if (!f) { fprintf(stderr, "ns48: cannot write %s\n", path); return 0; }
    fwrite("RIFF", 1, 4, f); fwrite(&riff, 4, 1, f); fwrite("WAVE", 1, 4, f);
    fwrite("fmt ", 1, 4, f); fwrite(&sub1, 4, 1, f);
    fwrite(&one, 2, 1, f); fwrite(&ch, 2, 1, f); fwrite(&rate, 4, 1, f);
    fwrite(&byte_rate, 4, 1, f); fwrite(&align, 2, 1, f); fwrite(&bits, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&data, 4, 1, f);
    fwrite(w->pcm, 2, w->frames, f);
    fclose(f);
    return 1;
}

#endif  /* SE_WAV_H */
