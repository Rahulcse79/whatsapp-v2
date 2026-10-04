#!/usr/bin/env bash
#
# Fetches the speech and noise the benchmark needs into work/, which is gitignored.
#
# Nothing here is vendored and nothing is committed. These are test fixtures for a
# measurement that runs on a developer's Mac: they are downloaded, measured and left
# alone. That is deliberate - N-2 forbids a *build* that fetches, and this is not a build.
# The repository must still build and ship with work/ absent.
#
# ## The corpus, and the one thing it cannot do
#
# Speech and most of the noise come from Microsoft's MS-SNSD, which is the corpus the
# DNS-Challenge baselines were built on, so a DNSMOS number measured here is comparable
# with published ones. It is **16 kHz**, and that is a real limitation of this rig rather
# than a detail: the capture path being measured runs at 48 kHz, and upsampled 16 kHz
# speech has nothing above 8 kHz for the suppressor to damage in the top two thirds of its
# band. The bias is therefore toward flattering the 48 kHz configuration, which is the
# safe direction for a go/no-go: a configuration that fails here fails on a handset too.
# `bench.py` prints this caveat with every table so it cannot be read off a summary and
# forgotten.
#
# Music and bells are not in MS-SNSD and they are the two cases stage 1 is least likely to
# survive, so they are fetched from Wikimedia Commons instead. Each is named with its
# licence below; both are used locally and neither is redistributed.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$here/work"
speech="$work/speech"
noise="$work/noise"

mkdir -p "$speech" "$noise" "$work/dnsmos"

command -v ffmpeg >/dev/null 2>&1 || {
  echo "fetch-corpus: ffmpeg is needed to decode the Commons .ogg/.opus assets." >&2
  echo "  MacPorts: sudo port install ffmpeg   Homebrew: brew install ffmpeg" >&2
  exit 1
}

get() {  # get <url> <dest>; skips what is already there so re-running is cheap
  [ -s "$2" ] && { echo "    have $(basename "$2")"; return 0; }
  curl -fsSL -m 180 -o "$2.part" "$1" || {
    echo "fetch-corpus: failed to download $1" >&2
    rm -f "$2.part"; return 1
  }
  mv "$2.part" "$2"
  echo "    got  $(basename "$2")"
}

# ------------------------------------------------------------------- DNSMOS
#
# The P.835 model, pinned by SHA-256. DNSMOS is the metric the exit criteria are written
# in, so the model that produces it is an input to a decision and is checked like one: a
# silently updated model would move every number in the table and invalidate comparisons
# with runs from last week.
echo "DNSMOS P.835 model:"
D=https://raw.githubusercontent.com/microsoft/DNS-Challenge/master/DNSMOS/DNSMOS
get "$D/sig_bak_ovr.onnx" "$work/dnsmos/sig_bak_ovr.onnx"
get "$D/model_v8.onnx"    "$work/dnsmos/model_v8.onnx"

