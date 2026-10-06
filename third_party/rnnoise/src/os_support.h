/* Copyright (C) 2007 Jean-Marc Valin
 *
 * File: os_support.h
 * Memory macros, with the definitions Opus gives them.
 *
 * This file is NOT in the rnnoise 0.2 release tarball. It is added by
 * pjsip/patches/0008-rnnoise-os-support-header.patch; see that patch for why rnnoise
 * cannot be built for ARM without it.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE FOUNDATION OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

#ifndef OS_SUPPORT_H
#define OS_SUPPORT_H

#include <string.h>

/* Only the three memory macros, and only because `vec.h` and `vec_neon.h` use them.
 *
 * Opus's own os_support.h additionally wraps malloc/free/realloc behind
 * opus_alloc/opus_free and carries a stack-allocation helper. None of that is reproduced:
 * rnnoise references none of it, and copying an allocator this tree does not call would be
 * three more things that could diverge from upstream Opus without anybody noticing.
 *
 * The definitions are Opus's verbatim, including the `0*((dst)-(src))` term - which is not
 * decoration. It is a compile-time type check: the subtraction is only valid when both
 * pointers have the same type, so a COPY between mismatched types fails to compile instead
 * of copying the wrong number of bytes.
 */

/** Copy n elements from src to dst. The 0* term provides compile-time type checking  */
#ifndef OVERRIDE_OPUS_COPY
#define OPUS_COPY(dst, src, n) (memcpy((dst), (src), (n)*sizeof(*(dst)) + 0*((dst)-(src)) ))
#endif

/** Copy n elements from src to dst, allowing overlapping regions. The 0* term
    provides compile-time type checking */
#ifndef OVERRIDE_OPUS_MOVE
#define OPUS_MOVE(dst, src, n) (memmove((dst), (src), (n)*sizeof(*(dst)) + 0*((dst)-(src)) ))
#endif

/** Set n elements of dst to zero */
#ifndef OVERRIDE_OPUS_CLEAR
#define OPUS_CLEAR(dst, n) (memset((dst), 0, (n)*sizeof(*(dst))))
#endif

#endif /* OS_SUPPORT_H */
