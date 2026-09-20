#!/usr/bin/env python3
"""A minimal scripted SIP/RTP user agent for the Lyra recording integration tests.

It can act as a callee (register, auto-answer a Lyra INVITE, stream Lyra RTP) or a
caller (register, INVITE a number, stream Lyra RTP on 200 OK). RTP payloads come from a
raw .lyra file produced by tools/lyra_gen_frames (real Lyra frames), so the stream that
reaches FreeSWITCH is genuine Lyra that mod_lyra must decode to record.

No third-party packages: raw UDP sockets, MD5 digest auth, hand-built SDP. This is a
test tool, not production code — it implements exactly the SIP subset the local
FreeSWITCH needs and nothing more.

Usage:
  sipua.py callee --user 1011 --seconds 12 [--host H] [--port 5060] [--password 1234] [--lyra frames.lyra]
  sipua.py caller --user 1010 --dial 9911 --seconds 10 [...]
"""
import argparse
import hashlib
import os
import random
import re
import socket
import struct
import sys
import threading
import time

LYRA_PT = "96"          # dynamic PT we offer/answer for lyra/16000
DTMF_PT = "120"
FRAME_MS = 20
FRAME_BYTES = 8         # 3200 bit/s; matches lyra_gen_frames default


def digest(user, pwd, method, uri, realm, nonce):
    ha1 = hashlib.md5(f"{user}:{realm}:{pwd}".encode()).hexdigest()
    ha2 = hashlib.md5(f"{method}:{uri}".encode()).hexdigest()
    return hashlib.md5(f"{ha1}:{nonce}:{ha2}".encode()).hexdigest()


def hdr(name, msg):
    """Return a single header line WITHOUT the trailing CR. Using '.*' would capture the
    '\\r' (only '\\n' is excluded by '.'), and re-joining with '\\r\\n' would insert a blank
    line that truncates the SIP headers — which makes FreeSWITCH reject the message."""
    m = re.search(rf'^{name}:[^\r\n]*', msg, re.I | re.M)
    return m.group(0) if m else ""


def parse_auth(msg):
    m = re.search(r'(?:WWW|Proxy)-Authenticate:\s*Digest\s*(.*)', msg, re.I)
    if not m:
        return None
    fields = {}
    for part in re.findall(r'(\w+)="?([^",]+)"?', m.group(1)):
        fields[part[0].lower()] = part[1]
    return fields


class RtpStreamer(threading.Thread):
    """Streams Lyra frames from a file at 20 ms, looping until stopped. Also drains the
    receive side so the OS buffer never fills and reports how many packets arrived."""
    daemon = True

    def __init__(self, sock, remote, frames, pt):
        super().__init__()
        self.sock = sock
        self.remote = remote
        self.frames = frames
        self.pt = int(pt)
        self.stop_flag = threading.Event()
        self.rx = 0
        self.tx = 0

    def run(self):
        seq = random.randint(0, 0xFFFF)
        ts = random.randint(0, 0x7FFFFFFF)
        ssrc = random.randint(0, 0x7FFFFFFF)
        self.sock.setblocking(False)
        i = 0
        next_send = time.time()
        while not self.stop_flag.is_set():
            # drain rx
            try:
                while True:
                    self.sock.recv(2048)
                    self.rx += 1
            except BlockingIOError:
                pass
            if self.frames:
                frame = self.frames[i % len(self.frames)]
                i += 1
                hdr = struct.pack("!BBHII", 0x80, self.pt & 0x7F, seq & 0xFFFF, ts & 0xFFFFFFFF, ssrc)
                try:
                    self.sock.sendto(hdr + frame, self.remote)
                    self.tx += 1
                except OSError:
                    pass
                seq += 1
                ts += 320  # samples per 20 ms at 16 kHz
            next_send += FRAME_MS / 1000.0
            delay = next_send - time.time()
            if delay > 0:
                time.sleep(delay)
            else:
                next_send = time.time()

    def stop(self):
        self.stop_flag.set()


def load_frames(path):
    if not path or not os.path.exists(path):
        # A single silence frame so the stream is never empty (keeps FS media alive).
        return [b"\x00" * FRAME_BYTES]
    data = open(path, "rb").read()
    return [data[i:i + FRAME_BYTES] for i in range(0, len(data) - FRAME_BYTES + 1, FRAME_BYTES)] or [b"\x00" * FRAME_BYTES]


