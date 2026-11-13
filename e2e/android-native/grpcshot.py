#!/usr/bin/env python3
"""Take a screenshot from the emulator's own gRPC service.

The guest's `screencap` returns black on this host: without KVM the guest's GL
frames never reach it. The emulator's gRPC `getScreenshot` reads the host-side
framebuffer instead, so it is the one path that could still see what the device
is drawing.

On this host it returns a uniform black 480x960 image for every frame the app
produces -- the only non-black frame it ever answered with was the boot
animation -- which is how the limitation was confirmed rather than assumed. It is
kept because on a host with KVM it is the right way to capture a device
screenshot, and because "we tried this too" belongs in the record.

Minimal hand-rolled HTTP/2 and protobuf: this image has no grpc or protobuf
Python package.
"""
import socket, struct, sys


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def field(num, wire, payload):
    key = varint((num << 3) | wire)
    if wire == 0:
        return key + varint(payload)
    return key + varint(len(payload)) + payload


def frame(typ, flags, sid, payload):
    return (len(payload).to_bytes(3, 'big') + bytes([typ, flags])
            + struct.pack('>I', sid) + payload)


def read_varint(buf, i):
    n = shift = 0
    while True:
        b = buf[i]
        i += 1
        n |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return n, i


def parse(buf):
    i, out = 0, {}
    while i < len(buf):
        key, i = read_varint(buf, i)
        num, wire = key >> 3, key & 7
        if wire == 0:
            val, i = read_varint(buf, i)
        elif wire == 2:
            ln, i = read_varint(buf, i)
            val = buf[i:i + ln]
            i += ln
        else:
            raise SystemExit('unexpected wire type %d' % wire)
        out.setdefault(num, []).append(val)
    return out


def header_block(pairs):
    out = b''
    for name, value in pairs:
        out += (b'\x00' + varint(len(name)) + name
                + varint(len(value)) + value)
    return out


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else '127.0.0.1'
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 8554
    dest = sys.argv[3] if len(sys.argv) > 3 else 'shot.png'
    sid = 1
    request = field(1, 0, 0)  # ImageFormat{format: PNG}
    body = b'\x00' + struct.pack('>I', len(request)) + request
    path = '/android.emulation.control.EmulatorController/getScreenshot'
    sock = socket.create_connection((host, port), timeout=90)
    sock.settimeout(90)
    sock.sendall(b'PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n')
    sock.sendall(frame(0x04, 0x00, 0, b''))  # client SETTINGS
    block = header_block([
        (b':method', b'POST'),
        (b':scheme', b'http'),
        (b':path', path.encode()),
        (b':authority', ('%s:%d' % (host, port)).encode()),
        (b'content-type', b'application/grpc'),
        (b'te', b'trailers'),
    ])
    sock.sendall(frame(0x01, 0x04, sid, block))  # HEADERS, END_HEADERS
    sock.sendall(frame(0x00, 0x01, sid, body))   # DATA, END_STREAM
    data = b''
    buf = b''
    done = False
    while not done:
        chunk = sock.recv(65536)
        if not chunk:
            break
        buf += chunk
        while len(buf) >= 9:
            ln = int.from_bytes(buf[:3], 'big')
            if len(buf) < 9 + ln:
                break
            typ, flags = buf[3], buf[4]
            got = int.from_bytes(buf[5:9], 'big') & 0x7FFFFFFF
            payload = buf[9:9 + ln]
            buf = buf[9 + ln:]
            if typ == 0x04 and not flags & 0x01:
                sock.sendall(frame(0x04, 0x01, 0, b''))  # SETTINGS ack
            elif typ == 0x00 and got == sid:
                data += payload
                if flags & 0x01:
                    done = True
                    break
            elif typ == 0x01 and got == sid and flags & 0x01:
                done = True
                break
    png = None
    i = 0
    while i + 5 <= len(data):
        ln = struct.unpack('>I', data[i + 1:i + 5])[0]
        msg = data[i + 5:i + 5 + ln]
        i += 5 + ln
        fields = parse(msg)
        if 4 in fields:
            png = fields[4][0]
            break
    if not png:
        raise SystemExit('no image in reply (%d bytes)' % len(data))
    open(dest, 'wb').write(png)
    print('wrote', dest, len(png), 'bytes')


main()
