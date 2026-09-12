// A hand-written stand-in for the protoc output of lyra_config.proto.
//
// This file is NOT generated. It exists so that Lyra can be built without a protobuf
// toolchain: lyra_config.proto declares one message with one field —
//
//     message LyraConfig { optional int32 identifier = 1; }
//
// — and lyra_config.binarypb, the only file ever parsed with it, is two bytes long. A
// host `protoc` plus an Android `libprotobuf-lite` is a dependency larger than the codec
// itself, carried for a version check. The class below has exactly the two members
// lyra_config.h uses, ParseFromIstream() and identifier(), and implements the proto2
// wire format for that message in full: field 1 as a varint, every other field and wire
// type skipped, groups included, so a future .binarypb that grows a field still parses.
//
// Keep it in step with lyra_config.proto. Lyra v1.3.2; see the patch that adds this file
// for the reasoning in the repository that carries it.
#ifndef LYRA_LYRA_CONFIG_PB_H_
#define LYRA_LYRA_CONFIG_PB_H_

#include <cstdint>
#include <istream>
#include <iterator>
#include <string>

namespace third_party {
namespace lyra_codec {
namespace lyra {

class LyraConfig {
 public:
  // Parses a serialized LyraConfig. Returns false on malformed input, exactly as the
  // protobuf implementation would; a missing field leaves identifier() at its proto2
  // default of 0.
  bool ParseFromIstream(std::istream* input) {
    const std::string bytes((std::istreambuf_iterator<char>(*input)),
                            std::istreambuf_iterator<char>());
    return ParseFromString(bytes);
  }

  bool ParseFromString(const std::string& bytes) {
    identifier_ = 0;
    const uint8_t* p = reinterpret_cast<const uint8_t*>(bytes.data());
    const uint8_t* end = p + bytes.size();
    int group_depth = 0;
    while (p < end) {
      uint64_t key;
      if (!ReadVarint(&p, end, &key)) return false;
      const uint64_t field = key >> 3;
      const unsigned wire_type = static_cast<unsigned>(key & 7);
      switch (wire_type) {
        case 0: {  // varint
          uint64_t value;
          if (!ReadVarint(&p, end, &value)) return false;
          if (field == 1 && group_depth == 0) {
            identifier_ = static_cast<int32_t>(value);
          }
          break;
        }
        case 1:  // 64-bit
          if (end - p < 8) return false;
          p += 8;
          break;
        case 2: {  // length-delimited
          uint64_t length;
          if (!ReadVarint(&p, end, &length)) return false;
          if (static_cast<uint64_t>(end - p) < length) return false;
          p += length;
          break;
        }
        case 3:  // start group (deprecated, still legal proto2)
          ++group_depth;
          break;
        case 4:  // end group
          if (group_depth == 0) return false;
          --group_depth;
          break;
        case 5:  // 32-bit
          if (end - p < 4) return false;
          p += 4;
          break;
        default:
          return false;
      }
    }
    return group_depth == 0;
  }

  int32_t identifier() const { return identifier_; }

 private:
  static bool ReadVarint(const uint8_t** p, const uint8_t* end, uint64_t* out) {
    uint64_t result = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (*p >= end) return false;
      const uint8_t byte = *(*p)++;
      result |= static_cast<uint64_t>(byte & 0x7f) << shift;
      if ((byte & 0x80) == 0) {
        *out = result;
        return true;
      }
    }
    return false;  // more than ten bytes: not a varint
  }

  int32_t identifier_ = 0;
};

}  // namespace lyra
}  // namespace lyra_codec
}  // namespace third_party

#endif  // LYRA_LYRA_CONFIG_PB_H_