def sdp(host, rtp_port):
    """A caller's OFFER: Lyra plus telephone-event."""
    return "\r\n".join([
        "v=0", f"o=- {random.randint(1,2**31)} {random.randint(1,2**31)} IN IP4 {host}",
        "s=lyra-itest", f"c=IN IP4 {host}", "t=0 0",
        f"m=audio {rtp_port} RTP/AVP {LYRA_PT} {DTMF_PT}",
        f"a=rtpmap:{LYRA_PT} lyra/16000", f"a=fmtp:{LYRA_PT} bitrate=3200",
        f"a=rtpmap:{DTMF_PT} telephone-event/16000", "a=sendrecv", ""])


# rtpmap lines to advertise, keyed by payload type, so a UAS can answer whatever it was
# offered rather than dictating its own codec (which would be an invalid SDP answer).
RTPMAP = {"0": "PCMU/8000", "8": "PCMA/8000", "9": "G722/8000",
          "96": "lyra/16000", "120": "telephone-event/16000", "101": "telephone-event/8000"}


def answer_sdp(host, rtp_port, offer):
    """A callee's ANSWER: echo the offer's own rtpmap/fmtp lines for the codecs it listed
    (Lyra first), which is what a real UAS must do — the offerer may use dynamic PTs (FS
    assigns Lyra e.g. 102), so we copy its exact rtpmap rather than guess. Returns
    (sdp, chosen_pt)."""
    offer = offer or ""
    mm = re.search(r'm=audio \d+ RTP/AVP ([\d ]+)', offer)
    pts = mm.group(1).split() if mm else [LYRA_PT, DTMF_PT]
    # the exact rtpmap/fmtp text the offer used, per PT
    rtpmaps = dict(re.findall(r'a=rtpmap:(\d+) (\S+)', offer))
    fmtps = dict(re.findall(r'a=fmtp:(\d+) (.+)', offer))
    lyra_pts = [p for p in pts if 'lyra' in rtpmaps.get(p, '').lower()]
    other = [p for p in pts if p not in lyra_pts and (p in rtpmaps or p in RTPMAP)]
    ordered = lyra_pts + other
    if not ordered:
        ordered = [pts[0]] if pts else [LYRA_PT]
    lines = ["v=0", f"o=- {random.randint(1,2**31)} {random.randint(1,2**31)} IN IP4 {host}",
             "s=lyra-itest", f"c=IN IP4 {host}", "t=0 0",
             f"m=audio {rtp_port} RTP/AVP {' '.join(ordered)}"]
    for p in ordered:
        name = rtpmaps.get(p) or RTPMAP.get(p, "PCMU/8000")
        lines.append(f"a=rtpmap:{p} {name}")
        if p in fmtps:
            lines.append(f"a=fmtp:{p} {fmtps[p]}")
        elif 'lyra' in name.lower():
            lines.append(f"a=fmtp:{p} bitrate=3200")
    lines += ["a=sendrecv", ""]
    chosen = next((p for p in ordered if 'telephone-event' not in (rtpmaps.get(p) or RTPMAP.get(p, '')).lower()), ordered[0])
    return "\r\n".join(lines), chosen


def remote_media(msg):
    """(ip, port, pt) FreeSWITCH offered/answered for audio, or None."""
    cm = re.search(r'c=IN IP4 ([0-9.]+)', msg)
    mm = re.search(r'm=audio (\d+) RTP/AVP ([\d ]+)', msg)
    if not cm or not mm:
        return None
    pts = mm.group(2).split()
    pt = pts[0]
    # prefer the PT mapped to lyra
    for p in pts:
        if re.search(rf'a=rtpmap:{p} lyra/16000', msg, re.I):
            pt = p
            break
    return cm.group(1), int(mm.group(1)), pt


