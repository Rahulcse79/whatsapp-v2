package com.whatsappv2.data.sip.call

/**
 * The recording half of the SDK seam (Task 58).
 *
 * Two functions, and its own interface for that reason: `PjsipCallRecorder` needs to
 * start and stop a recording, and nothing about that job should come with the ability to
 * hang up a call. A narrow interface is also a narrow fake.
 *
 * The stack writes plaintext to the path it is given; sealing it is the store's job, above
 * this line (§7, DoD 12).
 */
internal interface SipRecordingGateway {

    /**
     * Starts writing this call's media to [filePath] (Task 58).
     *
     * The path is chosen above, in the encrypted store — this only writes where it is
     * told. What Android permits an app to capture is documented in `docs/security.md`
     * and is narrower than "the call".
     */
    fun startRecording(callKey: String, filePath: String)

    /** Stops recording and closes the file. A no-op for a call that was not recording. */
    fun stopRecording(callKey: String)
}
