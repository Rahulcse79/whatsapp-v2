//
//  VideoView.swift
//  Putting PJSIP's own render views into SwiftUI.
//
//  pjsua creates and owns the view that video is drawn into — on iOS a `UIView` backed
//  by Metal or OpenGL ES, reached through `pjsua_vid_win_get_info`. It is not ours to
//  create, keep or free; it is ours to put on screen and, crucially, to **give back**.
//
//  ## The two defects this file is written around
//
//  1. **A view can only have one superview.** The same pjsua view shown in two places —
//     a tile and a full-screen presentation, say — is removed from the first the moment
//     it is added to the second, and on re-subcomposition SwiftUI can ask for both
//     within one frame. The Android equivalent crashed outright. Here the container
//     removes any existing subview before adding, and `updateUIView` is a no-op when
//     the view has not changed, so an identical re-render touches nothing.
//
//  2. **A surface handed to a native stack and never taken back is a texture it keeps
//     writing into after the screen has gone** — every frame of the call leaked, and on
//     some devices a crash. `dismantleUIView` removes it, which is the one place that
//     reliably runs when SwiftUI tears the view down.
//

import SwiftUI
import UIKit

/// Hosts a `UIView` the SIP stack owns.
struct NativeVideoView: UIViewRepresentable {

    /// pjsua's view, or nil when there is no stream. Nil draws an empty container
    /// rather than disappearing, so the layout does not jump when video starts.
    let videoView: UIView?

    /// `.scaleAspectFit` letterboxes and shows the whole picture; `.scaleAspectFill`
    /// crops to fill. A self-view wants fill, a remote tile wants fit — a remote tile
    /// that crops hides the other person's face at the edges of the frame.
    var contentMode: UIView.ContentMode = .scaleAspectFit

    func makeUIView(context: Context) -> VideoContainerView {
        let container = VideoContainerView()
        container.backgroundColor = .black
        container.contentMode = contentMode
        container.attach(videoView)
        return container
    }

    func updateUIView(_ container: VideoContainerView, context: Context) {
        container.contentMode = contentMode
        container.attach(videoView)
    }

    static func dismantleUIView(_ container: VideoContainerView, coordinator: ()) {
        container.attach(nil)
    }
}

/// A plain container that owns the attachment rules.
///
/// A class rather than logic inside the representable because the "is it already mine"
/// check has to survive between `updateUIView` calls, and a struct has nowhere to keep
/// it that is not also recreated.
final class VideoContainerView: UIView {

    private weak var attached: UIView?

    func attach(_ view: UIView?) {
        guard attached !== view else {
            // Same view, already here. Returning early is not an optimisation: calling
            // addSubview with the current subview reorders the hierarchy and makes the
            // renderer drop a frame, which is visible as a flicker on every redraw.
            layoutAttached()
            return
        }

        attached?.removeFromSuperview()
        attached = view

        guard let view else { return }
        // It may still be in somebody else's hierarchy — see defect 1 in the file header.
        view.removeFromSuperview()
        view.translatesAutoresizingMaskIntoConstraints = true
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(view)
        layoutAttached()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutAttached()
    }

    private func layoutAttached() {
        attached?.frame = bounds
    }

    deinit {
        // Not just tidiness. If this runs while the stack is still rendering, the view
        // must be out of a hierarchy that is about to be freed.
        attached?.removeFromSuperview()
    }
}

/// The remote party's video for one call, or a placeholder.
struct RemoteVideoTile: View {
    @ObservedObject var engine: SipEngine
    let callId: Int

    var body: some View {
        ZStack {
            Color.black
            if let view = engine.remoteVideoViews[callId] {
                NativeVideoView(videoView: view, contentMode: .scaleAspectFit)
            } else {
                // Never an empty black rectangle with no explanation: "the tile is black"
                // is the single most common video report, and distinguishing "no stream
                // yet" from "a stream that is not decoding" starts here.
                VStack(spacing: 8) {
                    Image(systemName: "video.slash.fill")
                        .font(.system(size: 28))
                        .foregroundStyle(.white.opacity(0.5))
                    Text("No video")
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
        }
        // The revision counter is what makes SwiftUI re-evaluate: a dictionary of
        // UIViews has no useful equality, so observing it alone changes nothing.
        .id(engine.videoRevision)
    }
}

/// The local camera preview, cropped to fill.
struct LocalPreviewTile: View {
    let previewView: UIView?

    var body: some View {
        ZStack {
            Color.black
            if let previewView {
                NativeVideoView(videoView: previewView, contentMode: .scaleAspectFill)
            } else {
                Image(systemName: "person.crop.square")
                    .font(.system(size: 20))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