class UA:
    def __init__(self, args, role):
        self.a = args
        self.role = role
        self.host = self._local_ip(args.host)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind((self.host, 0))
        self.sock.settimeout(5)
        self.lport = self.sock.getsockname()[1]
        self.rtp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.rtp.bind((self.host, 0))
        self.rtp_port = self.rtp.getsockname()[1]
        self.call_id = f"{random.randint(0,2**31)}@itest"
        self.tag = f"{random.randint(0,2**31)}"
        self.branch = lambda: f"z9hG4bK{random.randint(0,2**31)}"
        self.cseq = 1
        self.frames = load_frames(args.lyra)

    @staticmethod
    def _local_ip(dst):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((dst, 5060))
            return s.getsockname()[0]
        finally:
            s.close()

    def send(self, msg, to=None):
        self.sock.sendto(msg.encode(), to or (self.a.host, self.a.port))

    def recv(self):
        try:
            data, _ = self.sock.recvfrom(65535)
            return data.decode(errors="replace")
        except socket.timeout:
            return None

    def register(self):
        uri = f"sip:{self.a.host}"
        aor = f"sip:{self.a.user}@{self.a.host}"
        for attempt in range(2):
            br = self.branch()
            lines = [
                f"REGISTER {uri} SIP/2.0",
                f"Via: SIP/2.0/UDP {self.host}:{self.lport};branch={br};rport",
                f"From: <{aor}>;tag={self.tag}",
                f"To: <{aor}>",
                f"Call-ID: reg-{self.call_id}",
                f"CSeq: {self.cseq} REGISTER",
                f"Contact: <sip:{self.a.user}@{self.host}:{self.lport}>",
                "Expires: 120", "Max-Forwards: 70", "Content-Length: 0", "", ""]
            if attempt == 1 and self.auth:
                resp = digest(self.a.user, self.a.password, "REGISTER", uri, self.auth["realm"], self.auth["nonce"])
                lines.insert(6, f'Authorization: Digest username="{self.a.user}", realm="{self.auth["realm"]}", '
                                f'nonce="{self.auth["nonce"]}", uri="{uri}", response="{resp}"')
            self.cseq += 1
            self.send("\r\n".join(lines))
            msg = self.recv()
            if not msg:
                continue
            if msg.startswith("SIP/2.0 401") or msg.startswith("SIP/2.0 407"):
                self.auth = parse_auth(msg)
                continue
            if msg.startswith("SIP/2.0 200"):
                return True
        return False

    auth = None


def run_callee(args):
    ua = UA(args, "callee")
    if not ua.register():
        print("callee: REGISTER failed", file=sys.stderr)
        return 1
    print(f"callee {args.user} registered on {ua.host}:{ua.lport}", flush=True)
    ua.sock.settimeout(args.wait + 5)
    streamer = None
    deadline = time.time() + args.wait + args.seconds + 10
    while time.time() < deadline:
        msg = ua.recv()
        if not msg:
            continue
        first = msg.split("\r\n", 1)[0]
        if msg.startswith("INVITE"):
            via = hdr("Via", msg)
            frm = hdr("From", msg)
            to = hdr("To", msg) + f";tag={ua.tag}"
            cid = hdr("Call-ID", msg)
            cseq = hdr("CSeq", msg)
            media = remote_media(msg)
            # 200 OK answering with the codec(s) we were actually offered (Lyra first).
            body, chosen = answer_sdp(ua.host, ua.rtp_port, msg)
            ok = ["SIP/2.0 200 OK", via, frm, to, cid, cseq,
                  f"Contact: <sip:{args.user}@{ua.host}:{ua.lport}>",
                  "Content-Type: application/sdp", f"Content-Length: {len(body)}", "", body]
            ua.send("\r\n".join(ok))
            if media and not streamer:
                streamer = RtpStreamer(ua.rtp, (media[0], media[1]), ua.frames, chosen)
                streamer.start()
                print(f"callee answered; streaming to {media[0]}:{media[1]} pt={chosen}", flush=True)
        elif msg.startswith("BYE"):
            ua.send("\r\n".join(["SIP/2.0 200 OK", hdr("Via", msg), hdr("From", msg),
                                 hdr("To", msg), hdr("Call-ID", msg), hdr("CSeq", msg),
                                 "Content-Length: 0", "", ""]))
            break
    if streamer:
        streamer.stop()
        time.sleep(0.2)
        print(f"callee done: tx={streamer.tx} rx={streamer.rx}", flush=True)
    return 0