echo "  verifying:"
( cd "$work/dnsmos" && shasum -a 256 -c /dev/stdin <<'SUMS'
269fbebdb513aa23cddfbb593542ecc540284a91849ac50516870e1ac78f6edd  sig_bak_ovr.onnx
9246480c58567bc6affd4200938e77eef49468c8bc7ed3776d109c07456f6e91  model_v8.onnx
SUMS
) || { echo "fetch-corpus: a DNSMOS model does not match its pin. Refusing to measure
  against an unknown model - see this script's header." >&2; exit 1; }

# -------------------------------------------------------------- clean speech
#
# MS-SNSD's clean_train, which is VCTK read speech downsampled to 16 kHz. Four speakers,
# two of each sex, several utterances each: DNSMOS scores 9.01 s windows, and single MS-SNSD
# utterances are 2-6 s, so mix.py concatenates per speaker before mixing.
echo "clean speech (MS-SNSD clean_train, VCTK @ 16 kHz):"
S=https://raw.githubusercontent.com/microsoft/MS-SNSD/master/clean_train
for spk in p234 p237 p245 p246; do
  for n in 001 002 003 004 005; do
    get "$S/${spk}_${n}.wav" "$speech/${spk}_${n}.wav"
  done
done

# --------------------------------------------------------------------- noise
#
# One file per class the exit criteria name. MS-SNSD's own class names are on the left of
# the arrow; the name on the right is what the tables call it.
echo "noise (MS-SNSD noise_train @ 16 kHz):"
N=https://raw.githubusercontent.com/microsoft/MS-SNSD/master/noise_train
get "$N/AirConditioner_1.wav" "$noise/fan.wav"        # fan      - steady, broadband
get "$N/Traffic_1.wav"        "$noise/traffic.wav"    # traffic  - steady with transients
get "$N/Station_1.wav"        "$noise/train.wav"      # train    - platform: rumble + PA
get "$N/Square_1.wav"         "$noise/street.wav"     # street   - open air, non-stationary
get "$N/Babble_1.wav"         "$noise/babble.wav"     # babble   - the Phase 2 case; see README

# Music and bells, from Wikimedia Commons. These two are here precisely because they are
# the hard cases: a statistical noise suppressor estimates a slowly varying noise floor,
# and neither tonal music nor an impulsive bell is one.
#
#   music - "Beethoven - Piano Sonata No. 28 in A Major, Op. 101 - I." - PUBLIC DOMAIN
#           https://commons.wikimedia.org/wiki/File:Beethoven_-_Piano_Sonata_No._28_in_A_Major,_Op._101_-_I._Etwas_lebhaft,_und_mit_der_innigsten_Empfindung.ogg
#   bells - "Chorzow Luther church bell ringing 2021" - CC BY-SA 4.0,
#           by Wikimedia Commons user Pudelek
#           https://commons.wikimedia.org/wiki/File:Chorzow_Luther_church_bell_ringing_2021.opus
echo "noise (Wikimedia Commons, transcoded to 16 kHz mono):"
C=https://commons.wikimedia.org/wiki/Special:FilePath
get "$C/Beethoven_-_Piano_Sonata_No._28_in_A_Major%2C_Op._101_-_I._Etwas_lebhaft%2C_und_mit_der_innigsten_Empfindung.ogg" \
    "$work/.music.ogg"
get "$C/Chorzow_Luther_church_bell_ringing_2021.opus" "$work/.bells.opus"

# -ac 1 -ar 16000 to match MS-SNSD exactly, and 60 s is plenty: mix.py tiles whatever it
# is given to the length of the speech.
for pair in ".music.ogg:music" ".bells.opus:bells"; do
  src="$work/${pair%%:*}"; dst="$noise/${pair##*:}.wav"
  [ -s "$dst" ] && { echo "    have $(basename "$dst")"; continue; }
  ffmpeg -nostdin -loglevel error -y -i "$src" -t 60 -ac 1 -ar 16000 -c:a pcm_s16le "$dst"
  echo "    made $(basename "$dst")"
done

# ------------------------------------------------------- the speaker embedding model
#
# WeSpeaker's voxceleb_ECAPA512_LM: ECAPA-TDNN, 192 dimensions, CC-BY-4.0, trained on
# VoxCeleb. What `enrolment_bench.py` and `gate_bench.py` turn a minute of speech into.
#
# Pinned by SHA-256 like the DNSMOS models and for the same reason: it decides a gate
# threshold, so a silently updated model moves every number in VOICE-PROFILE.md.
echo "speaker embedding model (WeSpeaker ECAPA-TDNN, CC-BY-4.0):"
mkdir -p "$work/speaker"
get "https://huggingface.co/Wespeaker/wespeaker-ecapa-tdnn512-LM/resolve/main/voxceleb_ECAPA512_LM.onnx" \
    "$work/speaker/ecapa512.onnx"
( cd "$work/speaker" && shasum -a 256 -c /dev/stdin <<'SUMS'
d71b85d9b48058ef68004f04f1b78acebefb9dfcf542e19b976a12a5ad1f10b0  ecapa512.onnx
SUMS
) || { echo "fetch-corpus: the embedding model does not match its pin." >&2; exit 1; }

# -------------------------------------------------------------- speakers, for the gate
#
# Twelve speakers rather than the four the DNSMOS matrix needs: a verification bench with
# four speakers has twelve impostor pairs, which is too few to put a number on a false
# accept rate. Six utterances each - four to enrol on, the rest held out.
echo "speakers (MS-SNSD clean_train, 12 speakers x 6 utterances):"
mkdir -p "$work/speakers"
for spk in p234 p237 p241 p245 p246 p247 p248 p249 p251 p255 p260 p263; do
  for n in 001 002 003 004 005 006; do
    get "$S/${spk}_${n}.wav" "$work/speakers/${spk}_${n}.wav" || true
  done
done

echo
echo "corpus ready: $work"
echo "  speech: $(ls "$speech" | wc -l | tr -d ' ') files    noise: $(ls "$noise" | wc -l | tr -d ' ') classes"
