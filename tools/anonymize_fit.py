"""Make a real FIT file safe to commit as a test fixture.

    uv run --python 3.12 --no-project python -I tools/anonymize_fit.py <in.fit> <out.fit>
    uv run --python 3.12 --no-project python -I tools/anonymize_fit.py --dump <file.fit>   # list string fields

Patches the file in place byte-for-byte (so every other field, message count and size is untouched), then fixes the CRCs:
- every string field in the messages that carry names (user profile, device info, file id, ANT/BLE devices)
  is replaced with a same-length placeholder;
- serial numbers, the user profile's height/weight and its undocumented 32-bit field 41 are replaced with fixed values;
- GPS positions (virtual-world ones too) are replaced with a synthetic loop that starts at FAKE_ORIGIN and has the same
  total length, built from each record's own distance, so pace and distance still agree.
"""
import math
import struct
import sys

FAKE_ORIGIN = (38.50, -98.50)  # open farmland in the central US; the loop is not a real route
FAKE_SERIAL = 1234567890
SEMI = 2 ** 31 / 180.0
INVALID_SINT32 = 0x7FFFFFFF
# global message number -> name; strings in these are blanked
NAME_MESSAGES = {0, 3, 23, 79, 147, 148, 149, 150, 151, 152}  # file_id, user_profile, device_info, ..., ant/ble


def crc16(data, crc=0):
    table = [0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
             0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400]
    for b in data:
        tmp = table[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc ^= tmp ^ table[b & 0xF]
        tmp = table[crc & 0xF]
        crc = (crc >> 4) & 0x0FFF
        crc ^= tmp ^ table[(b >> 4) & 0xF]
    return crc


def messages(buf):
    """Yield (global_num, little_endian, [(field_num, offset, size, base_type)], record_start) for every data message."""
    header = buf[0]
    pos, end = header, header + struct.unpack_from("<I", buf, 4)[0]
    defs = {}
    while pos < end:
        h = buf[pos]
        start = pos
        pos += 1
        if h & 0x80:  # compressed timestamp header: data message of local type bits 5-6
            local = (h >> 5) & 3
        elif h & 0x40:
            dev = bool(h & 0x20)
            local = h & 0x0F
            le = buf[pos + 1] == 0
            gnum = struct.unpack_from("<H" if le else ">H", buf, pos + 2)[0]
            n = buf[pos + 4]
            fields = [tuple(buf[pos + 5 + 3 * i: pos + 8 + 3 * i]) for i in range(n)]
            pos += 5 + 3 * n
            dev_size = 0
            if dev:
                nd = buf[pos]
                dev_size = sum(buf[pos + 2 + 3 * i] for i in range(nd))
                pos += 1 + 3 * nd
            defs[local] = (gnum, le, fields, dev_size)
            continue
        else:
            local = h & 0x0F
        gnum, le, fields, dev_size = defs[local]
        off, layout = pos, []
        for num, size, base in fields:
            layout.append((num, off, size, base & 0x1F))
            off += size
        yield gnum, le, layout, start
        pos = off + dev_size


def anonymize(buf):
    buf = bytearray(buf)
    # Pass 1: names, serials, body measurements.
    for gnum, le, layout, _ in messages(buf):
        for num, off, size, base in layout:
            if gnum in NAME_MESSAGES and base == 7 and buf[off]:
                buf[off:off + size] = (b"Anon" + b"\0" * size)[:size]
            elif gnum in (0, 23) and num == 3 and size == 4:  # serial_number
                struct.pack_into("<I" if le else ">I", buf, off, FAKE_SERIAL)
            elif gnum == 3 and num == 41 and size == 4:  # undocumented 32-bit value in user_profile; looks like an ID
                struct.pack_into("<I" if le else ">I", buf, off, 0)
            elif gnum == 3 and num == 3 and size == 1:  # height, scale 100
                buf[off] = 175
            elif gnum == 3 and num == 4 and size == 2:  # weight, scale 10
                struct.pack_into("<H" if le else ">H", buf, off, 700)
    # Pass 2: GPS.
    rows = []
    for gnum, le, layout, _ in messages(buf):
        if gnum != 20:
            continue
        f = {num: (off, size) for num, off, size, _ in layout}
        if 0 in f and 1 in f:
            rows.append((le, f))
    if not rows:
        return buf
    total = 0.0
    for le, f in rows:
        if 5 in f:
            d = struct.unpack_from("<I" if le else ">I", buf, f[5][0])[0]
            if d != 0xFFFFFFFF:
                total = max(total, d / 100.0)
    total = total or float(len(rows))
    lat, lon, last_d, s = FAKE_ORIGIN[0], FAKE_ORIGIN[1], 0.0, 0.0
    for le, f in rows:
        o_lat, o_lon = f[0][0], f[1][0]
        if struct.unpack_from("<i" if le else ">i", buf, o_lat)[0] == INVALID_SINT32:
            continue
        d = last_d
        if 5 in f:
            raw = struct.unpack_from("<I" if le else ">I", buf, f[5][0])[0]
            if raw != 0xFFFFFFFF:
                d = raw / 100.0
        else:
            d = last_d + 1.0
        step, last_d, s = d - last_d, d, d
        heading = 2 * math.pi * s / total + 0.4 * math.sin(14 * math.pi * s / total)
        lat += step * math.cos(heading) / 111320.0
        lon += step * math.sin(heading) / (111320.0 * math.cos(math.radians(lat)))
        struct.pack_into("<i" if le else ">i", buf, o_lat, round(lat * SEMI))
        struct.pack_into("<i" if le else ">i", buf, o_lon, round(lon * SEMI))
    return buf


def finish(buf):
    header = buf[0]
    if header >= 14:
        struct.pack_into("<H", buf, 12, crc16(bytes(buf[:12])))
    struct.pack_into("<H", buf, len(buf) - 2, crc16(bytes(buf[:-2])))
    return buf


def dump(buf):
    for gnum, le, layout, _ in messages(buf):
        for num, off, size, base in layout:
            if base == 7 and buf[off]:
                print(gnum, num, bytes(buf[off:off + size]).split(b"\0")[0].decode("utf-8", "replace"))


if __name__ == "__main__":
    if sys.argv[1] == "--dump":
        dump(open(sys.argv[2], "rb").read())
    else:
        data = open(sys.argv[1], "rb").read()
        open(sys.argv[2], "wb").write(finish(anonymize(data)))