def run_caller(args):
    ua = UA(args, "caller")
    if not ua.register():
        print("caller: REGISTER failed", file=sys.stderr)
        return 1
    uri = f"sip:{args.dial}@{args.host}"
    aor = f"sip:{args.user}@{args.host}"
    to = f"<{uri}>"
    body = sdp(ua.host, ua.rtp_port)
    auth_hdr = None
    remote = None
    invite_branch = ua.branch()
    for attempt in range(2):
        lines = [
            f"INVITE {uri} SIP/2.0",
            f"Via: SIP/2.0/UDP {ua.host}:{ua.lport};branch={invite_branch};rport",
            f"From: <{aor}>;tag={ua.tag}", f"To: {to}",
            f"Call-ID: {ua.call_id}", f"CSeq: {ua.cseq} INVITE",
            f"Contact: <sip:{args.user}@{ua.host}:{ua.lport}>",
            "Max-Forwards: 70", "Content-Type: application/sdp",
            f"Content-Length: {len(body)}"]
        if auth_hdr:
            lines.insert(7, auth_hdr)
        lines += ["", body]
        ua.send("\r\n".join(lines))
        answered = False
        end = time.time() + 10
        while time.time() < end:
            msg = ua.recv()
            if not msg:
                continue
            if msg.startswith("SIP/2.0 401") or msg.startswith("SIP/2.0 407"):
                a = parse_auth(msg)
                # ACK the challenge (a non-2xx final response MUST be ACKed, on the same
                # branch, or FreeSWITCH retransmits it), then retry with credentials.
                ua.send("\r\n".join([f"ACK {uri} SIP/2.0",
                                     f"Via: SIP/2.0/UDP {ua.host}:{ua.lport};branch={invite_branch};rport",
                                     f"From: <{aor}>;tag={ua.tag}", hdr("To", msg),
                                     f"Call-ID: {ua.call_id}", f"CSeq: {ua.cseq} ACK",
                                     "Max-Forwards: 70", "Content-Length: 0", "", ""]))
                resp = digest(args.user, args.password, "INVITE", uri, a["realm"], a["nonce"])
                scheme = "Proxy-Authorization" if msg.startswith("SIP/2.0 407") else "Authorization"
                auth_hdr = (f'{scheme}: Digest username="{args.user}", realm="{a["realm"]}", '
                            f'nonce="{a["nonce"]}", uri="{uri}", response="{resp}"')
                ua.cseq += 1
                invite_branch = ua.branch()
                break
            if msg.startswith("SIP/2.0 200"):
                remote = remote_media(msg)
                to = hdr("To", msg)  # now carries the callee tag
                # ACK for a 2xx uses a NEW branch (it is a separate transaction).
                ack = [f"ACK {uri} SIP/2.0",
                       f"Via: SIP/2.0/UDP {ua.host}:{ua.lport};branch={ua.branch()};rport",
                       f"From: <{aor}>;tag={ua.tag}",
                       to,
                       f"Call-ID: {ua.call_id}", f"CSeq: {ua.cseq} ACK",
                       "Max-Forwards: 70", "Content-Length: 0", "", ""]
                ua.send("\r\n".join(ack))
                answered = True
                break
            if msg.startswith("SIP/2.0 4") or msg.startswith("SIP/2.0 5") or msg.startswith("SIP/2.0 6"):
                print(f"caller: got {msg.splitlines()[0]}", file=sys.stderr)
                return 1
        if answered:
            break
    if not remote:
        print("caller: no 200 OK with media", file=sys.stderr)
        return 1
    streamer = RtpStreamer(ua.rtp, (remote[0], remote[1]), ua.frames, remote[2])
    streamer.start()
    print(f"caller in call; streaming Lyra to {remote[0]}:{remote[1]} pt={remote[2]}", flush=True)
    time.sleep(args.seconds)
    streamer.stop()
    # BYE
    ua.cseq += 1
    bye = [f"BYE {uri} SIP/2.0",
           f"Via: SIP/2.0/UDP {ua.host}:{ua.lport};branch={ua.branch()};rport",
           f"From: <{aor}>;tag={ua.tag}",
           to,  # the To header from the 200 OK, with the callee's tag
           f"Call-ID: {ua.call_id}", f"CSeq: {ua.cseq} BYE",
           "Max-Forwards: 70", "Content-Length: 0", "", ""]
    ua.send("\r\n".join(bye))
    time.sleep(0.3)
    print(f"caller done: tx={streamer.tx} rx={streamer.rx}", flush=True)
    return 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("role", choices=["caller", "callee"])
    p.add_argument("--user", required=True)
    p.add_argument("--dial")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=5060)
    p.add_argument("--password", default="1234")
    p.add_argument("--seconds", type=float, default=10)
    p.add_argument("--wait", type=float, default=5, help="callee: seconds to wait for the INVITE")
    p.add_argument("--lyra", default="", help="raw Lyra frames file from lyra_gen_frames")
    args = p.parse_args()
    return run_caller(args) if args.role == "caller" else run_callee(args)


if __name__ == "__main__":
    sys.exit(main())
